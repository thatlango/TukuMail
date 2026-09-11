use anyhow::Result;
use sqlx::PgPool;
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{TcpListener, TcpStream},
};
use tracing::{info, warn};

use crate::{db, model::StoredMessage};

pub async fn serve(addr: &str, pool: PgPool) -> Result<()> {
    let listener = TcpListener::bind(addr).await?;
    info!(%addr, "Tuku Engine v2 IMAP listener ready");
    loop {
        let (stream, peer) = listener.accept().await?;
        let pool = pool.clone();
        tokio::spawn(async move {
            if let Err(error) = handle(stream, pool).await {
                warn!(%peer, %error, "IMAP session failed");
            }
        });
    }
}

async fn handle(stream: TcpStream, pool: PgPool) -> Result<()> {
    let (read, mut write) = stream.into_split();
    let mut reader = BufReader::new(read);
    write.write_all(b"* OK Tuku Engine v2 IMAP4rev1 ready\r\n").await?;
    let mut user: Option<String> = None;
    let mut selected = false;
    let mut line = String::new();

    loop {
        line.clear();
        if reader.read_line(&mut line).await? == 0 {
            return Ok(());
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        let mut parts = trimmed.splitn(3, ' ');
        let tag = parts.next().unwrap_or("*");
        let command = parts.next().unwrap_or("").to_ascii_uppercase();
        let args = parts.next().unwrap_or("");

        match command.as_str() {
            "CAPABILITY" => {
                write.write_all(b"* CAPABILITY IMAP4rev1 UIDPLUS AUTH=PLAIN\r\n").await?;
                tagged(&mut write, tag, "OK", "CAPABILITY completed").await?;
            }
            "NOOP" => tagged(&mut write, tag, "OK", "NOOP completed").await?,
            "LOGIN" => {
                let tokens = quoted_tokens(args);
                if tokens.len() != 2 {
                    tagged(&mut write, tag, "BAD", "LOGIN expects username and password").await?;
                    continue;
                }
                if db::authenticate(&pool, &tokens[0], &tokens[1]).await? {
                    user = Some(db::normalise_address(&tokens[0])?);
                    tagged(&mut write, tag, "OK", "LOGIN completed").await?;
                } else {
                    tagged(&mut write, tag, "NO", "Authentication failed").await?;
                }
            }
            "LIST" => {
                if user.is_none() {
                    tagged(&mut write, tag, "NO", "Authenticate first").await?;
                } else {
                    write.write_all(b"* LIST (\\HasNoChildren) "/" "INBOX"\r\n").await?;
                    tagged(&mut write, tag, "OK", "LIST completed").await?;
                }
            }
            "SELECT" => {
                let Some(address) = user.as_ref() else {
                    tagged(&mut write, tag, "NO", "Authenticate first").await?;
                    continue;
                };
                if !args.trim_matches('"').eq_ignore_ascii_case("INBOX") {
                    tagged(&mut write, tag, "NO", "Only INBOX is available in v2 pilot").await?;
                    continue;
                }
                let messages = db::imap_messages(&pool, address).await?;
                let unseen = messages.iter().filter(|m| !m.seen).count();
                let uid_next = messages.last().map(|m| m.uid + 1).unwrap_or(1);
                write.write_all(format!("* {} EXISTS\r\n", messages.len()).as_bytes()).await?;
                write.write_all(format!("* {} RECENT\r\n", unseen).as_bytes()).await?;
                write.write_all(format!("* OK [UIDNEXT {}] Predicted next UID\r\n", uid_next).as_bytes()).await?;
                write.write_all(b"* FLAGS (\\Seen)\r\n").await?;
                selected = true;
                tagged(&mut write, tag, "OK", "[READ-ONLY] SELECT completed").await?;
            }
            "STATUS" => {
                let Some(address) = user.as_ref() else {
                    tagged(&mut write, tag, "NO", "Authenticate first").await?;
                    continue;
                };
                let messages = db::imap_messages(&pool, address).await?;
                let unseen = messages.iter().filter(|m| !m.seen).count();
                let uid_next = messages.last().map(|m| m.uid + 1).unwrap_or(1);
                write.write_all(
                    format!("* STATUS INBOX (MESSAGES {} UNSEEN {} UIDNEXT {})\r\n", messages.len(), unseen, uid_next)
                        .as_bytes(),
                ).await?;
                tagged(&mut write, tag, "OK", "STATUS completed").await?;
            }
            "SEARCH" => {
                if !selected {
                    tagged(&mut write, tag, "NO", "Select a mailbox first").await?;
                    continue;
                }
                let address = user.as_ref().unwrap();
                let messages = db::imap_messages(&pool, address).await?;
                let seq = (1..=messages.len()).map(|n| n.to_string()).collect::<Vec<_>>().join(" ");
                write.write_all(format!("* SEARCH {}\r\n", seq).as_bytes()).await?;
                tagged(&mut write, tag, "OK", "SEARCH completed").await?;
            }
            "FETCH" => {
                if !selected {
                    tagged(&mut write, tag, "NO", "Select a mailbox first").await?;
                    continue;
                }
                let address = user.as_ref().unwrap();
                let messages = db::imap_messages(&pool, address).await?;
                let sequence = args.split_whitespace().next().unwrap_or("1:*");
                emit_fetch(&mut write, &messages, sequence, false).await?;
                tagged(&mut write, tag, "OK", "FETCH completed").await?;
            }
            "UID" => {
                if !selected {
                    tagged(&mut write, tag, "NO", "Select a mailbox first").await?;
                    continue;
                }
                let mut p = args.splitn(3, ' ');
                let sub = p.next().unwrap_or("").to_ascii_uppercase();
                let set = p.next().unwrap_or("1:*");
                if sub != "FETCH" {
                    tagged(&mut write, tag, "BAD", "Only UID FETCH is implemented").await?;
                    continue;
                }
                let address = user.as_ref().unwrap();
                let messages = db::imap_messages(&pool, address).await?;
                emit_fetch(&mut write, &messages, set, true).await?;
                tagged(&mut write, tag, "OK", "UID FETCH completed").await?;
            }
            "LOGOUT" => {
                write.write_all(b"* BYE Tuku Engine v2 logging out\r\n").await?;
                tagged(&mut write, tag, "OK", "LOGOUT completed").await?;
                return Ok(());
            }
            _ => tagged(&mut write, tag, "BAD", "Command not implemented by v2 pilot").await?,
        }
    }
}

async fn emit_fetch(
    write: &mut tokio::net::tcp::OwnedWriteHalf,
    messages: &[StoredMessage],
    set: &str,
    by_uid: bool,
) -> Result<()> {
    for (index, message) in messages.iter().enumerate() {
        let sequence = index + 1;
        let selected = if by_uid {
            in_set(message.uid as usize, set, messages.last().map(|m| m.uid as usize).unwrap_or(1))
        } else {
            in_set(sequence, set, messages.len().max(1))
        };
        if !selected {
            continue;
        }
        let raw = render_rfc822(message);
        let flags = if message.seen { "\\Seen" } else { "" };
        write.write_all(
            format!(
                "* {} FETCH (UID {} FLAGS ({}) RFC822.SIZE {} BODY[] {{{}}}\r\n{}\r\n)\r\n",
                sequence,
                message.uid,
                flags,
                raw.as_bytes().len(),
                raw.as_bytes().len(),
                raw
            )
            .as_bytes(),
        )
        .await?;
    }
    Ok(())
}

fn render_rfc822(message: &StoredMessage) -> String {
    format!(
        "From: {}\r\nTo: {}\r\nCc: {}\r\nSubject: {}\r\nDate: {}\r\nMessage-ID: <{}@tuku-engine-v2>\r\n\r\n{}",
        message.sender,
        message.recipients.join(", "),
        message.cc.join(", "),
        message.subject.replace(['\r', '\n'], " "),
        message.received_at.to_rfc2822(),
        message.id,
        message.body
    )
}

fn in_set(value: usize, set: &str, max: usize) -> bool {
    set.split(',').any(|part| {
        let part = part.trim();
        if part == "*" {
            return value == max;
        }
        if let Some((a, b)) = part.split_once(':') {
            let start = parse_num(a, max);
            let end = parse_num(b, max);
            return value >= start.min(end) && value <= start.max(end);
        }
        value == parse_num(part, max)
    })
}

fn parse_num(value: &str, max: usize) -> usize {
    if value == "*" { max } else { value.parse().unwrap_or(0) }
}

fn quoted_tokens(input: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut escaped = false;
    for ch in input.chars() {
        if escaped {
            current.push(ch);
            escaped = false;
        } else if ch == '\\' && quoted {
            escaped = true;
        } else if ch == '"' {
            quoted = !quoted;
        } else if ch.is_whitespace() && !quoted {
            if !current.is_empty() {
                out.push(std::mem::take(&mut current));
            }
        } else {
            current.push(ch);
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}

async fn tagged(
    write: &mut tokio::net::tcp::OwnedWriteHalf,
    tag: &str,
    status: &str,
    text: &str,
) -> Result<()> {
    write.write_all(format!("{tag} {status} {text}\r\n").as_bytes()).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_login_tokens() {
        assert_eq!(
            quoted_tokens(""user@example.com" "secret phrase""),
            vec!["user@example.com", "secret phrase"]
        );
    }

    #[test]
    fn sequence_sets_work() {
        assert!(in_set(3, "1:4", 10));
        assert!(in_set(10, "*", 10));
        assert!(!in_set(7, "1:4", 10));
    }
}
