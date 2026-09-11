use anyhow::{Context, Result, anyhow, bail};
use argon2::{
    Argon2, PasswordHash, PasswordHasher, PasswordVerifier,
    password_hash::{SaltString, rand_core::OsRng},
};
use chrono::{Duration, Utc};
use mailparse::{MailHeaderMap, ParsedMail, parse_mail};
use sqlx::{PgPool, Row};
use uuid::Uuid;

use crate::model::{MessageDetail, MessageSummary, QueueItem, StoredMessage};

pub async fn ensure_domain(pool: &PgPool, domain: &str) -> Result<()> {
    let name = normalise_domain(domain)?;
    sqlx::query("INSERT INTO engine_v2_domains(name) VALUES ($1) ON CONFLICT (name) DO NOTHING")
        .bind(name)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn create_mailbox(
    pool: &PgPool,
    address: &str,
    display_name: &str,
    password: &str,
    quota_bytes: i64,
) -> Result<()> {
    let address = normalise_address(address)?;
    let domain = address.split_once('@').expect("normalised address").1;
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM engine_v2_domains WHERE name=$1)")
            .bind(domain)
            .fetch_one(pool)
            .await?;
    if !exists {
        bail!("domain is not provisioned");
    }

    let password_hash = hash_password(password)?;
    sqlx::query(
        "INSERT INTO engine_v2_mailboxes(address,domain,display_name,password_hash,quota_bytes)
         VALUES ($1,$2,$3,$4,$5)",
    )
    .bind(&address)
    .bind(domain)
    .bind(display_name.trim())
    .bind(password_hash)
    .bind(quota_bytes.max(0))
    .execute(pool)
    .await
    .context("mailbox already exists or is invalid")?;
    Ok(())
}

pub async fn rotate_password(pool: &PgPool, address: &str, password: &str) -> Result<()> {
    let address = normalise_address(address)?;
    let hash = hash_password(password)?;
    let result = sqlx::query("UPDATE engine_v2_mailboxes SET password_hash=$2 WHERE address=$1")
        .bind(address)
        .bind(hash)
        .execute(pool)
        .await?;
    if result.rows_affected() != 1 {
        bail!("mailbox not found");
    }
    Ok(())
}

pub async fn suspend_mailbox(pool: &PgPool, address: &str) -> Result<()> {
    let address = normalise_address(address)?;
    let result = sqlx::query("UPDATE engine_v2_mailboxes SET suspended=TRUE WHERE address=$1")
        .bind(address)
        .execute(pool)
        .await?;
    if result.rows_affected() != 1 {
        bail!("mailbox not found");
    }
    Ok(())
}

pub async fn create_alias(pool: &PgPool, alias: &str, destination: &str) -> Result<()> {
    let alias = normalise_address(alias)?;
    let destination = normalise_address(destination)?;
    sqlx::query(
        "INSERT INTO engine_v2_aliases(alias,destination) VALUES ($1,$2)
         ON CONFLICT(alias) DO UPDATE SET destination=EXCLUDED.destination",
    )
    .bind(alias)
    .bind(destination)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn authenticate(pool: &PgPool, address: &str, password: &str) -> Result<bool> {
    let Ok(address) = normalise_address(address) else {
        return Ok(false);
    };
    let row =
        sqlx::query("SELECT password_hash,suspended FROM engine_v2_mailboxes WHERE address=$1")
            .bind(address)
            .fetch_optional(pool)
            .await?;
    let Some(row) = row else { return Ok(false) };
    if row.get::<bool, _>("suspended") {
        return Ok(false);
    }

    let stored: String = row.get("password_hash");
    let parsed =
        PasswordHash::new(&stored).map_err(|e| anyhow!("invalid stored password hash: {e}"))?;
    Ok(Argon2::default()
        .verify_password(password.as_bytes(), &parsed)
        .is_ok())
}

pub async fn inbox(pool: &PgPool, address: &str, limit: i64) -> Result<Vec<MessageSummary>> {
    let address = normalise_address(address)?;
    let rows = sqlx::query(
        "SELECT uid,sender,subject,body,received_at,seen
         FROM engine_v2_messages
         WHERE mailbox_address=$1 AND folder='INBOX'
         ORDER BY uid DESC LIMIT $2",
    )
    .bind(address)
    .bind(limit.clamp(1, 100))
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|row| {
            let body: String = row.get("body");
            MessageSummary {
                id: row.get::<i64, _>("uid").to_string(),
                from: row.get("sender"),
                subject: row.get("subject"),
                preview: preview(&body),
                received_at: row.get("received_at"),
                read: row.get("seen"),
            }
        })
        .collect())
}

pub async fn message(pool: &PgPool, address: &str, uid: i64) -> Result<MessageDetail> {
    let address = normalise_address(address)?;
    let row = sqlx::query(
        "SELECT uid,sender,recipients,cc,subject,body,received_at,seen
         FROM engine_v2_messages
         WHERE mailbox_address=$1 AND folder='INBOX' AND uid=$2",
    )
    .bind(address)
    .bind(uid)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| anyhow!("message not found"))?;

    Ok(MessageDetail {
        id: row.get::<i64, _>("uid").to_string(),
        from: row.get("sender"),
        to: row.get("recipients"),
        cc: row.get("cc"),
        subject: row.get("subject"),
        body_text: row.get("body"),
        received_at: row.get("received_at"),
        read: row.get("seen"),
    })
}

pub async fn send(
    pool: &PgPool,
    sender: &str,
    to: &[String],
    cc: &[String],
    subject: &str,
    body: &str,
) -> Result<()> {
    let sender = normalise_address(sender)?;
    let to = normalise_many(to)?;
    let cc = normalise_many(cc)?;
    let mut envelope = to.clone();
    envelope.extend(cc.iter().cloned());

    let raw = build_text_message(&sender, &to, &cc, subject, body);
    accept_submission(pool, &sender, &envelope, &raw).await?;
    Ok(())
}

pub async fn accept_submission(
    pool: &PgPool,
    envelope_from: &str,
    recipients: &[String],
    raw_message: &[u8],
) -> Result<(usize, usize)> {
    let sender = normalise_address(envelope_from)?;
    let projection = project_message(raw_message);
    let mut local_count = 0usize;
    let mut remote = Vec::new();

    for recipient in recipients {
        if let Some(local) = resolve_local(pool, recipient).await? {
            store_message(
                pool,
                &local,
                &sender,
                recipients,
                &projection.cc,
                &projection.subject,
                &projection.body,
                raw_message,
            )
            .await?;
            local_count += 1;
        } else {
            remote.push(normalise_address(recipient)?);
        }
    }

    if !remote.is_empty() {
        sqlx::query(
            "INSERT INTO engine_v2_outgoing_queue
             (id,sender,recipients,cc,subject,body,raw_message)
             VALUES ($1,$2,$3,$4,$5,$6,$7)",
        )
        .bind(Uuid::new_v4())
        .bind(sender)
        .bind(&remote)
        .bind(&projection.cc)
        .bind(&projection.subject)
        .bind(&projection.body)
        .bind(raw_message)
        .execute(pool)
        .await?;
    }

    Ok((local_count, remote.len()))
}

pub async fn recipient_is_local(pool: &PgPool, address: &str) -> Result<bool> {
    Ok(resolve_local(pool, address).await?.is_some())
}

async fn resolve_local(pool: &PgPool, raw: &str) -> Result<Option<String>> {
    let address = normalise_address(raw)?;
    let direct: Option<String> = sqlx::query_scalar(
        "SELECT address FROM engine_v2_mailboxes WHERE address=$1 AND suspended=FALSE",
    )
    .bind(&address)
    .fetch_optional(pool)
    .await?;

    if direct.is_some() {
        return Ok(direct);
    }

    let alias: Option<String> = sqlx::query_scalar(
        "SELECT m.address
         FROM engine_v2_aliases a
         JOIN engine_v2_mailboxes m ON m.address=a.destination
         WHERE a.alias=$1 AND m.suspended=FALSE",
    )
    .bind(address)
    .fetch_optional(pool)
    .await?;
    Ok(alias)
}

async fn store_message(
    pool: &PgPool,
    mailbox: &str,
    sender: &str,
    recipients: &[String],
    cc: &[String],
    subject: &str,
    body: &str,
    raw_message: &[u8],
) -> Result<()> {
    let quota: i64 = sqlx::query_scalar(
        "SELECT quota_bytes FROM engine_v2_mailboxes WHERE address=$1 AND suspended=FALSE",
    )
    .bind(mailbox)
    .fetch_one(pool)
    .await?;
    let used: i64 = sqlx::query_scalar(
        "SELECT COALESCE(SUM(size_bytes),0)::BIGINT
         FROM engine_v2_messages WHERE mailbox_address=$1",
    )
    .bind(mailbox)
    .fetch_one(pool)
    .await?;

    let size = raw_message.len() as i64;
    if quota > 0 && used.saturating_add(size) > quota {
        bail!("mailbox quota exceeded");
    }

    sqlx::query(
        "INSERT INTO engine_v2_messages
         (id,mailbox_address,sender,recipients,cc,subject,body,size_bytes,raw_message)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)",
    )
    .bind(Uuid::new_v4())
    .bind(mailbox)
    .bind(sender)
    .bind(recipients)
    .bind(cc)
    .bind(subject)
    .bind(body)
    .bind(size)
    .bind(raw_message)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn imap_messages(pool: &PgPool, address: &str) -> Result<Vec<StoredMessage>> {
    let address = normalise_address(address)?;
    let rows = sqlx::query(
        "SELECT id,uid,sender,recipients,cc,subject,body,raw_message,received_at,seen
         FROM engine_v2_messages
         WHERE mailbox_address=$1 AND folder='INBOX'
         ORDER BY uid ASC",
    )
    .bind(address)
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|r| StoredMessage {
            id: r.get("id"),
            uid: r.get("uid"),
            sender: r.get("sender"),
            recipients: r.get("recipients"),
            cc: r.get("cc"),
            subject: r.get("subject"),
            body: r.get("body"),
            raw_message: r.get("raw_message"),
            received_at: r.get("received_at"),
            seen: r.get("seen"),
        })
        .collect())
}

pub async fn queue_due(pool: &PgPool, limit: i64) -> Result<Vec<QueueItem>> {
    let rows = sqlx::query(
        "SELECT id,sender,recipients,cc,subject,body,raw_message,attempts
         FROM engine_v2_outgoing_queue
         WHERE state='QUEUED' AND next_attempt_at <= now()
         ORDER BY created_at ASC LIMIT $1",
    )
    .bind(limit.clamp(1, 100))
    .fetch_all(pool)
    .await?;

    Ok(rows
        .into_iter()
        .map(|r| QueueItem {
            id: r.get("id"),
            sender: r.get("sender"),
            recipients: r.get("recipients"),
            cc: r.get("cc"),
            subject: r.get("subject"),
            body: r.get("body"),
            raw_message: r.get("raw_message"),
            attempts: r.get("attempts"),
        })
        .collect())
}

pub async fn queue_delivered(pool: &PgPool, id: Uuid) -> Result<()> {
    sqlx::query(
        "UPDATE engine_v2_outgoing_queue
         SET state='DELIVERED', delivered_at=now(), last_error=NULL WHERE id=$1",
    )
    .bind(id)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn queue_failed(pool: &PgPool, item: &QueueItem, error: &str) -> Result<()> {
    let attempts = item.attempts.saturating_add(1);
    let state = if attempts >= 8 { "FAILED" } else { "QUEUED" };
    let delay_minutes = 2_i64
        .saturating_pow(attempts.min(10) as u32)
        .min(24 * 60);
    let next = Utc::now() + Duration::minutes(delay_minutes);

    sqlx::query(
        "UPDATE engine_v2_outgoing_queue
         SET attempts=$2,state=$3,next_attempt_at=$4,last_error=$5
         WHERE id=$1",
    )
    .bind(item.id)
    .bind(attempts)
    .bind(state)
    .bind(next)
    .bind(truncate(error, 2000))
    .execute(pool)
    .await?;
    Ok(())
}

fn hash_password(password: &str) -> Result<String> {
    if password.len() < 10 {
        bail!("password must be at least 10 characters");
    }

    let salt = SaltString::generate(&mut OsRng);
    Ok(Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map_err(|e| anyhow!("password hashing failed: {e}"))?
        .to_string())
}

pub fn normalise_address(raw: &str) -> Result<String> {
    let value = raw
        .trim()
        .trim_matches('<')
        .trim_matches('>')
        .to_ascii_lowercase();
    let Some((local, domain)) = value.split_once('@') else {
        bail!("invalid email address");
    };

    if local.is_empty()
        || local.len() > 64
        || !local
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "._+-".contains(c))
    {
        bail!("invalid local part");
    }

    let domain = normalise_domain(domain)?;
    Ok(format!("{local}@{domain}"))
}

pub fn normalise_domain(raw: &str) -> Result<String> {
    let domain = raw.trim().trim_end_matches('.').to_ascii_lowercase();
    if domain.is_empty()
        || domain.len() > 253
        || !domain.contains('.')
        || !domain
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '.')
    {
        bail!("invalid domain");
    }
    Ok(domain)
}

pub fn sender_domain(address: &str) -> Result<String> {
    let address = normalise_address(address)?;
    Ok(address
        .split_once('@')
        .expect("normalised address")
        .1
        .to_string())
}

pub fn fallback_raw(item: &QueueItem) -> Vec<u8> {
    build_text_message(
        &item.sender,
        &item.recipients,
        &item.cc,
        &item.subject,
        &item.body,
    )
}

fn normalise_many(values: &[String]) -> Result<Vec<String>> {
    values.iter().map(|value| normalise_address(value)).collect()
}

fn build_text_message(
    sender: &str,
    to: &[String],
    cc: &[String],
    subject: &str,
    body: &str,
) -> Vec<u8> {
    let subject = sanitize_header(subject);
    let body = body.replace("\r\n", "\n").replace('\r', "\n").replace('\n', "\r\n");
    let id = Uuid::new_v4();

    format!(
        "From: {sender}\r\nTo: {}\r\nCc: {}\r\nSubject: {subject}\r\nDate: {}\r\nMessage-ID: <{id}@tuku-engine-v2>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n{body}\r\n",
        to.join(", "),
        cc.join(", "),
        Utc::now().to_rfc2822(),
    )
    .into_bytes()
}

struct Projection {
    subject: String,
    body: String,
    cc: Vec<String>,
}

fn project_message(raw: &[u8]) -> Projection {
    match parse_mail(raw) {
        Ok(parsed) => {
            let subject = parsed
                .headers
                .get_first_value("Subject")
                .unwrap_or_default();
            let body = text_body(&parsed).unwrap_or_default();
            Projection {
                subject,
                body,
                cc: Vec::new(),
            }
        }
        Err(_) => Projection {
            subject: String::new(),
            body: String::from_utf8_lossy(raw).into_owned(),
            cc: Vec::new(),
        },
    }
}

fn text_body(parsed: &ParsedMail<'_>) -> Option<String> {
    if parsed.subparts.is_empty() {
        if parsed.ctype.mimetype.eq_ignore_ascii_case("text/plain") {
            return parsed.get_body().ok();
        }
        if parsed.ctype.mimetype.eq_ignore_ascii_case("text/html") {
            return parsed.get_body().ok().map(strip_html);
        }
        return None;
    }

    parsed
        .subparts
        .iter()
        .find_map(|part| {
            if part.ctype.mimetype.eq_ignore_ascii_case("text/plain") {
                text_body(part)
            } else {
                None
            }
        })
        .or_else(|| parsed.subparts.iter().find_map(text_body))
}

fn strip_html(value: String) -> String {
    let mut out = String::with_capacity(value.len());
    let mut in_tag = false;
    for ch in value.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                out.push(' ');
            }
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }
    out
}

fn preview(body: &str) -> String {
    let compact = body.split_whitespace().collect::<Vec<_>>().join(" ");
    if compact.chars().count() <= 180 {
        compact
    } else {
        compact.chars().take(180).collect::<String>() + "…"
    }
}

fn sanitize_header(value: &str) -> String {
    value.replace(['\r', '\n'], " ")
}

fn truncate(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalises_addresses() {
        assert_eq!(
            normalise_address(" User.Name+tag@Example.COM ").unwrap(),
            "user.name+tag@example.com"
        );
        assert!(normalise_address("bad address").is_err());
    }

    #[test]
    fn preview_is_bounded() {
        let p = preview(&"x ".repeat(500));
        assert!(p.chars().count() <= 181);
    }

    #[test]
    fn mime_projection_extracts_text_plain() {
        let raw = b"From: a@example.com\r\nTo: b@example.com\r\nSubject: MIME test\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=x\r\n\r\n--x\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nhello body\r\n--x\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=a.bin\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n--x--\r\n";
        let projection = project_message(raw);
        assert_eq!(projection.subject, "MIME test");
        assert!(projection.body.contains("hello body"));
    }
}
