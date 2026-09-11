mod db;
mod dkim;
mod imap;
mod model;
mod smtp;
mod tls;

use std::{env, net::SocketAddr, sync::Arc, time::Duration};

use anyhow::{Context, Result, bail};
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use lettre::{
    Address, AsyncSmtpTransport, AsyncTransport, Tokio1Executor,
    address::Envelope,
    transport::smtp::authentication::Credentials,
};
use serde::Deserialize;
use sqlx::{PgPool, postgres::PgPoolOptions};
use tokio::time::sleep;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

use crate::{
    dkim::DkimConfig,
    model::{
        AliasRequest, AuthRequest, AuthResponse, MailboxUpsert, PasswordChange, RawSendRequest,
        SendRequest,
    },
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
    tls: Option<TlsConfig>,
    dkim: Option<DkimConfig>,
}

#[derive(Clone)]
struct RelayConfig {
    host: String,
    port: u16,
    username: Option<String>,
    password: Option<String>,
}

#[derive(Clone)]
struct TlsConfig {
    cert_path: String,
    key_path: String,
    smtp_addr: String,
    imap_addr: String,
}

impl Config {
    fn from_env() -> Result<Self> {
        let database_url = required("ENGINE_DATABASE_URL")?;
        let admin_key = required("ENGINE_ADMIN_KEY")?;
        if admin_key.len() < 24 {
            bail!("ENGINE_ADMIN_KEY must be at least 24 characters");
        }

        let relay = env::var("ENGINE_RELAY_HOST")
            .ok()
            .filter(|value| !value.trim().is_empty())
            .map(|host| RelayConfig {
                host,
                port: env::var("ENGINE_RELAY_PORT")
                    .ok()
                    .and_then(|value| value.parse().ok())
                    .unwrap_or(587),
                username: env::var("ENGINE_RELAY_USERNAME")
                    .ok()
                    .filter(|value| !value.is_empty()),
                password: env::var("ENGINE_RELAY_PASSWORD")
                    .ok()
                    .filter(|value| !value.is_empty()),
            });

        let cert = env::var("ENGINE_TLS_CERT").ok().filter(|v| !v.trim().is_empty());
        let key = env::var("ENGINE_TLS_KEY").ok().filter(|v| !v.trim().is_empty());
        let tls = match (cert, key) {
            (Some(cert_path), Some(key_path)) => Some(TlsConfig {
                cert_path,
                key_path,
                smtp_addr: env::var("ENGINE_SMTPS_ADDR")
                    .unwrap_or_else(|_| "0.0.0.0:2465".into()),
                imap_addr: env::var("ENGINE_IMAPS_ADDR")
                    .unwrap_or_else(|_| "0.0.0.0:2993".into()),
            }),
            (None, None) => None,
            _ => bail!("ENGINE_TLS_CERT and ENGINE_TLS_KEY must be configured together"),
        };

        let dkim = env::var("ENGINE_DKIM_DIR")
            .ok()
            .filter(|value| !value.trim().is_empty())
            .map(|directory| DkimConfig {
                directory,
                selector: env::var("ENGINE_DKIM_SELECTOR").unwrap_or_else(|_| "tuku1".into()),
                required: bool_env("ENGINE_DKIM_REQUIRED", false),
            });

        Ok(Self {
            database_url,
            http_addr: env::var("ENGINE_HTTP_ADDR")
                .unwrap_or_else(|_| "0.0.0.0:8088".into()),
            smtp_addr: env::var("ENGINE_SMTP_ADDR")
                .unwrap_or_else(|_| "0.0.0.0:2525".into()),
            imap_addr: env::var("ENGINE_IMAP_ADDR")
                .unwrap_or_else(|_| "0.0.0.0:2143".into()),
            admin_key,
            relay,
            tls,
            dkim,
        })
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| "tuku_engine_v2=info".into()),
        )
        .init();

    let config = Config::from_env()?;
    let pool = PgPoolOptions::new()
        .max_connections(20)
        .connect(&config.database_url)
        .await
        .context("connect Engine v2 database")?;

    sqlx::migrate!("./migrations").run(&pool).await?;
    info!("Tuku Engine v2 schema ready");

    let state = AppState {
        pool: pool.clone(),
        admin_key: Arc::from(config.admin_key.clone()),
    };
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
        .route("/v1/mailboxes/{address}/send-raw", post(send_raw))
        .with_state(state);

    let http_addr: SocketAddr = config
        .http_addr
        .parse()
        .context("invalid ENGINE_HTTP_ADDR")?;
    let http = tokio::net::TcpListener::bind(http_addr).await?;
    info!(%http_addr, "Tuku Engine v2 management API ready");

    {
        let pool = pool.clone();
        let addr = config.smtp_addr.clone();
        tokio::spawn(async move {
            if let Err(error) = smtp::serve(&addr, pool).await {
                error!(%error, "SMTP listener stopped");
            }
        });
    }

    {
        let pool = pool.clone();
        let addr = config.imap_addr.clone();
        tokio::spawn(async move {
            if let Err(error) = imap::serve(&addr, pool).await {
                error!(%error, "IMAP listener stopped");
            }
        });
    }

    if let Some(tls_config) = config.tls.clone() {
        let acceptor = tls::load_acceptor(&tls_config.cert_path, &tls_config.key_path)?;

        {
            let pool = pool.clone();
            let addr = tls_config.smtp_addr.clone();
            let acceptor = acceptor.clone();
            tokio::spawn(async move {
                if let Err(error) = smtp::serve_tls(&addr, pool, acceptor).await {
                    error!(%error, "SMTPS listener stopped");
                }
            });
        }

        {
            let pool = pool.clone();
            let addr = tls_config.imap_addr.clone();
            tokio::spawn(async move {
                if let Err(error) = imap::serve_tls(&addr, pool, acceptor).await {
                    error!(%error, "IMAPS listener stopped");
                }
            });
        }
    } else {
        warn!("TLS certificate not configured; implicit TLS listeners are disabled");
    }

    if let Some(relay) = config.relay {
        let queue_pool = pool.clone();
        let dkim = config.dkim.clone();
        tokio::spawn(async move {
            queue_worker(queue_pool, relay, dkim).await;
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
        "version": "2.1",
        "status": "ok",
        "storage": "raw-mime"
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
    )
    .await?;
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
    Ok(Json(
        db::inbox(&state.pool, &address, query.limit.unwrap_or(50)).await?,
    ))
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
    )
    .await?;
    Ok(StatusCode::ACCEPTED)
}

async fn send_raw(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(address): Path<String>,
    Json(request): Json<RawSendRequest>,
) -> EngineResult<StatusCode> {
    authorise(&state, &headers)?;
    if request.recipients.is_empty() {
        return Err(EngineError::bad_request("at least one recipient is required"));
    }

    let raw = STANDARD
        .decode(request.raw_base64)
        .map_err(|_| EngineError::bad_request("rawBase64 is not valid base64"))?;
    if raw.len() > 52_428_800 {
        return Err(EngineError::bad_request("message exceeds 50 MiB limit"));
    }

    db::accept_submission(&state.pool, &address, &request.recipients, &raw).await?;
    Ok(StatusCode::ACCEPTED)
}

async fn queue_worker(pool: PgPool, relay: RelayConfig, dkim: Option<DkimConfig>) {
    info!(host=%relay.host, port=relay.port, "Engine v2 outbound queue worker enabled");
    loop {
        match db::queue_due(&pool, 20).await {
            Ok(items) => {
                for item in items {
                    match relay_message(&relay, dkim.as_ref(), &item).await {
                        Ok(()) => {
                            if let Err(error) = db::queue_delivered(&pool, item.id).await {
                                error!(id=%item.id, %error, "could not mark queued message delivered");
                            }
                        }
                        Err(error) => {
                            warn!(id=%item.id, %error, "outbound delivery deferred");
                            if let Err(mark_error) =
                                db::queue_failed(&pool, &item, &error.to_string()).await
                            {
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

async fn relay_message(
    relay: &RelayConfig,
    dkim_config: Option<&DkimConfig>,
    item: &model::QueueItem,
) -> Result<()> {
    let sender: Address = item.sender.parse()?;
    let recipients = item
        .recipients
        .iter()
        .map(|address| address.parse::<Address>())
        .collect::<std::result::Result<Vec<_>, _>>()?;
    let envelope = Envelope::new(Some(sender), recipients)?;

    let raw = item
        .raw_message
        .clone()
        .unwrap_or_else(|| db::fallback_raw(item));
    let signed = dkim::sign_if_configured(&raw, &item.sender, dkim_config)?;

    let mut transport =
        AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&relay.host)?.port(relay.port);
    if let (Some(username), Some(password)) = (&relay.username, &relay.password) {
        transport = transport.credentials(Credentials::new(username.clone(), password.clone()));
    }

    transport.build().send_raw(&envelope, &signed).await?;
    Ok(())
}

fn authorise(state: &AppState, headers: &HeaderMap) -> EngineResult<()> {
    let supplied = headers
        .get("x-tuku-engine-key")
        .and_then(|value| value.to_str().ok())
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

fn bool_env(name: &str, default: bool) -> bool {
    env::var(name)
        .ok()
        .and_then(|value| match value.trim().to_ascii_lowercase().as_str() {
            "1" | "true" | "yes" | "on" => Some(true),
            "0" | "false" | "no" | "off" => Some(false),
            _ => None,
        })
        .unwrap_or(default)
}

type EngineResult<T> = std::result::Result<T, EngineError>;

struct EngineError {
    status: StatusCode,
    message: String,
}

impl EngineError {
    fn unauthorised() -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            message: "invalid engine credential".into(),
        }
    }

    fn bad_request(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            message: message.into(),
        }
    }
}

impl<E> From<E> for EngineError
where
    E: Into<anyhow::Error>,
{
    fn from(value: E) -> Self {
        let error: anyhow::Error = value.into();
        Self {
            status: StatusCode::BAD_REQUEST,
            message: error.to_string(),
        }
    }
}

impl IntoResponse for EngineError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(serde_json::json!({ "error": self.message })),
        )
            .into_response()
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

    #[test]
    fn boolean_environment_parser_has_safe_default() {
        assert!(!bool_env("THIS_ENV_VAR_SHOULD_NOT_EXIST_TUKU", false));
    }
}
