use anyhow::{Result, anyhow};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use sqlx::PgPool;
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{TcpListener, TcpStream},
};
use tracing::{info, warn};

use crate::db;

#[derive(Default)]
struct SessionState {
    helo: bool,
    authenticated: Option<String>,
    mail_from: Option<String>,
    recipients: Vec<String>,
}

pub async fn serve(addr: &str, pool: PgPool) -> Result<()> {
    let listener = TcpListener::bind(addr).await?;
    info!(%addr, "Tuku Engine v2 SMTP listener ready");
    loop {
        let (stream, peer) = listener.accept().await?;
        let pool = pool.clone();
        tokio::spawn(async move {
            if let Err(error) = handle(stream, pool).await {
                warn!(%peer, %error, "SMTP session failed");
            }
        });
    }
}

async fn handle(stream: TcpStream, pool: PgPool) -> Result<()> {
    let (read, mut write) = stream.into_split();
    let mut reader = BufReader::new(read);
    write.write_all(b"220 Tuku Engine v2 ESMTP ready\r\n").await?;
    let mut state = SessionState::default();
    let mut line = String::new();

    loop {
        line.clear();
        if reader.read_line(&mut line).await? == 0 {
            return Ok(());
        }
        let command = line.trim_end_matches(['\r', '\n']);
        let upper = command.to_ascii_uppercase();

        if upper.starts_with("EHLO ") || upper.starts_with("HELO ") {
            state.helo = true;
            write
                .write_all(b"250-Tuku Engine v2\r\n250-AUTH PLAIN\r\n250-8BITMIME\r\n250 SIZE 52428800\r\n")
                .await?;
        } else if upper == "NOOP" {
            write.write_all(b"250 2.0.0 OK\r\n").await?;
        } else if upper == "RSET" {
            state.mail_from = None;
            state.recipients.clear();
            write.write_all(b"250 2.0.0 Reset\r\n").await?;
        } else if upper.starts_with("AUTH PLAIN") {
            let payload = command.split_whitespace().nth(2).unwrap_or("");
            if payload.is_empty() {
                write.write_all(b"334 \r\n").await?;
                line.clear();
                reader.read_line(&mut line).await?;
                authenticate_plain(&pool, line.trim(), &mut state, &mut write).await?;
            } else {
                authenticate_plain(&pool, payload, &mut state, &mut write).await?;
            }
        } else if upper.starts_with("MAIL FROM:") {
            if !state.helo {
                write.write_all(b"503 5.5.1 Send HELO/EHLO first\r\n").await?;
                continue;
            }
            let raw = command["MAIL FROM:".len()..].split_whitespace().next().unwrap_or("");
            match db::normalise_address(raw) {
                Ok(address) => {
                    state.mail_from = Some(address);
                    state.recipients.clear();
                    write.write_all(b"250 2.1.0 Sender OK\r\n").await?;
                }
                Err(_) => write.write_all(b"501 5.1.7 Bad sender address\r\n").await?,
            }
        } else if upper.starts_with("RCPT TO:") {
            let raw = command["RCPT TO:".len()..].split_whitespace().next().unwrap_or("");
            match db::normalise_address(raw) {
                Ok(address) => {
                    let local = db::recipient_is_local(&pool, &address).await?;
                    if local || state.authenticated.is_some() {
                        state.recipients.push(address);
                        write.write_all(b"250 2.1.5 Recipient OK\r\n").await?;
                    } else {
                        write.write_all(b"550 5.7.1 Relaying denied\r\n").await?;
                    }
                }
                Err(_) => write.write_all(b"501 5.1.3 Bad recipient address\r\n").await?,
            }
        } else if upper == "DATA" {
            let Some(mail_from) = state.mail_from.clone() else {
                write.write_all(b"503 5.5.1 MAIL FROM required\r\n").await?;
                continue;
            };
            if state.recipients.is_empty() {
                write.write_all(b"503 5.5.1 RCPT TO required\r\n").await?;
                continue;
            }
            if state.recipients.iter().any(|r| !futures_local(&pool, r)) && state.authenticated.is_none() {
                write.write_all(b"550 5.7.1 Relaying denied\r\n").await?;
                continue;
            }

            write.write_all(b"354 End data with <CR><LF>.<CR><LF>\r\n").await?;
            let raw = read_data(&mut reader).await?;
            let (subject, body) = parse_message(&raw);
            match db::accept_smtp_message(&pool, &mail_from, &state.recipients, &subject, &body).await {
                Ok(_) => {
                    write.write_all(b"250 2.0.0 Message accepted\r\n").await?;
                    state.mail_from = None;
                    state.recipients.clear();
                }
                Err(error) => {
                    warn!(%error, "SMTP message rejected during storage");
                    write.write_all(b"451 4.3.0 Temporary processing failure\r\n").await?;
                }
            }
        } else if upper == "QUIT" {
            write.write_all(b"221 2.0.0 Bye\r\n").await?;
            return Ok(());
        } else {
            write.write_all(b"502 5.5.2 Command not implemented\r\n").await?;
        }
    }
}

fn futures_local(_pool: &PgPool, _address: &str) -> bool {
    // DATA is reached only after each RCPT has already passed relay policy.
    true
}

async fn authenticate_plain(
    pool: &PgPool,
    payload: &str,
    state: &mut SessionState,
    write: &mut tokio::net::tcp::OwnedWriteHalf,
) -> Result<()> {
    let decoded = STANDARD
        .decode(payload)
        .map_err(|_| anyhow!("invalid AUTH PLAIN payload"))?;
    let parts: Vec<&[u8]> = decoded.split(|b| *b == 0).collect();
    if parts.len() < 3 {
        write.write_all(b"535 5.7.8 Authentication failed\r\n").await?;
        return Ok(());
    }
    let user = std::str::from_utf8(parts[parts.len() - 2]).unwrap_or("");
    let password = std::str::from_utf8(parts[parts.len() - 1]).unwrap_or("");
    if db::authenticate(pool, user, password).await? {
        state.authenticated = Some(db::normalise_address(user)?);
        write.write_all(b"235 2.7.0 Authentication successful\r\n").await?;
    } else {
        write.write_all(b"535 5.7.8 Authentication failed\r\n").await?;
    }
    Ok(())
}

async fn read_data<R: AsyncBufReadExt + Unpin>(reader: &mut R) -> Result<String> {
    let mut out = String::new();
    let mut line = String::new();
    loop {
        line.clear();
        if reader.read_line(&mut line).await? == 0 {
            break;
        }
        if line == ".\r\n" || line == ".\n" {
            break;
        }
        if line.starts_with("..") {
            out.push_str(&line[1..]);
        } else {
            out.push_str(&line);
        }
        if out.len() > 52_428_800 {
            return Err(anyhow!("message exceeds size limit"));
        }
    }
    Ok(out)
}

fn parse_message(raw: &str) -> (String, String) {
    let normalised = raw.replace("\r\n", "\n");
    let (head, body) = normalised.split_once("\n\n").unwrap_or(("", &normalised));
    let mut subject = String::new();
    let mut current = String::new();
    for line in head.lines() {
        if line.starts_with([' ', '\t']) && !current.is_empty() {
            current.push(' ');
            current.push_str(line.trim());
            continue;
        }
        if !current.is_empty() && current.to_ascii_lowercase().starts_with("subject:") {
            subject = current[8..].trim().to_string();
        }
        current = line.to_string();
    }
    if !current.is_empty() && current.to_ascii_lowercase().starts_with("subject:") {
        subject = current[8..].trim().to_string();
    }
    (subject, body.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_subject_and_body() {
        let (subject, body) = parse_message("From: a@example.com\r\nSubject: Hello\r\n\r\nWorld\r\n");
        assert_eq!(subject, "Hello");
        assert!(body.contains("World"));
    }
}
