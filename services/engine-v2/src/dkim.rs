use std::{fs, path::Path};

use anyhow::{Context, Result, bail};
use mail_auth::{
    common::crypto::{RsaKey, Sha256},
    dkim::DkimSigner,
};
use rustls_pki_types::{PrivateKeyDer, pem::PemObject};

use crate::db;

#[derive(Clone, Debug)]
pub struct DkimConfig {
    pub directory: String,
    pub selector: String,
    pub required: bool,
}

pub fn sign_if_configured(
    raw_message: &[u8],
    sender: &str,
    config: Option<&DkimConfig>,
) -> Result<Vec<u8>> {
    let Some(config) = config else {
        return Ok(raw_message.to_vec());
    };

    let domain = db::sender_domain(sender)?;
    let path = Path::new(&config.directory)
        .join(&domain)
        .join(format!("{}.pem", config.selector));

    if !path.is_file() {
        if config.required {
            bail!("DKIM key missing for domain {domain}: {}", path.display());
        }
        return Ok(raw_message.to_vec());
    }

    let pem = fs::read(&path)
        .with_context(|| format!("read DKIM key {}", path.display()))?;
    let private_key = PrivateKeyDer::from_pem_slice(&pem)
        .map_err(|error| anyhow::anyhow!("decode DKIM PEM key for {domain}: {error}"))?;
    let key = RsaKey::<Sha256>::from_key_der(private_key)
        .map_err(|error| anyhow::anyhow!("parse DKIM RSA key for {domain}: {error}"))?;

    let signature = DkimSigner::from_key(key)
        .domain(domain)
        .selector(config.selector.clone())
        .headers([
            "From",
            "To",
            "Cc",
            "Subject",
            "Date",
            "Message-ID",
            "MIME-Version",
            "Content-Type",
            "Content-Transfer-Encoding",
        ])
        .sign(raw_message)
        .map_err(|error| anyhow::anyhow!("DKIM signing failed: {error}"))?;

    let header = signature.to_header();
    let mut signed = Vec::with_capacity(header.len() + raw_message.len());
    signed.extend_from_slice(header.as_bytes());
    signed.extend_from_slice(raw_message);
    Ok(signed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn optional_dkim_without_key_preserves_message() {
        let config = DkimConfig {
            directory: "/definitely/missing".into(),
            selector: "tuku1".into(),
            required: false,
        };
        let raw = b"From: a@example.com\r\nTo: b@example.org\r\nSubject: x\r\n\r\nbody\r\n";
        assert_eq!(
            sign_if_configured(raw, "a@example.com", Some(&config)).unwrap(),
            raw
        );
    }
}
