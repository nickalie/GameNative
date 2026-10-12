//! Running adb. Everything that talks to the device goes through [`AdbRunner`] so tests can fake it.

use std::process::{Command, Stdio};

use crate::error::CliError;

/// Raw result of one adb invocation. `stdout` is kept as bytes so binary output survives.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AdbOutput {
    /// Exit status; `None` when the process was killed by a signal.
    pub status: Option<i32>,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}

impl AdbOutput {
    pub fn success(&self) -> bool {
        self.status == Some(0)
    }

    pub fn stdout_text(&self) -> String {
        String::from_utf8_lossy(&self.stdout).into_owned()
    }

    pub fn stderr_text(&self) -> String {
        String::from_utf8_lossy(&self.stderr).into_owned()
    }
}

pub trait AdbRunner {
    /// Runs `adb <args>` against the selected device. Failing to start adb is an adb error;
    /// a non-zero exit status is returned in [`AdbOutput`] for the caller to judge.
    fn run(&self, args: &[String]) -> Result<AdbOutput, CliError>;
}

impl<T: AdbRunner + ?Sized> AdbRunner for &T {
    fn run(&self, args: &[String]) -> Result<AdbOutput, CliError> {
        (**self).run(args)
    }
}

/// The real adb binary, optionally pinned to one device serial.
#[derive(Debug, Clone)]
pub struct SystemAdb {
    adb: String,
    serial: Option<String>,
}

impl SystemAdb {
    pub fn new(adb: impl Into<String>, serial: Option<String>) -> Self {
        SystemAdb {
            adb: adb.into(),
            serial,
        }
    }

    /// Builds from CLI options: `--serial` wins over `ANDROID_SERIAL`.
    pub fn from_options(adb: impl Into<String>, serial_flag: Option<String>) -> Self {
        let env = std::env::var("ANDROID_SERIAL").ok();
        SystemAdb::new(adb, pick_serial(serial_flag, env))
    }

    /// Full argument list passed to the adb binary (device selection first).
    pub fn command_args(&self, args: &[String]) -> Vec<String> {
        let mut full = Vec::with_capacity(args.len() + 2);
        if let Some(serial) = &self.serial {
            full.push("-s".to_string());
            full.push(serial.clone());
        }
        full.extend(args.iter().cloned());
        full
    }
}

impl AdbRunner for SystemAdb {
    fn run(&self, args: &[String]) -> Result<AdbOutput, CliError> {
        let output = Command::new(&self.adb)
            .args(self.command_args(args))
            .stdin(Stdio::null())
            .output()
            .map_err(|e| CliError::Adb(format!("failed to run '{}': {e}", self.adb)))?;
        Ok(AdbOutput {
            status: output.status.code(),
            stdout: output.stdout,
            stderr: output.stderr,
        })
    }
}

/// `--serial` if given, else a non-empty `ANDROID_SERIAL`, else let adb pick the only device.
pub fn pick_serial(flag: Option<String>, env: Option<String>) -> Option<String> {
    flag.filter(|s| !s.trim().is_empty())
        .or_else(|| env.filter(|s| !s.trim().is_empty()))
}

fn to_strings(args: &[&str]) -> Vec<String> {
    args.iter().map(|s| s.to_string()).collect()
}

/// Runs adb and requires a zero exit status; returns stdout bytes.
pub fn run_checked(runner: &dyn AdbRunner, args: &[String]) -> Result<Vec<u8>, CliError> {
    let out = runner.run(args)?;
    if out.success() {
        return Ok(out.stdout);
    }
    let status = out
        .status
        .map_or_else(|| "signal".to_string(), |c| c.to_string());
    let detail = first_non_empty(&out.stderr_text(), &out.stdout_text());
    let what = args.first().map(String::as_str).unwrap_or("");
    Err(CliError::Adb(format!(
        "adb {what} failed (exit {status}){}",
        if detail.is_empty() {
            String::new()
        } else {
            format!(": {detail}")
        }
    )))
}

/// `adb shell <cmd...>`; the exit status is left to the caller.
pub fn shell(runner: &dyn AdbRunner, cmd: &[&str]) -> Result<AdbOutput, CliError> {
    let mut args = vec!["shell".to_string()];
    args.extend(to_strings(cmd));
    runner.run(&args)
}

/// Binary-safe `adb exec-out <cmd...>` (no pty, no newline translation).
pub fn exec_out(runner: &dyn AdbRunner, cmd: &[&str]) -> Result<Vec<u8>, CliError> {
    let mut args = vec!["exec-out".to_string()];
    args.extend(to_strings(cmd));
    run_checked(runner, &args)
}

fn first_non_empty(a: &str, b: &str) -> String {
    let a = a.trim();
    if a.is_empty() {
        truncate(b.trim(), 500)
    } else {
        truncate(a, 500)
    }
}

/// Shortens text for error messages without splitting a UTF-8 character.
pub fn truncate(text: &str, max_chars: usize) -> String {
    if text.chars().count() <= max_chars {
        text.to_string()
    } else {
        let cut: String = text.chars().take(max_chars).collect();
        format!("{cut}...")
    }
}

#[cfg(test)]
pub mod testing {
    use std::cell::RefCell;
    use std::collections::VecDeque;

    use super::{AdbOutput, AdbRunner};
    use crate::error::CliError;

    /// Fake adb: records every argument list and replays queued results in order.
    #[derive(Default)]
    pub struct FakeAdb {
        pub calls: RefCell<Vec<Vec<String>>>,
        responses: RefCell<VecDeque<Result<AdbOutput, CliError>>>,
    }

    impl FakeAdb {
        pub fn new() -> Self {
            FakeAdb::default()
        }

        pub fn push(&self, result: Result<AdbOutput, CliError>) -> &Self {
            self.responses.borrow_mut().push_back(result);
            self
        }

        pub fn push_ok(&self, stdout: impl Into<Vec<u8>>) -> &Self {
            self.push(Ok(AdbOutput {
                status: Some(0),
                stdout: stdout.into(),
                stderr: Vec::new(),
            }))
        }

        pub fn push_status(&self, status: i32, stdout: &str, stderr: &str) -> &Self {
            self.push(Ok(AdbOutput {
                status: Some(status),
                stdout: stdout.as_bytes().to_vec(),
                stderr: stderr.as_bytes().to_vec(),
            }))
        }

        pub fn calls(&self) -> Vec<Vec<String>> {
            self.calls.borrow().clone()
        }
    }

    impl AdbRunner for FakeAdb {
        fn run(&self, args: &[String]) -> Result<AdbOutput, CliError> {
            self.calls.borrow_mut().push(args.to_vec());
            self.responses
                .borrow_mut()
                .pop_front()
                .unwrap_or_else(|| panic!("unexpected adb call: {args:?}"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::testing::FakeAdb;
    use super::*;

    fn s(args: &[&str]) -> Vec<String> {
        to_strings(args)
    }

    #[test]
    fn command_args_prepend_serial() {
        let adb = SystemAdb::new("adb", Some("R5CX123".into()));
        assert_eq!(
            adb.command_args(&s(&["shell", "echo"])),
            s(&["-s", "R5CX123", "shell", "echo"])
        );
        let adb = SystemAdb::new("adb", None);
        assert_eq!(adb.command_args(&s(&["devices"])), s(&["devices"]));
    }

    #[test]
    fn serial_flag_wins_over_env() {
        assert_eq!(
            pick_serial(Some("a".into()), Some("b".into())),
            Some("a".into())
        );
        assert_eq!(pick_serial(None, Some("b".into())), Some("b".into()));
        assert_eq!(
            pick_serial(Some(" ".into()), Some("b".into())),
            Some("b".into())
        );
        assert_eq!(pick_serial(None, Some(String::new())), None);
        assert_eq!(pick_serial(None, None), None);
    }

    #[test]
    fn missing_adb_binary_is_adb_error() {
        let adb = SystemAdb::new("/nonexistent/gnctl-test-adb", None);
        let err = adb.run(&s(&["devices"])).unwrap_err();
        assert_eq!(err.exit_code(), 2);
        assert!(err.message().contains("/nonexistent/gnctl-test-adb"));
    }

    #[test]
    fn run_checked_reports_stderr_on_failure() {
        let fake = FakeAdb::new();
        fake.push_status(1, "", "error: no devices/emulators found\n");
        let err = run_checked(&fake, &s(&["exec-out", "screencap", "-p"])).unwrap_err();
        assert_eq!(err.exit_code(), 2);
        assert_eq!(
            err.message(),
            "adb exec-out failed (exit 1): error: no devices/emulators found"
        );
    }

    #[test]
    fn run_checked_falls_back_to_stdout_and_handles_signal() {
        let fake = FakeAdb::new();
        fake.push(Ok(AdbOutput {
            status: None,
            stdout: b"boom".to_vec(),
            stderr: Vec::new(),
        }));
        let err = run_checked(&fake, &s(&["pull", "x"])).unwrap_err();
        assert_eq!(err.message(), "adb pull failed (exit signal): boom");
    }

    #[test]
    fn exec_out_is_binary_safe() {
        let png: Vec<u8> = vec![
            0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0xff,
        ];
        let fake = FakeAdb::new();
        fake.push_ok(png.clone());
        let bytes = exec_out(&fake, &["screencap", "-p"]).unwrap();
        assert_eq!(bytes, png);
        assert_eq!(fake.calls(), vec![s(&["exec-out", "screencap", "-p"])]);
    }

    #[test]
    fn shell_prefixes_and_keeps_status() {
        let fake = FakeAdb::new();
        fake.push_status(3, "out", "err");
        let out = shell(&fake, &["input", "keyevent", "BACK"]).unwrap();
        assert_eq!(out.status, Some(3));
        assert_eq!(
            fake.calls(),
            vec![s(&["shell", "input", "keyevent", "BACK"])]
        );
    }

    #[test]
    fn truncate_respects_char_boundaries() {
        assert_eq!(truncate("абв", 2), "аб...");
        assert_eq!(truncate("abc", 3), "abc");
    }
}
