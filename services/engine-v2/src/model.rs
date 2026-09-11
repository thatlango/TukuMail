use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MailboxUpsert {
    pub display_name: String,
    pub password: String,
    pub quota_bytes: i64,
}

#[derive(Debug, Deserialize)]
pub struct PasswordChange {
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct AliasRequest {
    pub destination: String,
}

#[derive(Debug, Deserialize)]
pub struct AuthRequest {
    pub address: String,
    pub password: String,
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    pub authenticated: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SendRequest {
    pub to: Vec<String>,
    #[serde(default)]
    pub cc: Vec<String>,
    pub subject: String,
    pub text_body: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageSummary {
    pub id: String,
    pub from: String,
    pub subject: String,
    pub preview: String,
    pub received_at: DateTime<Utc>,
    pub read: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageDetail {
    pub id: String,
    pub from: String,
    pub to: Vec<String>,
    pub cc: Vec<String>,
    pub subject: String,
    pub body_text: String,
    pub received_at: DateTime<Utc>,
    pub read: bool,
}

#[derive(Debug, Clone)]
pub struct StoredMessage {
    pub uid: i64,
    pub id: Uuid,
    pub sender: String,
    pub recipients: Vec<String>,
    pub cc: Vec<String>,
    pub subject: String,
    pub body: String,
    pub received_at: DateTime<Utc>,
    pub seen: bool,
}

#[derive(Debug, Clone)]
pub struct QueueItem {
    pub id: Uuid,
    pub sender: String,
    pub recipients: Vec<String>,
    pub cc: Vec<String>,
    pub subject: String,
    pub body: String,
    pub attempts: i32,
}
