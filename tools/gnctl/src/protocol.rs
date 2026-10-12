//! Wire format of the GameNative agent API.
//!
//! Request: `adb shell content call --uri content://<package>.agent --method command
//! --arg <base64url(JSON)>` with `{"api_version":1,"command":"...","args":{...}}`.
//! Response: the `content` tool prints `Result: Bundle[{json=<JSON>}]`, where the JSON is
//! `{"api_version":1,"ok":true,"data":...}` or
//! `{"api_version":1,"ok":false,"error":{"code":"...","message":"...","details":...}}`.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::adb::{truncate, AdbOutput, AdbRunner};
use crate::error::CliError;

pub const API_VERSION: i64 = 1;
pub const DEFAULT_PACKAGE: &str = "app.gamenative";
pub const METHOD_COMMAND: &str = "command";
pub const BUNDLE_KEY: &str = "json";
/// Text the `content` tool prints when the provider call itself fails.
const PROVIDER_ERROR: &str = "Error while accessing provider";

#[derive(Debug, Serialize)]
pub struct Request<'a> {
    pub api_version: i64,
    pub command: &'a str,
    pub args: &'a Map<String, Value>,
}

#[derive(Debug, Deserialize)]
struct RawResponse {
    api_version: Option<Value>,
    ok: Option<Value>,
    #[serde(default)]
    data: Option<Value>,
    #[serde(default)]
    error: Option<Value>,
}

/// Checks an Android package name, which is also interpolated into the device shell command.
pub fn validate_package(package: &str) -> Result<(), CliError> {
    let segments: Vec<&str> = package.split('.').collect();
    let valid = segments.len() >= 2
        && segments.iter().all(|seg| {
            let mut chars = seg.chars();
            matches!(chars.next(), Some(c) if c.is_ascii_alphabetic())
                && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
        });
    if valid {
        Ok(())
    } else {
        Err(CliError::Usage(format!("invalid package name '{package}'")))
    }
}

/// Provider authority for a package: `<package>.agent` (follows `.gold` and other suffixes).
pub fn authority(package: &str) -> Result<String, CliError> {
    validate_package(package)?;
    Ok(format!("{package}.agent"))
}

/// base64url (no padding) of the request JSON; the alphabet is safe inside a device shell command.
pub fn encode_request(command: &str, args: &Map<String, Value>) -> String {
    let request = Request {
        api_version: API_VERSION,
        command,
        args,
    };
    let json = serde_json::to_vec(&request).expect("request serializes");
    URL_SAFE_NO_PAD.encode(json)
}

/// adb arguments for one `content call`.
pub fn content_call_args(package: &str, encoded: &str) -> Result<Vec<String>, CliError> {
    let uri = format!("content://{}", authority(package)?);
    Ok([
        "shell",
        "content",
        "call",
        "--uri",
        &uri,
        "--method",
        METHOD_COMMAND,
        "--arg",
        encoded,
    ]
    .iter()
    .map(|s| s.to_string())
    .collect())
}

/// Extracts the response JSON from `content call` output, classifying failures:
/// adb/provider failures → adb error (2), anything unrecognized → protocol error (3).
pub fn parse_content_output(out: &AdbOutput) -> Result<String, CliError> {
    let stdout = out.stdout_text();
    let stderr = out.stderr_text();
    if !out.success() {
        let status = out
            .status
            .map_or_else(|| "signal".to_string(), |c| c.to_string());
        let detail = if stderr.trim().is_empty() {
            &stdout
        } else {
            &stderr
        };
        return Err(CliError::Adb(format!(
            "adb shell content call failed (exit {status}): {}",
            truncate(detail.trim(), 500)
        )));
    }
    // The content tool reports provider failures (missing provider, SecurityException) as text
    // and then prints no `Result:` line. The response JSON after `Result: ` is app data that may
    // contain any text, so on stdout only the part before the marker is checked.
    let result_idx = stdout.find("Result: ");
    let stdout_head = &stdout[..result_idx.unwrap_or(stdout.len())];
    for text in [stderr.as_str(), stdout_head] {
        if let Some(idx) = text.find(PROVIDER_ERROR) {
            return Err(CliError::Adb(truncate(text[idx..].trim(), 500)));
        }
    }
    let Some(idx) = result_idx else {
        return Err(unexpected_output(&stdout));
    };
    let rest = stdout[idx + "Result: ".len()..].trim();
    if rest == "null" {
        return Err(CliError::Protocol(
            "provider returned no result (is this a GameNative build with the agent API?)".into(),
        ));
    }
    let prefix = format!("Bundle[{{{BUNDLE_KEY}=");
    rest.strip_prefix(prefix.as_str())
        .and_then(|r| r.strip_suffix("}]"))
        .map(str::to_string)
        .ok_or_else(|| unexpected_output(&stdout))
}

fn unexpected_output(stdout: &str) -> CliError {
    CliError::Protocol(format!(
        "unexpected content call output: {}",
        truncate(stdout.trim(), 500)
    ))
}

/// Parses the response JSON: `ok=true` → `data` (null when absent), `ok=false` → app error (4).
pub fn parse_response(json: &str) -> Result<Value, CliError> {
    let raw: RawResponse = serde_json::from_str(json)
        .map_err(|e| CliError::Protocol(format!("response is not a valid JSON object: {e}")))?;
    match raw.api_version.as_ref().and_then(Value::as_i64) {
        Some(API_VERSION) => {}
        Some(other) => {
            return Err(CliError::Protocol(format!(
                "unsupported api_version {other} (gnctl speaks {API_VERSION})"
            )))
        }
        None => {
            return Err(CliError::Protocol(
                "response has no integer api_version".into(),
            ))
        }
    }
    match raw.ok {
        Some(Value::Bool(true)) => Ok(raw.data.unwrap_or(Value::Null)),
        Some(Value::Bool(false)) => Err(app_error(raw.error)),
        _ => Err(CliError::Protocol("response has no boolean 'ok'".into())),
    }
}

fn app_error(error: Option<Value>) -> CliError {
    let Some(Value::Object(mut error)) = error else {
        return CliError::Protocol("error response without an 'error' object".into());
    };
    let Some(Value::String(code)) = error.remove("code") else {
        return CliError::Protocol("error response without a string 'code'".into());
    };
    let message = match error.remove("message") {
        Some(Value::String(m)) => m,
        _ => code.clone(),
    };
    let details = error.remove("details").filter(|d| !d.is_null());
    CliError::App {
        code,
        message,
        details,
    }
}

/// Sends one command and returns its `data`.
pub fn call(
    runner: &dyn AdbRunner,
    package: &str,
    command: &str,
    args: &Map<String, Value>,
) -> Result<Value, CliError> {
    let encoded = encode_request(command, args);
    let out = runner.run(&content_call_args(package, &encoded)?)?;
    parse_response(&parse_content_output(&out)?)
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;
    use crate::adb::testing::FakeAdb;

    fn args(v: Value) -> Map<String, Value> {
        v.as_object().cloned().unwrap()
    }

    fn bundle(json: &str) -> String {
        format!("Result: Bundle[{{json={json}}}]\n")
    }

    fn decode(encoded: &str) -> Value {
        serde_json::from_slice(&URL_SAFE_NO_PAD.decode(encoded).unwrap()).unwrap()
    }

    #[test]
    fn request_is_base64url_json_without_padding() {
        // Characters chosen so standard base64 would produce '+' and '/'.
        let a = args(json!({"source": "STEAM", "q": "??>>~~"}));
        let encoded = encode_request("games.list", &a);
        assert!(encoded
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
        assert_eq!(
            decode(&encoded),
            json!({"api_version": 1, "command": "games.list", "args": {"source": "STEAM", "q": "??>>~~"}})
        );
    }

    #[test]
    fn authority_follows_package() {
        assert_eq!(authority("app.gamenative").unwrap(), "app.gamenative.agent");
        assert_eq!(
            authority("app.gamenative.gold").unwrap(),
            "app.gamenative.gold.agent"
        );
    }

    #[test]
    fn invalid_packages_are_usage_errors() {
        for bad in [
            "",
            "gamenative",
            "app..x",
            "app.1x",
            "app.x;reboot",
            "app.x y",
            "app.x/$(id)",
        ] {
            let err = authority(bad).unwrap_err();
            assert_eq!(err.exit_code(), 3, "{bad}");
        }
    }

    #[test]
    fn call_sends_content_call_and_returns_data() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle(
            r#"{"api_version":1,"ok":true,"data":[{"app_id":"STEAM_1"}]}"#,
        ));
        let data = call(
            &fake,
            "app.gamenative.gold",
            "games.list",
            &args(json!({"source": "STEAM"})),
        )
        .unwrap();
        assert_eq!(data, json!([{"app_id": "STEAM_1"}]));

        let calls = fake.calls();
        assert_eq!(calls.len(), 1);
        let c = &calls[0];
        assert_eq!(
            c[..8],
            [
                "shell",
                "content",
                "call",
                "--uri",
                "content://app.gamenative.gold.agent",
                "--method",
                "command",
                "--arg"
            ]
        );
        assert_eq!(c.len(), 9);
        assert_eq!(
            decode(&c[8]),
            json!({"api_version": 1, "command": "games.list", "args": {"source": "STEAM"}})
        );
    }

    #[test]
    fn invalid_package_never_calls_adb() {
        let fake = FakeAdb::new();
        let err = call(&fake, "bad pkg", "capabilities", &Map::new()).unwrap_err();
        assert_eq!(err.exit_code(), 3);
        assert!(fake.calls().is_empty());
    }

    #[test]
    fn bundle_parsing_handles_brackets_in_json_and_crlf() {
        let json = r#"{"api_version":1,"ok":true,"data":{"line":"x}]y","n":[1,{"a":"}]"}]}}"#;
        let out = AdbOutput {
            status: Some(0),
            stdout: format!("Result: Bundle[{{json={json}}}]\r\n").into_bytes(),
            stderr: Vec::new(),
        };
        let extracted = parse_content_output(&out).unwrap();
        assert_eq!(extracted, json);
        assert_eq!(
            parse_response(&extracted).unwrap(),
            json!({"line": "x}]y", "n": [1, {"a": "}]"}]})
        );
    }

    #[test]
    fn ok_without_data_is_null() {
        assert_eq!(
            parse_response(r#"{"api_version":1,"ok":true}"#).unwrap(),
            Value::Null
        );
        assert_eq!(
            parse_response(r#"{"api_version":1,"ok":true,"data":null}"#).unwrap(),
            Value::Null
        );
    }

    #[test]
    fn adb_failure_is_exit_2() {
        let fake = FakeAdb::new();
        fake.push_status(1, "", "adb: device 'X' not found\n");
        let err = call(&fake, DEFAULT_PACKAGE, "capabilities", &Map::new()).unwrap_err();
        assert_eq!(err.exit_code(), 2);
        assert!(err.message().contains("device 'X' not found"));

        let fake = FakeAdb::new();
        fake.push(Err(CliError::Adb("failed to run 'adb'".into())));
        let err = call(&fake, DEFAULT_PACKAGE, "capabilities", &Map::new()).unwrap_err();
        assert_eq!(err.exit_code(), 2);
    }

    #[test]
    fn provider_failure_text_is_exit_2() {
        let fake = FakeAdb::new();
        fake.push_status(
            0,
            "",
            "Error while accessing provider:app.gamenative.agent\njava.lang.SecurityException: uid 10234 may not use the agent API\n",
        );
        let err = call(&fake, DEFAULT_PACKAGE, "capabilities", &Map::new()).unwrap_err();
        assert_eq!(err.exit_code(), 2);
        assert!(err.message().contains("SecurityException"));

        // Same text on stdout with no Result line (older content tool builds print there).
        let fake = FakeAdb::new();
        fake.push_ok("Error while accessing provider:app.gamenative.agent\n");
        let err = call(&fake, DEFAULT_PACKAGE, "capabilities", &Map::new()).unwrap_err();
        assert_eq!(err.exit_code(), 2);
    }

    #[test]
    fn provider_error_text_inside_response_json_is_data() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle(
            r#"{"api_version":1,"ok":true,"data":{"lines":["wine: Error while accessing provider x"]}}"#,
        ));
        let data = call(&fake, DEFAULT_PACKAGE, "sessions.logs", &Map::new()).unwrap();
        assert_eq!(
            data,
            json!({"lines": ["wine: Error while accessing provider x"]})
        );

        let fake = FakeAdb::new();
        fake.push_ok(bundle(
            r#"{"api_version":1,"ok":false,"error":{"code":"UNKNOWN_COMMAND","message":"Unknown command: Error while accessing provider"}}"#,
        ));
        let err = call(
            &fake,
            DEFAULT_PACKAGE,
            "Error while accessing provider",
            &Map::new(),
        )
        .unwrap_err();
        assert_eq!(err.exit_code(), 4);
        assert!(matches!(err, CliError::App { ref code, .. } if code == "UNKNOWN_COMMAND"));
    }

    #[test]
    fn garbage_is_exit_3() {
        for stdout in [
            "",
            "hello world\n",
            "Result: null\n",
            "Result: Bundle[{other=1}]\n",
            "Result: Bundle[mParcelledData.dataSize=120]\n",
        ] {
            let fake = FakeAdb::new();
            fake.push_ok(stdout);
            let err = call(&fake, DEFAULT_PACKAGE, "capabilities", &Map::new()).unwrap_err();
            assert_eq!(err.exit_code(), 3, "{stdout:?}");
        }
        for json in [
            "not json",
            "[1,2]",
            r#"{"ok":true}"#,
            r#"{"api_version":"1","ok":true}"#,
            r#"{"api_version":2,"ok":true}"#,
            r#"{"api_version":1}"#,
            r#"{"api_version":1,"ok":"yes"}"#,
            r#"{"api_version":1,"ok":false}"#,
            r#"{"api_version":1,"ok":false,"error":{"message":"m"}}"#,
        ] {
            let fake = FakeAdb::new();
            fake.push_ok(bundle(json));
            let err = call(&fake, DEFAULT_PACKAGE, "capabilities", &Map::new()).unwrap_err();
            assert_eq!(err.exit_code(), 3, "{json}");
        }
    }

    #[test]
    fn app_error_is_exit_4_with_code_and_details() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle(
            r#"{"api_version":1,"ok":false,"error":{"code":"MISSING_COMPONENTS","message":"missing","details":[{"value":"dxvk-2.4","downloadable":true}]}}"#,
        ));
        let err = call(&fake, DEFAULT_PACKAGE, "containers.set", &Map::new()).unwrap_err();
        assert_eq!(err.exit_code(), 4);
        assert_eq!(
            err,
            CliError::App {
                code: "MISSING_COMPONENTS".into(),
                message: "missing".into(),
                details: Some(json!([{"value": "dxvk-2.4", "downloadable": true}])),
            }
        );
    }

    #[test]
    fn app_error_without_message_uses_code() {
        let err = parse_response(r#"{"api_version":1,"ok":false,"error":{"code":"DISABLED"}}"#)
            .unwrap_err();
        assert_eq!(err, CliError::app("DISABLED", "DISABLED"));
    }
}
