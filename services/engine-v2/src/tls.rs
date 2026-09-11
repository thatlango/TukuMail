use std::{
    fs::File,
    io::BufReader,
    path::Path,
    sync::Arc,
};

use anyhow::{Context, Result, bail};
use tokio_rustls::{TlsAcceptor, rustls::ServerConfig};

pub fn load_acceptor(cert_path: &str, key_path: &str) -> Result<TlsAcceptor> {
    if !Path::new(cert_path).is_file() {
        bail!("TLS certificate not found: {cert_path}");
    }
    if !Path::new(key_path).is_file() {
        bail!("TLS private key not found: {key_path}");
    }

    let mut cert_reader = BufReader::new(File::open(cert_path).context("open TLS certificate")?);
    let certs = rustls_pemfile::certs(&mut cert_reader)
        .collect::<std::result::Result<Vec<_>, _>>()
        .context("parse TLS certificate chain")?;

    if certs.is_empty() {
        bail!("TLS certificate chain is empty");
    }

    let mut key_reader = BufReader::new(File::open(key_path).context("open TLS private key")?);
    let key = rustls_pemfile::private_key(&mut key_reader)
        .context("parse TLS private key")?
        .context("TLS private key file contains no supported key")?;

    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .context("build rustls server configuration")?;

    Ok(TlsAcceptor::from(Arc::new(config)))
}
