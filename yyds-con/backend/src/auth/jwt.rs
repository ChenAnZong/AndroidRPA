use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

use crate::config;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    pub sub: i64,       // user id
    pub username: String,
    pub role: String,
    pub exp: u64,
    pub iat: u64,
}

/// Token type: user browser token vs device long-lived token
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DeviceClaims {
    pub sub: i64,       // user id
    pub imei: String,
    pub exp: u64,
    pub iat: u64,
    pub kind: String,   // "device"
}

pub fn create_token(user_id: i64, username: &str, role: &str) -> Result<String, jsonwebtoken::errors::Error> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();

    let claims = Claims {
        sub: user_id,
        username: username.to_string(),
        role: role.to_string(),
        exp: now + config::JWT_EXPIRATION_SECS,
        iat: now,
    };

    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(config::jwt_secret().as_bytes()),
    )
}

pub fn create_device_token(user_id: i64, imei: &str) -> Result<String, jsonwebtoken::errors::Error> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();

    let claims = DeviceClaims {
        sub: user_id,
        imei: imei.to_string(),
        exp: now + config::DEVICE_TOKEN_EXPIRATION_SECS,
        iat: now,
        kind: "device".to_string(),
    };

    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(config::jwt_secret().as_bytes()),
    )
}

pub fn verify_token(token: &str) -> Result<Claims, jsonwebtoken::errors::Error> {
    let data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(config::jwt_secret().as_bytes()),
        &Validation::default(),
    )?;
    Ok(data.claims)
}

pub fn verify_device_token(token: &str) -> Result<DeviceClaims, jsonwebtoken::errors::Error> {
    let data = decode::<DeviceClaims>(
        token,
        &DecodingKey::from_secret(config::jwt_secret().as_bytes()),
        &Validation::default(),
    )?;
    if data.claims.kind != "device" {
        return Err(jsonwebtoken::errors::ErrorKind::InvalidToken.into());
    }
    Ok(data.claims)
}
