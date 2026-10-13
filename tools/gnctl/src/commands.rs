//! Subcommand implementations: thin wrappers over the agent API commands plus the host-side
//! adb features (screenshot, log pull). Launch and the session waits live in [`crate::launch`].

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::{json, Map, Value};

use crate::adb::{self, truncate, AdbRunner};
use crate::cli::{
    AppId, Cli, Command, ComponentsCommand, ContainersCommand, DeviceCommand, GamesCommand,
    LogsCommand, SessionsCommand, StoresCommand, Switch,
};
use crate::error::CliError;
use crate::launch::{self, Clock};
use crate::protocol;

/// Code of the error for `logs pull` when the session has no log file.
pub const NO_LOG_FILE: &str = "NO_LOG_FILE";
/// Code of the error for `components install` when the download or install failed.
pub const INSTALL_FAILED: &str = "INSTALL_FAILED";
/// How often `components install --wait` asks for the job's progress.
const INSTALL_POLL: Duration = Duration::from_secs(1);
/// Keys a `containers set` file may contain (the `containers.set` args besides `app_id`).
const SET_KEYS: [&str; 3] = ["values", "env", "app_id"];
const SET_SHAPE: &str =
    r#"{"values":{"key":"value"},"env":{"set":{"NAME":"value"},"unset":["NAME"]}}"#;
const PNG_MAGIC: &[u8] = b"\x89PNG\r\n\x1a\n";

/// Everything a command needs from the outside world; faked in tests.
pub struct Ctx<'a> {
    pub runner: &'a dyn AdbRunner,
    pub package: &'a str,
    pub stdin: &'a mut dyn Read,
    pub clock: &'a dyn Clock,
}

impl Ctx<'_> {
    /// Sends one agent API command; `args` must be a JSON object (or null for none).
    pub fn call(&self, command: &str, args: Value) -> Result<Value, CliError> {
        let args = match args {
            Value::Object(map) => map,
            Value::Null => Map::new(),
            other => unreachable!("command args must be an object, got {other}"),
        };
        protocol::call(self.runner, self.package, command, &args)
    }
}

pub fn execute(cli: &Cli, ctx: &mut Ctx) -> Result<Value, CliError> {
    match &cli.command {
        Command::Capabilities => ctx.call("capabilities", Value::Null),
        Command::Device(DeviceCommand::Info) => ctx.call("device.info", Value::Null),
        Command::Games(GamesCommand::List { source }) => {
            ctx.call("games.list", optional("source", source.as_deref()))
        }
        Command::Games(GamesCommand::Add { path }) => {
            ctx.call("games.add_custom", json!({ "path": path }))
        }
        Command::Stores(StoresCommand::List) => ctx.call("stores.list", Value::Null),
        Command::Stores(StoresCommand::Set { source, state }) => ctx.call(
            "stores.set",
            json!({ "source": source, "enabled": *state == Switch::On }),
        ),
        Command::Components(ComponentsCommand::List { target }) => {
            ctx.call("components.list", optional("target", target.as_deref()))
        }
        Command::Components(ComponentsCommand::Install {
            target,
            value,
            variant,
            wait,
            timeout,
        }) => components_install(ctx, target, value, variant.as_deref(), *wait, *timeout),
        Command::Containers(command) => containers(ctx, command),
        Command::Sessions(command) => sessions(ctx, command),
        Command::Launch(args) => launch::launch(ctx, args),
        Command::Screenshot { output } => screenshot(ctx, output),
        Command::Logs(LogsCommand::Pull { output }) => logs_pull(ctx, output.as_deref()),
        Command::Call { command, args } => {
            let args = parse_args_object(args)?;
            ctx.call(command, Value::Object(args))
        }
    }
}

/// `components.install`; with [wait], asked again until the job is `installed` or `failed`. The app keeps
/// one job per component, so asking again reports progress instead of starting a second download.
fn components_install(
    ctx: &Ctx,
    target: &str,
    value: &str,
    variant: Option<&str>,
    wait: bool,
    timeout: u64,
) -> Result<Value, CliError> {
    let mut args = json!({ "target": target, "value": value });
    if let Some(variant) = variant {
        args["variant"] = Value::String(variant.into());
    }
    let deadline = ctx.clock.now() + Duration::from_secs(timeout);
    loop {
        let job = ctx.call("components.install", args.clone())?;
        let state = job
            .get("state")
            .and_then(Value::as_str)
            .ok_or_else(|| CliError::Protocol("components.install has no string 'state'".into()))?
            .to_string();
        match state.as_str() {
            "failed" => {
                let reason = job
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("no reason given");
                return Err(CliError::app_with(
                    INSTALL_FAILED,
                    format!("installing {value} failed: {reason}"),
                    job,
                ));
            }
            "installed" => return Ok(job),
            _ if !wait => return Ok(job),
            _ => {}
        }
        let now = ctx.clock.now();
        if now >= deadline {
            return Err(CliError::Timeout(format!(
                "{value} is still {state} after {timeout}s"
            )));
        }
        ctx.clock.sleep(INSTALL_POLL.min(deadline - now));
    }
}

fn optional(key: &str, value: Option<&str>) -> Value {
    let mut map = Map::new();
    if let Some(value) = value {
        map.insert(key.into(), Value::String(value.into()));
    }
    Value::Object(map)
}

fn parse_args_object(text: &str) -> Result<Map<String, Value>, CliError> {
    match serde_json::from_str::<Value>(text) {
        Ok(Value::Object(map)) => Ok(map),
        Ok(_) => Err(CliError::Usage("--args must be a JSON object".into())),
        Err(e) => Err(CliError::Usage(format!("--args is not valid JSON: {e}"))),
    }
}

fn containers(ctx: &mut Ctx, command: &ContainersCommand) -> Result<Value, CliError> {
    match command {
        ContainersCommand::Get(game) => {
            let app = AppId::from_arg(game)?;
            ctx.call("containers.get", json!({ "app_id": app.to_string() }))
        }
        ContainersCommand::Set { game, file } => {
            let app = AppId::from_arg(game)?;
            let mut args = read_changes(ctx, file, &app)?;
            args.insert("app_id".into(), Value::String(app.to_string()));
            ctx.call("containers.set", Value::Object(args))
        }
        ContainersCommand::Restore {
            game,
            backup,
            baseline,
        } => {
            let app = AppId::from_arg(game)?;
            let args = match backup {
                Some(id) => json!({ "app_id": app.to_string(), "backup_id": id }),
                None if *baseline => json!({ "app_id": app.to_string(), "baseline": true }),
                None => return Err(CliError::Usage("pass --backup ID or --baseline".into())),
            };
            ctx.call("containers.restore", args)
        }
        ContainersCommand::Backups(game) => {
            let app = AppId::from_arg(game)?;
            ctx.call("containers.backups", json!({ "app_id": app.to_string() }))
        }
    }
}

/// Reads the `containers set` changes from a file or stdin (`-`). Only `values`, `env` and a matching
/// `app_id` are allowed: flat keys at the top level would be ignored by the app, so they are refused.
fn read_changes(ctx: &mut Ctx, file: &str, app: &AppId) -> Result<Map<String, Value>, CliError> {
    let (text, origin) = if file == "-" {
        let mut text = String::new();
        ctx.stdin
            .read_to_string(&mut text)
            .map_err(|e| CliError::Io(format!("cannot read stdin: {e}")))?;
        (text, "stdin".to_string())
    } else {
        let text = fs::read_to_string(file)
            .map_err(|e| CliError::Io(format!("cannot read {file}: {e}")))?;
        (text, file.to_string())
    };
    let map = match serde_json::from_str::<Value>(&text) {
        Ok(Value::Object(map)) => map,
        Ok(_) => {
            return Err(CliError::Usage(format!(
                "{origin} must hold a JSON object like {SET_SHAPE}"
            )))
        }
        Err(e) => return Err(CliError::Usage(format!("{origin} is not valid JSON: {e}"))),
    };
    let unknown: Vec<&str> = map
        .keys()
        .map(String::as_str)
        .filter(|k| !SET_KEYS.contains(k))
        .collect();
    if !unknown.is_empty() {
        return Err(CliError::Usage(format!(
            "unexpected keys in {origin}: {}; expected {SET_SHAPE}",
            unknown.join(", ")
        )));
    }
    if let Some(id) = map.get("app_id") {
        let same = id
            .as_str()
            .and_then(|s| AppId::parse(s, None).ok())
            .is_some_and(|parsed| parsed == *app);
        if !same {
            return Err(CliError::Usage(format!(
                "app_id {id} in {origin} does not match {app}"
            )));
        }
    }
    Ok(map)
}

fn sessions(ctx: &mut Ctx, command: &SessionsCommand) -> Result<Value, CliError> {
    match command {
        SessionsCommand::Current => ctx.call("sessions.current", Value::Null),
        SessionsCommand::Stop => ctx.call("sessions.stop", Value::Null),
        SessionsCommand::Logs { tail } => {
            let args = match tail {
                Some(tail) => json!({ "tail": tail }),
                None => Value::Null,
            };
            ctx.call("sessions.logs", args)
        }
        SessionsCommand::Wait { until, timeout } => {
            launch::wait_current(ctx, *until, launch::secs(Some(*timeout)))
        }
    }
}

/// `adb exec-out screencap -p` into [output]; anything that is not a PNG is an adb error.
///
/// `exec-out` has no separate stderr, so text `screencap` prints first (the multiple-displays warning
/// on dual-screen and foldable devices) can precede the PNG; it is dropped.
fn screenshot(ctx: &Ctx, output: &Path) -> Result<Value, CliError> {
    let out = adb::exec_out(ctx.runner, &["screencap", "-p"])?;
    let Some(start) = out.windows(PNG_MAGIC.len()).position(|w| w == PNG_MAGIC) else {
        return Err(CliError::Adb(format!(
            "screencap did not return a PNG: {}",
            truncate(String::from_utf8_lossy(&out).trim(), 200)
        )));
    };
    let png = &out[start..];
    fs::write(output, png)
        .map_err(|e| CliError::Io(format!("cannot write {}: {e}", output.display())))?;
    Ok(json!({ "file": output.display().to_string(), "bytes": png.len() }))
}

/// `adb pull` of the current session's `log_file` (set by an `agent_debug` launch). [output] may be
/// a file or an existing directory (the remote file name is kept); default: that name in the
/// working directory.
fn logs_pull(ctx: &Ctx, output: Option<&Path>) -> Result<Value, CliError> {
    let current = ctx.call("sessions.current", Value::Null)?;
    if current.is_null() {
        return Err(CliError::app(
            NO_LOG_FILE,
            "no session yet; launch with `gnctl launch <app_id> --debug` to get a log file",
        ));
    }
    let session_id = current.get("session_id").cloned().unwrap_or(Value::Null);
    let Some(remote) = current.get("log_file").and_then(Value::as_str) else {
        let state = current.get("state").cloned().unwrap_or(Value::Null);
        let agent_debug = current.get("agent_debug") == Some(&Value::Bool(true));
        let message = if agent_debug {
            format!(
                "the session has no log file yet; it is created when the session reaches starting \
                 (state: {}; a launch that ends before starting never gets one)",
                state.as_str().unwrap_or("unknown")
            )
        } else {
            "the session has no log file; only launches with --debug (agent_debug) write one"
                .to_string()
        };
        return Err(CliError::app_with(
            NO_LOG_FILE,
            message,
            json!({ "session_id": session_id, "state": state, "agent_debug": agent_debug }),
        ));
    };
    let name = Path::new(remote)
        .file_name()
        .map_or_else(|| PathBuf::from("agent.log"), PathBuf::from);
    let local: PathBuf = match output {
        Some(dir) if dir.is_dir() => dir.join(name),
        Some(path) => path.to_path_buf(),
        None => name,
    };
    let local_text = local.display().to_string();
    adb::run_checked(
        ctx.runner,
        &["pull".to_string(), remote.to_string(), local_text.clone()],
    )?;
    let bytes = fs::metadata(&local)
        .map_err(|e| CliError::Io(format!("adb pull reported success but {local_text}: {e}")))?
        .len();
    Ok(json!({
        "file": local_text,
        "remote": remote,
        "bytes": bytes,
        "session_id": session_id,
    }))
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use serde_json::json;

    use crate::adb::testing::FakeAdb;
    use crate::adb::AdbOutput;
    use crate::test_support::{bundle_ok, decoded_request, run_cli, run_cli_stdin, FakeClock};

    use super::*;

    fn temp_path(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("gnctl-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        dir.join(name)
    }

    fn job(state: &str) -> Value {
        json!({"target": "fexcoreVersion", "value": "2609-0", "state": state, "progress": 0.5, "message": "why"})
    }

    #[test]
    fn components_install_wait_polls_until_installed() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(job("queued")));
        fake.push_ok(bundle_ok(job("downloading")));
        fake.push_ok(bundle_ok(job("installed")));
        let clock = FakeClock::default();
        let (code, out) = run_cli(
            &[
                "components",
                "install",
                "fexcoreVersion",
                "2609-0",
                "--wait",
            ],
            &fake,
            &clock,
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["state"], "installed");
        assert_eq!(clock.sleeps(), 2);
    }

    #[test]
    fn components_install_without_wait_answers_once() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(job("queued")));
        let clock = FakeClock::default();
        let (code, out) = run_cli(
            &[
                "components",
                "install",
                "box64Version",
                "0.4.4-1",
                "--variant",
                "arm64ec",
            ],
            &fake,
            &clock,
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["state"], "queued");
        assert_eq!(clock.sleeps(), 0);
        let request = decoded_request(&fake.calls()[0]);
        assert_eq!(request["command"], "components.install");
        assert_eq!(
            request["args"],
            json!({"target": "box64Version", "value": "0.4.4-1", "variant": "arm64ec"})
        );
    }

    #[test]
    fn components_install_failure_and_timeout_are_errors() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(job("downloading")));
        fake.push_ok(bundle_ok(job("failed")));
        let clock = FakeClock::default();
        let (code, out) = run_cli(
            &[
                "components",
                "install",
                "fexcoreVersion",
                "2609-0",
                "--wait",
            ],
            &fake,
            &clock,
        );
        assert_eq!(code, 4, "{out}");
        assert_eq!(out["error"]["code"], INSTALL_FAILED);
        assert_eq!(out["error"]["details"]["state"], "failed");

        let fake = FakeAdb::new();
        for _ in 0..3 {
            fake.push_ok(bundle_ok(job("downloading")));
        }
        let clock = FakeClock::default();
        let (code, out) = run_cli(
            &[
                "components",
                "install",
                "fexcoreVersion",
                "2609-0",
                "--wait",
                "--timeout",
                "2",
            ],
            &fake,
            &clock,
        );
        assert_eq!(code, 5, "{out}");
        assert_eq!(out["error"]["code"], "TIMEOUT");
    }

    #[test]
    fn wrappers_send_the_right_commands() {
        let cases: Vec<(Vec<&str>, Value)> = vec![
            (
                vec!["capabilities"],
                json!({"command": "capabilities", "args": {}}),
            ),
            (
                vec!["device", "info"],
                json!({"command": "device.info", "args": {}}),
            ),
            (
                vec!["games", "list"],
                json!({"command": "games.list", "args": {}}),
            ),
            (
                vec!["games", "list", "--source", "GOG"],
                json!({"command": "games.list", "args": {"source": "GOG"}}),
            ),
            (
                vec!["games", "add", "/sdcard/Games/PC/My Game"],
                json!({"command": "games.add_custom", "args": {"path": "/sdcard/Games/PC/My Game"}}),
            ),
            (
                vec!["stores", "list"],
                json!({"command": "stores.list", "args": {}}),
            ),
            (
                vec!["stores", "set", "gog", "on"],
                json!({"command": "stores.set", "args": {"source": "gog", "enabled": true}}),
            ),
            (
                vec!["stores", "set", "STEAM", "off"],
                json!({"command": "stores.set", "args": {"source": "STEAM", "enabled": false}}),
            ),
            (
                vec!["components", "list", "--target", "wineVersion"],
                json!({"command": "components.list", "args": {"target": "wineVersion"}}),
            ),
            (
                vec!["containers", "get", "STEAM_123"],
                json!({"command": "containers.get", "args": {"app_id": "STEAM_123"}}),
            ),
            (
                vec!["containers", "get", "123", "--source", "steam"],
                json!({"command": "containers.get", "args": {"app_id": "STEAM_123"}}),
            ),
            (
                vec!["containers", "backups", "GOG_5"],
                json!({"command": "containers.backups", "args": {"app_id": "GOG_5"}}),
            ),
            (
                vec![
                    "containers",
                    "restore",
                    "STEAM_1",
                    "--backup",
                    "1700000000000",
                ],
                json!({"command": "containers.restore", "args": {"app_id": "STEAM_1", "backup_id": "1700000000000"}}),
            ),
            (
                vec!["containers", "restore", "STEAM_1", "--baseline"],
                json!({"command": "containers.restore", "args": {"app_id": "STEAM_1", "baseline": true}}),
            ),
            (
                vec!["sessions", "current"],
                json!({"command": "sessions.current", "args": {}}),
            ),
            (
                vec!["sessions", "stop"],
                json!({"command": "sessions.stop", "args": {}}),
            ),
            (
                vec!["sessions", "logs"],
                json!({"command": "sessions.logs", "args": {}}),
            ),
            (
                vec!["sessions", "logs", "--tail", "50"],
                json!({"command": "sessions.logs", "args": {"tail": 50}}),
            ),
        ];
        for (argv, expected) in cases {
            let fake = FakeAdb::new();
            fake.push_ok(bundle_ok(json!({"x": 1})));
            let clock = FakeClock::default();
            let (code, out) = run_cli(&argv, &fake, &clock);
            assert_eq!(code, 0, "{argv:?}: {out}");
            assert_eq!(out, json!({"x": 1}));
            let request = decoded_request(&fake.calls()[0]);
            assert_eq!(request["command"], expected["command"], "{argv:?}");
            assert_eq!(request["args"], expected["args"], "{argv:?}");
        }
    }

    #[test]
    fn invalid_app_id_or_restore_flags_are_usage_errors_without_adb() {
        for argv in [
            vec!["containers", "get", "123"],
            vec!["containers", "get", "STEAM_1", "--source", "GOG"],
            vec!["containers", "restore", "STEAM_1"],
            vec![
                "containers",
                "restore",
                "STEAM_1",
                "--backup",
                "1",
                "--baseline",
            ],
            vec!["sessions", "logs", "--tail", "-1"],
        ] {
            let fake = FakeAdb::new();
            let (code, out) = run_cli(&argv, &fake, &FakeClock::default());
            assert_eq!(code, 3, "{argv:?}");
            assert_eq!(out["error"]["code"], "USAGE_ERROR", "{argv:?}");
            assert!(fake.calls().is_empty());
        }
    }

    #[test]
    fn containers_set_from_file() {
        let path = temp_path("set.json");
        fs::File::create(&path)
            .unwrap()
            .write_all(br#"{"values":{"dxwrapperConfig.version":"2.4"},"env":{"set":{"DXVK_HUD":"fps"},"unset":["MESA_X"]}}"#)
            .unwrap();
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(
            json!({"backup_id": "1", "changed_fields": ["dxwrapperConfig"]}),
        ));
        let (code, out) = run_cli(
            &[
                "containers",
                "set",
                "123",
                "--source",
                "STEAM",
                path.to_str().unwrap(),
            ],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["backup_id"], "1");
        let request = decoded_request(&fake.calls()[0]);
        assert_eq!(request["command"], "containers.set");
        assert_eq!(
            request["args"],
            json!({
                "app_id": "STEAM_123",
                "values": {"dxwrapperConfig.version": "2.4"},
                "env": {"set": {"DXVK_HUD": "fps"}, "unset": ["MESA_X"]}
            })
        );
    }

    #[test]
    fn containers_set_from_stdin() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(json!({"backup_id": null, "changed_fields": []})));
        let (code, out) = run_cli_stdin(
            &["containers", "set", "STEAM_9", "-"],
            &fake,
            &FakeClock::default(),
            br#"{"app_id":"steam_9","values":{"wineVersion":"proton-9.0-arm64ec"}}"#,
        );
        assert_eq!(code, 0, "{out}");
        let request = decoded_request(&fake.calls()[0]);
        assert_eq!(
            request["args"],
            json!({"app_id": "STEAM_9", "values": {"wineVersion": "proton-9.0-arm64ec"}})
        );
    }

    #[test]
    fn containers_set_rejects_bad_input_without_adb() {
        for (stdin, code_expected) in [
            (&b"not json"[..], "USAGE_ERROR"),
            (b"[1]", "USAGE_ERROR"),
            (br#"{"wineVersion":"x"}"#, "USAGE_ERROR"),
            (br#"{"app_id":"STEAM_2","values":{}}"#, "USAGE_ERROR"),
            (br#"{"app_id":5,"values":{}}"#, "USAGE_ERROR"),
        ] {
            let fake = FakeAdb::new();
            let (code, out) = run_cli_stdin(
                &["containers", "set", "STEAM_1", "-"],
                &fake,
                &FakeClock::default(),
                stdin,
            );
            assert_eq!(code, 3, "{}", String::from_utf8_lossy(stdin));
            assert_eq!(out["error"]["code"], code_expected);
            assert!(fake.calls().is_empty());
        }
        let fake = FakeAdb::new();
        let (code, out) = run_cli(
            &[
                "containers",
                "set",
                "STEAM_1",
                "/nonexistent/gnctl/set.json",
            ],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 3);
        assert_eq!(out["error"]["code"], "IO_ERROR");
    }

    #[test]
    fn containers_set_app_error_keeps_details() {
        let fake = FakeAdb::new();
        fake.push_ok(format!(
            "Result: Bundle[{{json={}}}]",
            json!({"api_version": 1, "ok": false, "error": {
                "code": "BAD_ARGS", "message": "rejected",
                "details": {"rejected": [{"key": "containerVariant", "reason": "read_only"}]}
            }})
        ));
        let (code, out) = run_cli_stdin(
            &["containers", "set", "STEAM_1", "-"],
            &fake,
            &FakeClock::default(),
            br#"{"values":{"containerVariant":"bionic"}}"#,
        );
        assert_eq!(code, 4);
        assert_eq!(
            out["error"]["details"]["rejected"][0]["reason"],
            "read_only"
        );
    }

    #[test]
    fn screenshot_writes_png() {
        let path = temp_path("shot.png");
        let _ = fs::remove_file(&path);
        let png = [PNG_MAGIC, &[0u8, 1, 2, 0xff, b'\n']].concat();
        let fake = FakeAdb::new();
        fake.push_ok(png.clone());
        let (code, out) = run_cli(
            &["screenshot", "-o", path.to_str().unwrap()],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(fs::read(&path).unwrap(), png);
        assert_eq!(out["bytes"], png.len());
        assert_eq!(fake.calls(), vec![vec!["exec-out", "screencap", "-p"]]);
    }

    #[test]
    fn screenshot_rejects_non_png_and_adb_failure() {
        let path = temp_path("bad.png");
        let fake = FakeAdb::new();
        fake.push_ok("Error: no display");
        let (code, out) = run_cli(
            &["screenshot", "-o", path.to_str().unwrap()],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 2);
        assert!(out["error"]["message"]
            .as_str()
            .unwrap()
            .contains("no display"));

        let fake = FakeAdb::new();
        fake.push_status(1, "", "error: no devices/emulators found");
        let (code, _) = run_cli(
            &["screenshot", "-o", path.to_str().unwrap()],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 2);
    }

    #[test]
    fn screenshot_drops_text_before_the_png() {
        let path = temp_path("multi.png");
        let _ = fs::remove_file(&path);
        let png = [PNG_MAGIC, &[0u8, 1, 2]].concat();
        let warning = b"[Warning] Multiple displays were found, but no display id was specified! \
                        Defaulting to the first display found, however this default is not guaranteed \
                        to be consistent across captures. A display id should be specified.\n";
        let fake = FakeAdb::new();
        fake.push_ok([warning.as_slice(), &png].concat());
        let (code, out) = run_cli(
            &["screenshot", "-o", path.to_str().unwrap()],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(fs::read(&path).unwrap(), png);
        assert_eq!(out["bytes"], png.len());
    }

    /// Fake adb whose `pull` writes the local file like the real one.
    struct PullingAdb(FakeAdb, Vec<u8>);

    impl AdbRunner for PullingAdb {
        fn run(&self, args: &[String]) -> Result<AdbOutput, CliError> {
            if args.first().map(String::as_str) == Some("pull") {
                fs::write(&args[2], &self.1).unwrap();
            }
            self.0.run(args)
        }
    }

    #[test]
    fn logs_pull_writes_the_file() {
        let path = temp_path("agent.log");
        let _ = fs::remove_file(&path);
        let remote =
            "/storage/emulated/0/Android/data/app.gamenative/files/wine_logs/agent_STEAM_1.log";
        let fake = PullingAdb(FakeAdb::new(), b"line1\nline2\n".to_vec());
        fake.0.push_ok(bundle_ok(
            json!({"session_id": "s1", "state": "exited", "log_file": remote}),
        ));
        fake.0.push_ok(format!("{remote}: 1 file pulled.\n"));
        let (code, out) = crate::test_support::run_cli_runner(
            &["logs", "pull", "-o", path.to_str().unwrap()],
            &fake,
            &FakeClock::default(),
            b"",
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(fs::read(&path).unwrap(), b"line1\nline2\n");
        assert_eq!(out["bytes"], 12);
        assert_eq!(out["remote"], remote);
        assert_eq!(out["session_id"], "s1");
        assert_eq!(
            fake.0.calls()[1],
            vec!["pull", remote, path.to_str().unwrap()]
        );
    }

    #[test]
    fn logs_pull_into_a_directory_keeps_the_remote_name() {
        let dir = temp_path("pull-dir");
        fs::create_dir_all(&dir).unwrap();
        let remote = "/x/wine_logs/agent_STEAM_5.log";
        let expected = dir.join("agent_STEAM_5.log");
        let _ = fs::remove_file(&expected);
        let fake = PullingAdb(FakeAdb::new(), b"abc".to_vec());
        fake.0.push_ok(bundle_ok(
            json!({"session_id": "s5", "state": "running", "log_file": remote}),
        ));
        fake.0.push_ok("1 file pulled.\n");
        let (code, out) = crate::test_support::run_cli_runner(
            &["logs", "pull", "-o", dir.to_str().unwrap()],
            &fake,
            &FakeClock::default(),
            b"",
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["file"], expected.display().to_string());
        assert_eq!(out["bytes"], 3);
        assert_eq!(fs::read(&expected).unwrap(), b"abc");
    }

    #[test]
    fn logs_pull_defaults_to_the_remote_file_name() {
        let local = logs_pull_local(None, "/x/wine_logs/agent_GOG_3.log");
        assert_eq!(local, "agent_GOG_3.log");
    }

    fn logs_pull_local(output: Option<&Path>, remote: &str) -> String {
        // Fake pull that does not create the file: the call fails after adb with IO_ERROR,
        // but the adb arguments show the chosen local path.
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(json!({"session_id": "s", "log_file": remote})));
        fake.push_ok("1 file pulled");
        let clock = FakeClock::default();
        let mut stdin: &[u8] = b"";
        let ctx = Ctx {
            runner: &fake,
            package: protocol::DEFAULT_PACKAGE,
            stdin: &mut stdin,
            clock: &clock,
        };
        let _ = logs_pull(&ctx, output);
        fake.calls()[1][2].clone()
    }

    #[test]
    fn logs_pull_without_log_file_is_a_clear_error() {
        for current in [Value::Null, json!({"session_id": "s1", "state": "running"})] {
            let fake = FakeAdb::new();
            fake.push_ok(bundle_ok(current.clone()));
            let (code, out) = run_cli(&["logs", "pull"], &fake, &FakeClock::default());
            assert_eq!(code, 4, "{current}");
            assert_eq!(out["error"]["code"], NO_LOG_FILE);
            assert!(out["error"]["message"]
                .as_str()
                .unwrap()
                .contains("--debug"));
            assert_eq!(fake.calls().len(), 1, "no adb pull");
        }
    }

    #[test]
    fn logs_pull_for_a_debug_session_before_starting_says_to_wait() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(
            json!({"session_id": "s1", "state": "launching", "agent_debug": true}),
        ));
        let (code, out) = run_cli(&["logs", "pull"], &fake, &FakeClock::default());
        assert_eq!(code, 4);
        assert_eq!(out["error"]["code"], NO_LOG_FILE);
        let message = out["error"]["message"].as_str().unwrap();
        assert!(
            message.contains("reaches starting") && message.contains("state: launching"),
            "{message}"
        );
        assert!(!message.contains("--debug"), "{message}");
        assert_eq!(
            out["error"]["details"],
            json!({"session_id": "s1", "state": "launching", "agent_debug": true})
        );
    }
}
