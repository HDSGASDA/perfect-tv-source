use crate::error::{ApiError, Result};
use std::{net::IpAddr, path::PathBuf};
use url::Url;
#[derive(Clone)]
pub struct Settings {
    pub host: IpAddr,
    pub port: u16,
    pub public_base_url: String,
    pub admin_origin: String,
    pub production: bool,
    pub data_dir: PathBuf,
}
impl Settings {
    pub fn from_env() -> Result<Self> {
        let production = match std::env::var("APP_ENV")
            .unwrap_or_else(|_| "development".into())
            .as_str()
        {
            "production" => true,
            "development" => false,
            _ => return Err(ApiError::bad("APP_ENV must be development or production")),
        };
        let public_base_url = origin(
            &std::env::var("PUBLIC_BASE_URL").unwrap_or_else(|_| "http://localhost:3000".into()),
            production,
        )?;
        let admin_origin = origin(
            &std::env::var("ADMIN_ORIGIN").unwrap_or_else(|_| public_base_url.clone()),
            production,
        )?;
        Ok(Self {
            host: std::env::var("HOST")
                .unwrap_or_else(|_| "127.0.0.1".into())
                .parse()
                .map_err(|_| ApiError::bad("HOST must be a literal IP address"))?,
            port: std::env::var("PORT")
                .unwrap_or_else(|_| "3000".into())
                .parse()
                .map_err(|_| ApiError::bad("PORT must be a valid port"))?,
            public_base_url,
            admin_origin,
            production,
            data_dir: PathBuf::from(std::env::var("DATA_DIR").unwrap_or_else(|_| "data".into())),
        })
    }
}
pub fn origin(value: &str, https_only: bool) -> Result<String> {
    if value.len() > 2048 || value.chars().any(|c| c.is_control()) || value.contains('\\') {
        return Err(ApiError::bad("URL must be a valid origin"));
    }
    let url = Url::parse(value).map_err(|_| ApiError::bad("URL must be an absolute origin"))?;
    if !matches!(url.scheme(), "http" | "https")
        || (https_only && url.scheme() != "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(ApiError::bad(if https_only {
            "Use an HTTPS origin without credentials, path, query or fragment"
        } else {
            "Use an HTTP(S) origin without credentials, path, query or fragment"
        }));
    }
    // Reject syntactic paths that the URL parser would otherwise normalize away.
    let authority = value
        .split_once("://")
        .ok_or_else(|| ApiError::bad("URL must include ://"))?
        .1;
    if let Some((_, path)) = authority.split_once('/') {
        if !path.is_empty() {
            return Err(ApiError::bad("Origin URLs cannot contain a path"));
        }
    }
    Ok(url.origin().ascii_serialization())
}
