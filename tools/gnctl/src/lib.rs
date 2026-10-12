//! gnctl: host-side CLI for the GameNative agent ADB API.
//!
//! Output is JSON on stdout. Exit codes: 0 ok, 2 adb, 3 protocol/usage, 4 app error, 5 timeout.
//! The binary (`main.rs`) only calls [`run_main`]; everything else lives here so it can be tested.

pub mod adb;
pub mod channels;
pub mod cli;
pub mod commands;
pub mod error;
pub mod launch;
pub mod protocol;

use std::ffi::OsString;
use std::io::{Read, Write};

use clap::Parser;
use serde_json::Value;

use crate::adb::{AdbRunner, SystemAdb};
use crate::cli::Cli;
use crate::commands::Ctx;
use crate::error::{CliError, EXIT_OK};
use crate::launch::{Clock, SystemClock};

/// Entry point of the binary: real argv, stdout, stdin, clock and adb. Returns the exit code.
pub fn run_main() -> i32 {
    let clock = SystemClock::new();
    let stdin = std::io::stdin();
    let mut stdin = stdin.lock();
    run(
        std::env::args_os(),
        &mut std::io::stdout(),
        &mut stdin,
        &clock,
        |cli| Box::new(SystemAdb::from_options(cli.adb.clone(), cli.serial.clone())),
    )
}

/// Parses arguments, runs the command and prints the JSON result; returns the exit code.
pub fn run<'r, I, W, F>(
    argv: I,
    out: &mut W,
    stdin: &mut dyn Read,
    clock: &dyn Clock,
    make_runner: F,
) -> i32
where
    I: IntoIterator,
    I::Item: Into<OsString> + Clone,
    W: Write,
    F: FnOnce(&Cli) -> Box<dyn AdbRunner + 'r>,
{
    let cli = match Cli::try_parse_from(argv) {
        Ok(cli) => cli,
        Err(e) if !e.use_stderr() => {
            // --help / --version
            let _ = write!(out, "{e}");
            return EXIT_OK;
        }
        Err(e) => return report(out, Err(CliError::Usage(e.to_string().trim().to_string()))),
    };
    let runner = make_runner(&cli);
    let mut ctx = Ctx {
        runner: runner.as_ref(),
        package: &cli.package,
        stdin,
        clock,
    };
    report(out, commands::execute(&cli, &mut ctx))
}

fn report<W: Write>(out: &mut W, result: Result<Value, CliError>) -> i32 {
    let (value, code) = match result {
        Ok(data) => (data, EXIT_OK),
        Err(err) => (err.to_json(), err.exit_code()),
    };
    let text = serde_json::to_string_pretty(&value).expect("JSON value serializes");
    let _ = writeln!(out, "{text}");
    code
}

#[cfg(test)]
pub(crate) mod test_support {
    use std::cell::Cell;
    use std::time::Duration;

    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    use base64::Engine;
    use serde_json::{json, Value};

    use crate::adb::AdbRunner;
    use crate::launch::Clock;

    /// Clock whose `sleep` only advances `now`.
    #[derive(Default)]
    pub struct FakeClock {
        now: Cell<Duration>,
        sleeps: Cell<usize>,
    }

    impl FakeClock {
        pub fn sleeps(&self) -> usize {
            self.sleeps.get()
        }
    }

    impl Clock for FakeClock {
        fn now(&self) -> Duration {
            self.now.get()
        }

        fn sleep(&self, duration: Duration) {
            self.now.set(self.now.get() + duration);
            self.sleeps.set(self.sleeps.get() + 1);
        }
    }

    /// `content call` output of a successful response with [data].
    pub fn bundle_ok(data: Value) -> String {
        let json = json!({"api_version": 1, "ok": true, "data": data});
        format!("Result: Bundle[{{json={json}}}]\n")
    }

    /// `content call` output of an app error with [code].
    pub fn bundle_err(code: &str) -> String {
        let json = json!({"api_version": 1, "ok": false, "error": {"code": code, "message": code}});
        format!("Result: Bundle[{{json={json}}}]\n")
    }

    /// The request JSON of a recorded `content call`.
    pub fn decoded_request(call: &[String]) -> Value {
        assert_eq!(call[1], "content", "not a content call: {call:?}");
        serde_json::from_slice(&URL_SAFE_NO_PAD.decode(&call[8]).unwrap()).unwrap()
    }

    /// Runs gnctl with [argv] (without the program name); returns the exit code and the stdout JSON.
    pub fn run_cli_runner(
        argv: &[&str],
        runner: &dyn AdbRunner,
        clock: &FakeClock,
        stdin: &[u8],
    ) -> (i32, Value) {
        let mut out = Vec::new();
        let mut stdin = stdin;
        let code = crate::run(
            std::iter::once("gnctl").chain(argv.iter().copied()),
            &mut out,
            &mut stdin,
            clock,
            |_| Box::new(runner),
        );
        let text = String::from_utf8(out).unwrap();
        let value = serde_json::from_str(&text)
            .unwrap_or_else(|e| panic!("stdout is not JSON ({e}): {text}"));
        (code, value)
    }

    pub fn run_cli(argv: &[&str], runner: &dyn AdbRunner, clock: &FakeClock) -> (i32, Value) {
        run_cli_runner(argv, runner, clock, b"")
    }

    pub fn run_cli_stdin(
        argv: &[&str],
        runner: &dyn AdbRunner,
        clock: &FakeClock,
        stdin: &[u8],
    ) -> (i32, Value) {
        run_cli_runner(argv, runner, clock, stdin)
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;
    use crate::adb::testing::FakeAdb;
    use crate::test_support::{run_cli, FakeClock};

    #[test]
    fn call_prints_data() {
        let fake = FakeAdb::new();
        fake.push_ok(r#"Result: Bundle[{json={"api_version":1,"ok":true,"data":{"package":"app.gamenative"}}}]"#);
        let (code, out) = run_cli(
            &[
                "--package",
                "app.gamenative",
                "call",
                "capabilities",
                "--args",
                r#"{"x":1}"#,
            ],
            &fake,
            &FakeClock::default(),
        );
        assert_eq!(code, 0);
        assert_eq!(out, json!({"package": "app.gamenative"}));
        assert_eq!(fake.calls()[0][4], "content://app.gamenative.agent");
    }

    #[test]
    fn app_error_prints_json_and_exits_4() {
        let fake = FakeAdb::new();
        fake.push_ok(r#"Result: Bundle[{json={"api_version":1,"ok":false,"error":{"code":"DISABLED","message":"off"}}}]"#);
        let (code, out) = run_cli(&["call", "games.list"], &fake, &FakeClock::default());
        assert_eq!(code, 4);
        assert_eq!(
            out,
            json!({"ok": false, "error": {"code": "DISABLED", "message": "off"}})
        );
    }

    #[test]
    fn bad_args_json_is_usage_error_without_adb() {
        let fake = FakeAdb::new();
        for bad in ["{", "[1]"] {
            let (code, out) = run_cli(
                &["call", "games.list", "--args", bad],
                &fake,
                &FakeClock::default(),
            );
            assert_eq!(code, 3);
            assert_eq!(out["error"]["code"], "USAGE_ERROR");
        }
        assert!(fake.calls().is_empty());
    }

    #[test]
    fn clap_errors_exit_3_not_2() {
        let fake = FakeAdb::new();
        let (code, out) = run_cli(&["no-such-command"], &fake, &FakeClock::default());
        assert_eq!(code, 3);
        assert_eq!(out["error"]["code"], "USAGE_ERROR");
    }

    #[test]
    fn help_exits_0() {
        let fake = FakeAdb::new();
        let mut out = Vec::new();
        let code = run(
            ["gnctl", "--help"],
            &mut out,
            &mut &b""[..],
            &FakeClock::default(),
            |_| Box::new(&fake),
        );
        assert_eq!(code, 0);
        assert!(String::from_utf8(out).unwrap().contains("Usage: gnctl"));
    }

    #[test]
    fn serial_and_adb_options_parse() {
        let cli =
            Cli::try_parse_from(["gnctl", "call", "x", "--serial", "R5C", "--adb", "/opt/adb"])
                .unwrap();
        assert_eq!(cli.serial.as_deref(), Some("R5C"));
        assert_eq!(cli.adb, "/opt/adb");
        assert_eq!(cli.package, "app.gamenative");
    }
}
