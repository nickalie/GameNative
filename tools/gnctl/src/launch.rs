//! `gnctl launch` and the session waits.
//!
//! A launch sends the app's `LAUNCH_GAME` intent with `am start`. The app starts a new `launching`
//! session for it, except while a session is `starting`, `running` or `exiting`: that session is kept
//! (the app brings it forward) and the intent's agent options are discarded. A `launching` session is
//! replaced. So the CLI refuses to launch over an active session (`SESSION_BUSY`) unless `--force`, and
//! never waits for a new session the app will not create.

use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::adb::truncate;
use crate::channels;
use crate::cli::{AppId, LaunchArgs, WaitUntil, DEFAULT_WAIT_SECS};
use crate::commands::Ctx;
use crate::error::CliError;
use crate::protocol;

pub const MAIN_ACTIVITY: &str = "app.gamenative.MainActivity";
pub const ACTION_LAUNCH_GAME: &str = "app.gamenative.LAUNCH_GAME";

/// How often a wait polls `sessions.current`.
pub const POLL_INTERVAL: Duration = Duration::from_millis(500);
/// How long the pre-launch session check retries `NOT_READY` (the app process is still starting).
pub const READY_TIMEOUT: Duration = Duration::from_secs(15);

pub const SESSION_BUSY: &str = "SESSION_BUSY";
pub const SESSION_EXITED: &str = "SESSION_EXITED";
pub const SESSION_VANISHED: &str = "SESSION_VANISHED";
pub const NO_ACTIVE_SESSION: &str = "NO_ACTIVE_SESSION";
const NOT_READY: &str = "NOT_READY";

const STATE_LAUNCHING: &str = "launching";
const STATE_EXITING: &str = "exiting";
const STATE_EXITED: &str = "exited";

/// Time source for the waits; faked in tests.
pub trait Clock {
    /// Time since an arbitrary fixed point.
    fn now(&self) -> Duration;
    fn sleep(&self, duration: Duration);
}

pub struct SystemClock(Instant);

impl SystemClock {
    pub fn new() -> Self {
        SystemClock(Instant::now())
    }
}

impl Default for SystemClock {
    fn default() -> Self {
        SystemClock::new()
    }
}

impl Clock for SystemClock {
    fn now(&self) -> Duration {
        self.0.elapsed()
    }

    fn sleep(&self, duration: Duration) {
        std::thread::sleep(duration);
    }
}

/// `--timeout` seconds (default [DEFAULT_WAIT_SECS]).
pub fn secs(timeout: Option<u64>) -> Duration {
    Duration::from_secs(timeout.unwrap_or(DEFAULT_WAIT_SECS))
}

/// The fields of a `sessions.current` object the CLI acts on, plus the object itself.
#[derive(Debug, Clone)]
struct Session {
    id: String,
    app_id: String,
    state: String,
    raw: Value,
}

impl Session {
    fn parse(value: Value) -> Result<Option<Session>, CliError> {
        if value.is_null() {
            return Ok(None);
        }
        let field = |name: &str| {
            value
                .get(name)
                .and_then(Value::as_str)
                .map(str::to_string)
                .ok_or_else(|| {
                    CliError::Protocol(format!("sessions.current has no string '{name}'"))
                })
        };
        Ok(Some(Session {
            id: field("session_id")?,
            app_id: field("app_id")?,
            state: field("state")?,
            raw: value,
        }))
    }

    fn is_active(&self) -> bool {
        self.state != STATE_EXITED
    }

    fn text(&self, name: &str) -> String {
        self.raw
            .get(name)
            .and_then(Value::as_str)
            .unwrap_or("none")
            .to_string()
    }
}

/// `sessions.current`, or `None` while the app answers `NOT_READY`.
fn poll(ctx: &Ctx) -> Result<Option<Option<Session>>, CliError> {
    match ctx.call("sessions.current", Value::Null) {
        Ok(value) => Session::parse(value).map(Some),
        Err(CliError::App { code, .. }) if code == NOT_READY => Ok(None),
        Err(e) => Err(e),
    }
}

/// `sessions.current`, retrying `NOT_READY` for up to [READY_TIMEOUT].
fn current_when_ready(ctx: &Ctx) -> Result<Option<Session>, CliError> {
    let deadline = ctx.clock.now() + READY_TIMEOUT;
    loop {
        match ctx.call("sessions.current", Value::Null) {
            Ok(value) => return Session::parse(value),
            Err(CliError::App { ref code, .. }) if code == NOT_READY => {
                let now = ctx.clock.now();
                if now >= deadline {
                    return Err(CliError::app(
                        NOT_READY,
                        "the app is still starting; try again",
                    ));
                }
                ctx.clock.sleep(POLL_INTERVAL.min(deadline - now));
            }
            Err(e) => return Err(e),
        }
    }
}

/// adb arguments of the launch intent. Channels are already validated, so they are shell-safe.
pub fn am_start_args(
    package: &str,
    app: &AppId,
    debug: bool,
    channels: Option<&str>,
) -> Vec<String> {
    let mut args: Vec<String> = [
        "shell",
        "am",
        "start",
        "-n",
        &format!("{package}/{MAIN_ACTIVITY}"),
        "-a",
        ACTION_LAUNCH_GAME,
        "--ei",
        "app_id",
        &app.number.to_string(),
        "--es",
        "game_source",
        &app.source,
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    if debug {
        args.extend(["--ez", "agent_debug", "true"].map(String::from));
    }
    if let Some(channels) = channels {
        args.extend(["--es", "wine_debug_channels", channels].map(String::from));
    }
    args
}

/// `am start` reports most failures as `Error: ...` text with a zero exit status.
fn am_start(ctx: &Ctx, args: &[String]) -> Result<(), CliError> {
    let out = ctx.runner.run(args)?;
    let text = format!("{}\n{}", out.stdout_text(), out.stderr_text());
    let failed = !out.success()
        || text.lines().any(|line| {
            let line = line.trim_start();
            line.starts_with("Error") || line.contains("Exception")
        });
    if failed {
        return Err(CliError::Adb(format!(
            "am start failed: {}",
            truncate(text.trim(), 500)
        )));
    }
    Ok(())
}

pub fn launch(ctx: &Ctx, args: &LaunchArgs) -> Result<Value, CliError> {
    let app = AppId::from_arg(&args.game)?;
    let channels = match &args.wine_debug {
        Some(raw) => channels::launch_value(raw)?,
        None => None,
    };
    protocol::validate_package(ctx.package)?;

    let previous = current_when_ready(ctx)?;
    let mut warnings = Vec::new();
    if let Some(active) = previous.as_ref().filter(|s| s.is_active()) {
        let kept = active.state != STATE_LAUNCHING;
        if !args.force {
            return Err(CliError::app_with(
                SESSION_BUSY,
                format!(
                    "session {} for {} is {}; stop it with `gnctl sessions stop` or pass --force",
                    active.id, active.app_id, active.state
                ),
                active.raw.clone(),
            ));
        }
        if kept && args.wait.is_some() {
            return Err(CliError::app_with(
                SESSION_BUSY,
                format!(
                    "session {} is {}: the app keeps it and starts no new session, so --wait would \
                     never finish; stop it first",
                    active.id, active.state
                ),
                active.raw.clone(),
            ));
        }
        if kept {
            warnings.push(Value::String("active_session_kept".into()));
        }
    }

    am_start(
        ctx,
        &am_start_args(ctx.package, &app, args.debug, channels.as_deref()),
    )?;

    let mut result = json!({ "app_id": app.to_string(), "agent_debug": args.debug });
    if let Some(channels) = &channels {
        result["wine_debug_channels"] = json!(channels);
    }
    if let Some(previous) = &previous {
        result["previous_session_id"] = json!(previous.id);
    }
    if !warnings.is_empty() {
        result["warnings"] = Value::Array(warnings);
    }
    if let Some(until) = args.wait {
        let target = Target::New {
            previous: previous.map(|s| s.id),
            app_id: app.to_string(),
        };
        result["session"] = wait(ctx, target, until, secs(args.timeout))?;
    }
    Ok(result)
}

/// `sessions wait`: follows the current session (active or last finished) until [until].
pub fn wait_current(ctx: &Ctx, until: WaitUntil, timeout: Duration) -> Result<Value, CliError> {
    let Some(session) = current_when_ready(ctx)? else {
        return Err(CliError::app(
            NO_ACTIVE_SESSION,
            "there is no session to wait for",
        ));
    };
    wait(ctx, Target::Known(session.id), until, timeout)
}

enum Target {
    /// A session that already exists.
    Known(String),
    /// The session a launch creates: the first one for [app_id] whose id is not [previous].
    New {
        previous: Option<String>,
        app_id: String,
    },
}

/// Polls `sessions.current` until the target session reaches [until]; returns its JSON.
///
/// Waiting for `running`, a session that is `exiting` or `exited` → `SESSION_EXITED` (exit 4). Once
/// the target was seen, another (or no) current session → `SESSION_VANISHED` (exit 4). `NOT_READY`
/// answers are skipped. Out of time → timeout (exit 5).
fn wait(ctx: &Ctx, target: Target, until: WaitUntil, timeout: Duration) -> Result<Value, CliError> {
    let deadline = ctx.clock.now().saturating_add(timeout);
    let (mut ours, previous, app_id) = match target {
        Target::Known(id) => (Some(id), None, None),
        Target::New { previous, app_id } => (None, previous, Some(app_id)),
    };
    let mut last_state: Option<String> = None;
    loop {
        if let Some(current) = poll(ctx)? {
            if ours.is_none() {
                ours = current
                    .as_ref()
                    .filter(|s| {
                        Some(&s.id) != previous.as_ref() && Some(&s.app_id) == app_id.as_ref()
                    })
                    .map(|s| s.id.clone());
            }
            if let Some(id) = &ours {
                match current {
                    Some(session) if &session.id == id => {
                        if session.state == until.state() {
                            return Ok(session.raw);
                        }
                        if until == WaitUntil::Running
                            && (session.state == STATE_EXITING || session.state == STATE_EXITED)
                        {
                            return Err(exited_early(session));
                        }
                        last_state = Some(session.state);
                    }
                    other => return Err(vanished(id, other)),
                }
            }
        }
        let now = ctx.clock.now();
        if now >= deadline {
            let secs = timeout.as_secs();
            return Err(CliError::Timeout(match (&ours, &app_id) {
                (Some(id), _) => format!(
                    "session {id} did not reach {} within {secs}s (state: {})",
                    until.state(),
                    last_state.as_deref().unwrap_or("unknown")
                ),
                (None, Some(app_id)) => format!(
                    "no new session for {app_id} appeared within {secs}s \
                     (game not installed, or the launch did not start)"
                ),
                (None, None) => format!("nothing happened within {secs}s"),
            }));
        }
        ctx.clock.sleep(POLL_INTERVAL.min(deadline - now));
    }
}

fn exited_early(session: Session) -> CliError {
    CliError::app_with(
        SESSION_EXITED,
        format!(
            "session {} is {} before running (exit_reason: {}, last_launch_error: {})",
            session.id,
            session.state,
            session.text("exit_reason"),
            session.text("last_launch_error")
        ),
        session.raw,
    )
}

fn vanished(id: &str, current: Option<Session>) -> CliError {
    let current = current.map_or(Value::Null, |s| s.raw);
    CliError::app_with(
        SESSION_VANISHED,
        format!("session {id} is no longer the current session (app restarted or another launch)"),
        json!({ "session_id": id, "current": current }),
    )
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;
    use crate::adb::testing::FakeAdb;
    use crate::test_support::{bundle_err, bundle_ok, decoded_request, run_cli, FakeClock};

    fn session(id: &str, state: &str) -> Value {
        json!({"session_id": id, "app_id": "STEAM_123", "state": state, "warnings": [], "agent_debug": true})
    }

    fn is_content_call(args: &[String]) -> bool {
        args.get(1).map(String::as_str) == Some("content")
    }

    fn am_calls(fake: &FakeAdb) -> Vec<Vec<String>> {
        fake.calls()
            .into_iter()
            .filter(|c| c.get(1).map(String::as_str) == Some("am"))
            .collect()
    }

    const STARTING: &str = "Starting: Intent { act=app.gamenative.LAUNCH_GAME }\n";

    #[test]
    fn launch_sends_the_intent() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(Value::Null));
        fake.push_ok(STARTING);
        let (code, out) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
        assert_eq!(code, 0, "{out}");
        assert_eq!(out, json!({"app_id": "STEAM_123", "agent_debug": false}));
        let calls = fake.calls();
        assert!(is_content_call(&calls[0]));
        assert_eq!(decoded_request(&calls[0])["command"], "sessions.current");
        assert_eq!(
            calls[1],
            [
                "shell",
                "am",
                "start",
                "-n",
                "app.gamenative/app.gamenative.MainActivity",
                "-a",
                "app.gamenative.LAUNCH_GAME",
                "--ei",
                "app_id",
                "123",
                "--es",
                "game_source",
                "STEAM"
            ]
        );
    }

    #[test]
    fn launch_numeric_id_with_source_debug_and_channels() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("old", "exited")));
        fake.push_ok(STARTING);
        let (code, out) = run_cli(
            &[
                "--package",
                "app.gamenative.gold",
                "launch",
                "77",
                "--source",
                "gog",
                "--debug",
                "--wine-debug",
                "d3d, -FPS",
            ],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["app_id"], "GOG_77");
        assert_eq!(out["wine_debug_channels"], "+d3d,-fps");
        assert_eq!(out["previous_session_id"], "old");
        let am = &am_calls(&fake)[0];
        assert_eq!(am[4], "app.gamenative.gold/app.gamenative.MainActivity");
        assert_eq!(
            am[7..],
            [
                "--ei",
                "app_id",
                "77",
                "--es",
                "game_source",
                "GOG",
                "--ez",
                "agent_debug",
                "true",
                "--es",
                "wine_debug_channels",
                "+d3d,-fps"
            ]
        );
    }

    #[test]
    fn rejected_channels_exit_3_without_adb() {
        for channels in [
            "+all",
            "relay,+d3d",
            "+seh",
            "a,b,c,d,e,f,g,h,i",
            "d3d;reboot",
        ] {
            let fake = FakeAdb::new();
            let (code, out) = run_cli(
                &["launch", "STEAM_1", "--debug", "--wine-debug", channels],
                &fake,
                &FakeClock::default(),
            );
            assert_eq!(code, 3, "{channels}");
            assert_eq!(out["error"]["code"], "USAGE_ERROR");
            assert!(fake.calls().is_empty(), "{channels}");
        }
    }

    #[test]
    fn launch_flag_dependencies_are_usage_errors() {
        for argv in [
            vec!["launch", "STEAM_1", "--wine-debug", "+d3d"],
            vec!["launch", "STEAM_1", "--timeout", "5"],
            vec!["launch", "STEAM_1", "--wait", "soon"],
            vec!["launch", "1"],
        ] {
            let fake = FakeAdb::new();
            let (code, _) = run_cli(&argv, &fake, &FakeClock::default());
            assert_eq!(code, 3, "{argv:?}");
            assert!(fake.calls().is_empty());
        }
    }

    #[test]
    fn active_session_is_busy_without_force() {
        for state in ["launching", "starting", "running", "exiting"] {
            let fake = FakeAdb::new();
            fake.push_ok(bundle_ok(session("s1", state)));
            let (code, out) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
            assert_eq!(code, 4, "{state}");
            assert_eq!(out["error"]["code"], SESSION_BUSY);
            assert_eq!(out["error"]["details"]["session_id"], "s1");
            assert!(am_calls(&fake).is_empty());
        }
    }

    #[test]
    fn force_launches_over_an_active_session() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("s1", "running")));
        fake.push_ok(STARTING);
        let (code, out) = run_cli(
            &["launch", "STEAM_123", "--force"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["warnings"], json!(["active_session_kept"]));
        assert_eq!(am_calls(&fake).len(), 1);
    }

    #[test]
    fn force_wait_over_a_kept_session_is_refused() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("s1", "starting")));
        let (code, out) = run_cli(
            &["launch", "STEAM_123", "--force", "--wait", "running"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 4);
        assert_eq!(out["error"]["code"], SESSION_BUSY);
        assert!(am_calls(&fake).is_empty());
    }

    #[test]
    fn force_wait_replaces_a_launching_session() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("stuck", "launching")));
        fake.push_ok(STARTING);
        fake.push_ok(bundle_ok(session("stuck", "launching")));
        fake.push_ok(bundle_ok(session("new", "running")));
        let (code, out) = run_cli(
            &["launch", "STEAM_123", "--force", "--wait", "running"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["session"]["session_id"], "new");
    }

    #[test]
    fn wait_running_follows_the_new_session_id() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("old", "exited")));
        fake.push_ok(STARTING);
        fake.push_ok(bundle_ok(session("old", "exited")));
        fake.push_ok(bundle_err("NOT_READY"));
        // A session for another game is not ours.
        fake.push_ok(bundle_ok(
            json!({"session_id": "other", "app_id": "STEAM_9", "state": "running"}),
        ));
        fake.push_ok(bundle_ok(session("new", "launching")));
        fake.push_ok(bundle_ok(session("new", "starting")));
        fake.push_ok(bundle_ok(session("new", "running")));
        let clock = FakeClock::default();
        let (code, out) = run_cli(
            &[
                "launch",
                "STEAM_123",
                "--debug",
                "--wait",
                "running",
                "--timeout",
                "60",
            ],
            &fake,
            &clock,
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["session"]["session_id"], "new");
        assert_eq!(out["session"]["state"], "running");
        assert_eq!(out["previous_session_id"], "old");
        assert_eq!(clock.sleeps(), 5);
    }

    #[test]
    fn wait_exited_returns_the_finished_session() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(Value::Null));
        fake.push_ok(STARTING);
        fake.push_ok(bundle_ok(session("new", "running")));
        let mut done = session("new", "exited");
        done["exit_reason"] = json!("guest_terminated");
        fake.push_ok(bundle_ok(done));
        let (code, out) = run_cli(
            &["launch", "STEAM_123", "--wait", "exited"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["session"]["exit_reason"], "guest_terminated");
    }

    #[test]
    fn early_exit_while_waiting_for_running_is_4() {
        for state in ["exiting", "exited"] {
            let fake = FakeAdb::new();
            fake.push_ok(bundle_ok(Value::Null));
            fake.push_ok(STARTING);
            let mut ended = session("new", state);
            ended["exit_reason"] = json!("launch_error");
            ended["last_launch_error"] = json!("Failed to setup wine: boom");
            fake.push_ok(bundle_ok(ended));
            let (code, out) = run_cli(
                &["launch", "STEAM_123", "--wait", "running"],
                &fake,
                &FakeClock::default(),
            );
            assert_eq!(code, 4, "{state}");
            assert_eq!(out["error"]["code"], SESSION_EXITED);
            let message = out["error"]["message"].as_str().unwrap();
            assert!(
                message.contains("launch_error") && message.contains("boom"),
                "{message}"
            );
            assert_eq!(out["error"]["details"]["session_id"], "new");
        }
    }

    #[test]
    fn vanished_session_is_4() {
        for replacement in [Value::Null, session("other", "starting")] {
            let fake = FakeAdb::new();
            fake.push_ok(bundle_ok(Value::Null));
            fake.push_ok(STARTING);
            fake.push_ok(bundle_ok(session("new", "starting")));
            fake.push_ok(bundle_ok(replacement.clone()));
            let (code, out) = run_cli(
                &["launch", "STEAM_123", "--wait", "running"],
                &fake,
                &FakeClock::default(),
            );
            assert_eq!(code, 4, "{replacement}");
            assert_eq!(out["error"]["code"], SESSION_VANISHED);
            assert_eq!(out["error"]["details"]["session_id"], "new");
        }
    }

    #[test]
    fn timeout_is_5() {
        // No new session ever appears: 0s, 0.5s, 1s, then the deadline.
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("old", "exited")));
        fake.push_ok(STARTING);
        for _ in 0..3 {
            fake.push_ok(bundle_ok(session("old", "exited")));
        }
        let clock = FakeClock::default();
        let (code, out) = run_cli(
            &["launch", "STEAM_123", "--wait", "running", "--timeout", "1"],
            &fake,
            &clock,
        );
        assert_eq!(code, 5, "{out}");
        assert_eq!(out["error"]["code"], "TIMEOUT");
        assert!(out["error"]["message"]
            .as_str()
            .unwrap()
            .contains("no new session for STEAM_123"));
        assert_eq!(clock.now(), Duration::from_secs(1));

        // The session exists but stays `starting`.
        let fake = FakeAdb::new();
        for _ in 0..4 {
            fake.push_ok(bundle_ok(session("s1", "starting")));
        }
        let (code, out) = run_cli(
            &["sessions", "wait", "--until", "running", "--timeout", "1"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 5, "{out}");
        assert!(out["error"]["message"]
            .as_str()
            .unwrap()
            .contains("state: starting"));
    }

    #[test]
    fn sessions_wait_until_exited() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(session("s1", "running")));
        fake.push_ok(bundle_ok(session("s1", "running")));
        fake.push_ok(bundle_ok(session("s1", "exiting")));
        fake.push_ok(bundle_ok(session("s1", "exited")));
        let (code, out) = run_cli(
            &["sessions", "wait", "--until", "exited"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0, "{out}");
        assert_eq!(out["state"], "exited");
    }

    #[test]
    fn sessions_wait_without_session_is_4() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(Value::Null));
        let (code, out) = run_cli(
            &["sessions", "wait", "--until", "running"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 4);
        assert_eq!(out["error"]["code"], NO_ACTIVE_SESSION);
    }

    #[test]
    fn not_ready_before_launch_is_retried_then_reported() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_err("NOT_READY"));
        fake.push_ok(bundle_ok(Value::Null));
        fake.push_ok(STARTING);
        let (code, _) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
        assert_eq!(code, 0);

        let fake = FakeAdb::new();
        // 0s .. 15s every 0.5s = 31 answers.
        for _ in 0..31 {
            fake.push_ok(bundle_err("NOT_READY"));
        }
        let (code, out) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
        assert_eq!(code, 4);
        assert_eq!(out["error"]["code"], NOT_READY);
        assert!(am_calls(&fake).is_empty());
    }

    #[test]
    fn disabled_api_stops_the_launch() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_err("DISABLED"));
        let (code, out) = run_cli(
            &["launch", "STEAM_123", "--debug"],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 4);
        assert_eq!(out["error"]["code"], "DISABLED");
        assert!(am_calls(&fake).is_empty());
    }

    #[test]
    fn am_start_errors_are_adb_errors() {
        for (status, stdout) in [
            (0, "Starting: Intent { }\nError: Activity class {app.gamenative/app.gamenative.MainActivity} does not exist.\n"),
            (0, "Exception occurred while executing 'start':\njava.lang.SecurityException: x\n"),
            (255, ""),
        ] {
            let fake = FakeAdb::new();
            fake.push_ok(bundle_ok(Value::Null));
            fake.push_status(status, stdout, "");
            let (code, out) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
            assert_eq!(code, 2, "{stdout}");
            assert_eq!(out["error"]["code"], "ADB_ERROR");
        }
        // Warm path: the intent goes to the running activity; not an error.
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(Value::Null));
        fake.push_ok("Starting: Intent { }\nWarning: Activity not started, intent has been delivered to currently running top-most instance.\n");
        let (code, _) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
        assert_eq!(code, 0);
    }

    #[test]
    fn malformed_session_is_protocol_error() {
        let fake = FakeAdb::new();
        fake.push_ok(bundle_ok(json!({"state": "running"})));
        let (code, _) = run_cli(&["launch", "STEAM_123"], &fake, &FakeClock::default());
        assert_eq!(code, 3);
    }
}
