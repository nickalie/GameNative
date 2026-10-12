//! CLI errors, their process exit codes and the JSON shape printed on stdout.

use std::fmt;

use serde_json::{json, Map, Value};

pub const EXIT_OK: i32 = 0;
/// adb could not be run, failed, or the device/provider could not be reached.
pub const EXIT_ADB: i32 = 2;
/// Bad CLI usage or an unparseable/unexpected answer from the app.
pub const EXIT_PROTOCOL: i32 = 3;
/// The app answered with `ok=false` (or a CLI-side app-level condition such as `SESSION_BUSY`).
pub const EXIT_APP: i32 = 4;
/// A wait did not finish in time.
pub const EXIT_TIMEOUT: i32 = 5;

#[derive(Debug, Clone, PartialEq)]
pub enum CliError {
    Adb(String),
    Protocol(String),
    Usage(String),
    App {
        code: String,
        message: String,
        details: Option<Value>,
    },
    Timeout(String),
}

impl CliError {
    pub fn app(code: impl Into<String>, message: impl Into<String>) -> Self {
        CliError::App {
            code: code.into(),
            message: message.into(),
            details: None,
        }
    }

    pub fn exit_code(&self) -> i32 {
        match self {
            CliError::Adb(_) => EXIT_ADB,
            CliError::Protocol(_) | CliError::Usage(_) => EXIT_PROTOCOL,
            CliError::App { .. } => EXIT_APP,
            CliError::Timeout(_) => EXIT_TIMEOUT,
        }
    }

    /// Error code in the JSON output: the app's own code for app errors, a CLI code otherwise.
    pub fn code(&self) -> &str {
        match self {
            CliError::Adb(_) => "ADB_ERROR",
            CliError::Protocol(_) => "PROTOCOL_ERROR",
            CliError::Usage(_) => "USAGE_ERROR",
            CliError::App { code, .. } => code,
            CliError::Timeout(_) => "TIMEOUT",
        }
    }

    pub fn message(&self) -> &str {
        match self {
            CliError::Adb(m)
            | CliError::Protocol(m)
            | CliError::Usage(m)
            | CliError::Timeout(m) => m,
            CliError::App { message, .. } => message,
        }
    }

    /// `{"ok":false,"error":{"code":..,"message":..,"details":..?}}`, mirroring the app's error shape.
    pub fn to_json(&self) -> Value {
        let mut error = Map::new();
        error.insert("code".into(), Value::String(self.code().to_string()));
        error.insert("message".into(), Value::String(self.message().to_string()));
        if let CliError::App {
            details: Some(details),
            ..
        } = self
        {
            error.insert("details".into(), details.clone());
        }
        json!({ "ok": false, "error": Value::Object(error) })
    }
}

impl fmt::Display for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code(), self.message())
    }
}

impl std::error::Error for CliError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exit_codes() {
        assert_eq!(CliError::Adb("x".into()).exit_code(), 2);
        assert_eq!(CliError::Protocol("x".into()).exit_code(), 3);
        assert_eq!(CliError::Usage("x".into()).exit_code(), 3);
        assert_eq!(CliError::app("NO_CONTAINER", "x").exit_code(), 4);
        assert_eq!(CliError::Timeout("x".into()).exit_code(), 5);
    }

    #[test]
    fn json_shape_for_cli_errors() {
        let v = CliError::Adb("no devices".into()).to_json();
        assert_eq!(
            v,
            json!({"ok": false, "error": {"code": "ADB_ERROR", "message": "no devices"}})
        );
        assert_eq!(
            CliError::Timeout("t".into()).to_json()["error"]["code"],
            "TIMEOUT"
        );
        assert_eq!(
            CliError::Usage("u".into()).to_json()["error"]["code"],
            "USAGE_ERROR"
        );
        assert_eq!(
            CliError::Protocol("p".into()).to_json()["error"]["code"],
            "PROTOCOL_ERROR"
        );
    }

    #[test]
    fn json_shape_keeps_app_code_and_details() {
        let err = CliError::App {
            code: "BAD_ARGS".into(),
            message: "rejected".into(),
            details: Some(json!({"rejected": ["containerVariant"]})),
        };
        assert_eq!(
            err.to_json(),
            json!({"ok": false, "error": {
                "code": "BAD_ARGS",
                "message": "rejected",
                "details": {"rejected": ["containerVariant"]}
            }})
        );
        assert_eq!(err.to_string(), "BAD_ARGS: rejected");
    }
}
