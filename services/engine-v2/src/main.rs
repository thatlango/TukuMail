mod db;
mod imap;
mod model;
mod smtp;

use std::{env, net::SocketAddr, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use lettre::{
    AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor,
    transport::smtp::authentication::Credentials,
};
use serde::Deserialize;
use sqlx::{PgPool, postgres::PgPoolOptions};
use tokio::time::sleep;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

use crate::model::{
    AliasRequest, AuthRequest, AuthResponse, MailboxUpsert, PasswordChange, SendRequest,
};

#[derive(Clone)]
struct AppState {
    pool: PgPool,
    admin_key: Arc<str>,
}

#[derive(Clone)]
struct Config {
    database_url: String,
    http_addr: String,
    smtp_addr: String,
    imap_addr: String,
    admin_key: String,
    relay: Option<RelayConfig>,
}

#[derive(Clone)]
struct RelayConfig {
    host: String,
    port: u16,
    username: Option<String>,
    password: Option<String>,
}

impl Config {
    fn from_env() -> Result<Self> {
        let database_url = required("ENGINE_DATABASE_URL")?;
        let admin_key = required("ENGINE_ADMIN_KEY")?;
        if admin_key.len() < 24 {
            anyhow::bail!("ENGINE_ADMIN_KEY must be at least 24 characters");
        }
        let relay = env::var("ENGINE_RELAY_HOST").ok().filter(|v| !v.trim().is_empty()).map(|host| {
            RelayConfig {
                host,
                port: env::var("ENGINE_RELAY_PORT").ok().and_then(|v| v.parse().ok()).unwrap_or(587),
                username: env::var("ENGINE_RELAY_USERNAME").ok().filter(|v| !v.is_empty()),
                password: env::var("ENGINE_RELAY_PASSWORD").ok().filter(|v| !v.is_empty()),
            }
        });
        Ok(Self {
            database_url,
            http_addr: env::var("ENGINE_HTTP_ADDR").unwrap_or_else(|_| "0.0.0.0:8088".into()),
            smtp_addr: env::var("ENGINE_SMTP_ADDR").unwrap_or_else(|_| "0.0.0.0:2525".into()),
            imap_addr: env::var("ENGINE_IMAP_ADDR").unwrap_or_else(|_| "0.0.0.0:2143".into()),
            admin_key,
            relay,
        })
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "tuku_engine_v2=info".into()))
        .init();

    let config = Config::from_env()?;
    let pool = PgPoolOptions::new()
        .max_connections(20)
        .connect(&config.database_url)
        .await
        .context("connect Engine v2 database")?;

    sqlx::migrate!("./migrations").run(&pool).await?;
    info!("Tuku Engine v2 schema ready");

    let state = AppState { pool: pool.clone(), admin_key: Arc::from(config.admin_key.clone()) };
    let app = Router::new()
        .route("/health", get(health))
        .route("/v1/domains/{domain}", put(domain_put))
        .route("/v1/mailboxes/{address}", put(mailbox_put))
        .route("/v1/mailboxes/{address}/password", put(password_put))
        .route("/v1/mailboxes/{address}/suspend", post(mailbox_suspend))
        .route("/v1/aliases/{alias}", put(alias_put))
        .route("/v1/auth", post(authenticate))
        .route("/v1/mailboxes/{address}/messages", get(inbox))
        .route("/v1/mailboxes/{address}/messages/{uid}", get(message))
        .route("/v1/mailboxes/{address}/send", post(send))
        .with_state(state);

    let http_addr: SocketAddr = config.http_addr.parse().context("invalid ENGINE_HTTP_ADDR")?;
    let http = tokio::net::TcpListener::bind(http_addr).await?;
    info!(%http_addr, "Tuku Engine v2 management API ready");

    let smtp_pool = pool.clone();
    let smtp_addr = config.smtp_addr.clone();
    tokio::spawn(async move {
        if let Err(error) = smtp::serve(&smtp_addr, smtp_pool).await {
            error!(%error, "SMTP listener stopped");
        }
    });

    let imap_pool = pool.clone();
    let imap_addr = config.imap_addr.clone();
    tokio::spawn(async move {
        if let Err(error) = imap::serve(&imap_addr, imap_pool).await {
            error!(%error, "IMAP listener stopped");
        }
    });

    if let Some(relay) = config.relay {
        let queue_pool = pool.clone();
        tokio::spawn(async move {
            queue_worker(queue_pool, relay).await;
        });
    } else {
        warn!("No ENGINE_RELAY_HOST configured; remote messages remain durably queued");
    }

    axum::serve(http, app).await?;
    Ok(())
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "product": "Tuku Engine",
        "version": "2",
        "status": "ok"
    }))
}

async fn domain_put(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(domain): Path<String>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    db::ensure_domain(&state.pool, &domain).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn mailbox_put(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(address): Path<String>,
    Json(request): Json<MailboxUpsert>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    db::create_mailbox(
        &state.pool,
        &address,
        &request.display_name,
        &request.password,
        request.quota_bytes,
    ).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn password_put(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(address): Path<String>,
    Json(request): Json<PasswordChange>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    db::rotate_password(&state.pool, &address, &request.password).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn mailbox_suspend(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(address): Path<String>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    db::suspend_mailbox(&state.pool, &address).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn alias_put(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(alias): Path<String>,
    Json(request): Json<AliasRequest>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    db::create_alias(&state.pool, &alias, &request.destination).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn authenticate(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<AuthRequest>,
) -> EngineResult<Json<AuthResponse>> {
    authorise(&state, &headers)?;
    let authenticated = db::authenticate(&state.pool, &request.address, &request.password).await?;
    Ok(Json(AuthResponse { authenticated }))
}

#[derive(Deserialize)]
struct LimitQuery {
    limit: Option<i64>,
}

async fn inbox(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(address): Path<String>,
    Query(query): Query<LimitQuery>,
) -> EngineResult<Json<Vec<model::MessageSummary>>> {
    authorise(&state, &headers)?;
    Ok(Json(db::inbox(&state.pool, &address, query.limit.unwrap_or(50)).await?))
}

async fn message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((address, uid)): Path<(String, i64)>,
) -> EngineResult<Json<model::MessageDetail>> {
    authorise(&state, &headers)?;
    Ok(Json(db::message(&state.pool, &address, uid).await?))
}

async fn send(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(address): Path<String>,
    Json(request): Json<SendRequest>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    db::send(
        &state.pool,
        &address,
        &request.to,
        &request.cc,
        &request.subject,
        &request.text_body,
    ).await?;
    Ok(StatusCode::ACCEPTED)
}

async fn queue_worker(pool: PgPool, relay: RelayConfig) {
    info!(host=%relay.host, port=relay.port, "Engine v2 outbound queue worker enabled");
    loop {
        match db::queue_due(&pool, 20).await {
            Ok(items) => {
                for item in items {
                    match relay_message(&relay, &item).await {
                        Ok(()) => {
                            if let Err(error) = db::queue_delivered(&pool, item.id).await {
                                error!(id=%item.id, %error, "could not mark queued message delivered");
                            }
                        }
                        Err(error) => {
                            warn!(id=%item.id, %error, "outbound delivery deferred");
                            if let Err(mark_error) = db::queue_failed(&pool, &item, &error.to_string()).await {
                                error!(id=%item.id, %mark_error, "could not update queue retry state");
                            }
                        }
                    }
                }
            }
            Err(error) => error!(%error, "queue scan failed"),
        }
        sleep(Duration::from_secs(5)).await;
    }
}

async fn relay_message(relay: &RelayConfig, item: &model::QueueItem) -> Result<()> {
    let mut builder = Message::builder().from(item.sender.parse()?);
    for address in &item.recipients {
        builder = builder.to(address.parse()?);
    }
    for address in &item.cc {
        builder = builder.cc(address.parse()?);
    }
    let message = builder.subject(&item.subject).body(item.body.clone())?;

    let mut transport = AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&relay.host)?
        .port(relay.port);
    if let (Some(username), Some(password)) = (&relay.username, &relay.password) {
        transport = transport.credentials(Credentials::new(username.clone(), password.clone()));
    }
    transport.build().send(message).await?;
    Ok(())
}

fn authorise(state: &AppState, headers: &HeaderMap) -> EngineResult<()> {
    let supplied = headers
        .get("x-tuku-engine-key")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if constant_time_eq(supplied.as_bytes(), state.admin_key.as_bytes()) {
        Ok(())
    } else {
        Err(EngineError::unauthorised())
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b) {
        diff |= x ^ y;
    }
    diff == 0
}

fn required(name: &str) -> Result<String> {
    env::var(name).with_context(|| format!("{name} is required"))
}

type EngineResult<T> = std::result::Result<T, EngineError>;

struct EngineError {
    status: StatusCode,
    message: String,
}

impl EngineError {
    fn unauthorised() -> Self {
        Self { status: StatusCode::UNAUTHORIZED, message: "invalid engine credential".into() }
    }
}

impl<E> From<E> for EngineError
where
    E: Into<anyhow::Error>,
{
    fn from(value: E) -> Self {
        let error: anyhow::Error = value.into();
        Self { status: StatusCode::BAD_REQUEST, message: error.to_string() }
    }
}

impl IntoResponse for EngineError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(serde_json::json!({ "error": self.message })),
        ).into_response()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constant_time_comparison_handles_length_and_content() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
    }
}
