//! gnctl: host-side CLI for the GameNative agent ADB API.
//!
//! Output is JSON on stdout. Exit codes: 0 ok, 2 adb, 3 protocol/usage, 4 app error, 5 timeout.
//! The binary (`main.rs`) only calls [`run_main`]; everything else lives here so it can be tested.

pub mod adb;
pub mod error;
pub mod protocol;

use std::ffi::OsString;
use std::io::Write;

use clap::{Parser, Subcommand};
use serde_json::{Map, Value};

use crate::adb::{AdbRunner, SystemAdb};
use crate::error::{CliError, EXIT_OK};

#[derive(Debug, Parser)]
#[command(
    name = "gnctl",
    version,
    about = "Control GameNative on an Android device over ADB"
)]
pub struct Cli {
    /// Device serial (default: $ANDROID_SERIAL, else adb's only device)
    #[arg(long, global = true)]
    serial: Option<String>,
    /// Path to the adb binary
    #[arg(long, global = true, default_value = "adb")]
    adb: String,
    /// GameNative package name (the provider authority is <package>.agent)
    #[arg(long, global = true, default_value = protocol::DEFAULT_PACKAGE)]
    package: String,
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Send a raw agent API command and print its data
    Call {
        /// Command name, e.g. games.list
        command: String,
        /// Command arguments as a JSON object
        #[arg(long, default_value = "{}")]
        args: String,
    },
}

/// Entry point of the binary: real argv, real stdout, real adb. Returns the exit code.
pub fn run_main() -> i32 {
    run(std::env::args_os(), &mut std::io::stdout(), |cli| {
        Box::new(SystemAdb::from_options(cli.adb.clone(), cli.serial.clone()))
    })
}

/// Parses arguments, runs the command and prints the JSON result; returns the exit code.
pub fn run<I, W, F>(argv: I, out: &mut W, make_runner: F) -> i32
where
    I: IntoIterator,
    I::Item: Into<OsString> + Clone,
    W: Write,
    F: FnOnce(&Cli) -> Box<dyn AdbRunner>,
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
    report(out, execute(&cli, runner.as_ref()))
}

fn execute(cli: &Cli, runner: &dyn AdbRunner) -> Result<Value, CliError> {
    match &cli.command {
        Command::Call { command, args } => {
            let args = parse_args_object(args)?;
            protocol::call(runner, &cli.package, command, &args)
        }
    }
}

fn parse_args_object(text: &str) -> Result<Map<String, Value>, CliError> {
    match serde_json::from_str::<Value>(text) {
        Ok(Value::Object(map)) => Ok(map),
        Ok(_) => Err(CliError::Usage("--args must be a JSON object".into())),
        Err(e) => Err(CliError::Usage(format!("--args is not valid JSON: {e}"))),
    }
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
mod tests {
    use std::rc::Rc;

    use serde_json::json;

    use super::*;
    use crate::adb::testing::FakeAdb;
    use crate::adb::AdbOutput;

    /// Lets a test keep a handle on the fake after handing it to `run`.
    struct Shared(Rc<FakeAdb>);

    impl AdbRunner for Shared {
        fn run(&self, args: &[String]) -> Result<AdbOutput, CliError> {
            self.0.run(args)
        }
    }

    fn run_with(argv: &[&str], fake: &Rc<FakeAdb>) -> (i32, String) {
        let mut out = Vec::new();
        let fake = Rc::clone(fake);
        let code = run(
            std::iter::once("gnctl").chain(argv.iter().copied()),
            &mut out,
            move |_| Box::new(Shared(fake)),
        );
        (code, String::from_utf8(out).unwrap())
    }

    #[test]
    fn call_prints_data() {
        let fake = Rc::new(FakeAdb::new());
        fake.push_ok(r#"Result: Bundle[{json={"api_version":1,"ok":true,"data":{"package":"app.gamenative"}}}]"#);
        let (code, out) = run_with(
            &[
                "--package",
                "app.gamenative",
                "call",
                "capabilities",
                "--args",
                r#"{"x":1}"#,
            ],
            &fake,
        );
        assert_eq!(code, 0);
        assert_eq!(
            serde_json::from_str::<Value>(&out).unwrap(),
            json!({"package": "app.gamenative"})
        );
        assert_eq!(fake.calls()[0][4], "content://app.gamenative.agent");
    }

    #[test]
    fn app_error_prints_json_and_exits_4() {
        let fake = Rc::new(FakeAdb::new());
        fake.push_ok(r#"Result: Bundle[{json={"api_version":1,"ok":false,"error":{"code":"DISABLED","message":"off"}}}]"#);
        let (code, out) = run_with(&["call", "games.list"], &fake);
        assert_eq!(code, 4);
        assert_eq!(
            serde_json::from_str::<Value>(&out).unwrap(),
            json!({"ok": false, "error": {"code": "DISABLED", "message": "off"}})
        );
    }

    #[test]
    fn bad_args_json_is_usage_error_without_adb() {
        let fake = Rc::new(FakeAdb::new());
        for bad in ["{", "[1]"] {
            let (code, out) = run_with(&["call", "games.list", "--args", bad], &fake);
            assert_eq!(code, 3);
            let v: Value = serde_json::from_str(&out).unwrap();
            assert_eq!(v["error"]["code"], "USAGE_ERROR");
        }
        assert!(fake.calls().is_empty());
    }

    #[test]
    fn clap_errors_exit_3_not_2() {
        let fake = Rc::new(FakeAdb::new());
        let (code, out) = run_with(&["no-such-command"], &fake);
        assert_eq!(code, 3);
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["error"]["code"], "USAGE_ERROR");
    }

    #[test]
    fn help_exits_0() {
        let fake = Rc::new(FakeAdb::new());
        let (code, out) = run_with(&["--help"], &fake);
        assert_eq!(code, 0);
        assert!(out.contains("Usage: gnctl"));
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
