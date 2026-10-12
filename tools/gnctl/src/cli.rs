//! Command-line surface of gnctl (clap definitions) and app id parsing.

use std::path::PathBuf;

use clap::{Args, Parser, Subcommand, ValueEnum};

use crate::error::CliError;
use crate::protocol;

#[derive(Debug, Parser)]
#[command(
    name = "gnctl",
    version,
    about = "Control GameNative on an Android device over ADB"
)]
pub struct Cli {
    /// Device serial (default: $ANDROID_SERIAL, else adb's only device)
    #[arg(long, global = true)]
    pub serial: Option<String>,
    /// Path to the adb binary
    #[arg(long, global = true, default_value = "adb")]
    pub adb: String,
    /// GameNative package name (the provider authority is <package>.agent)
    #[arg(long, global = true, default_value = protocol::DEFAULT_PACKAGE)]
    pub package: String,
    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// API version, app version, package and the commands the app supports
    Capabilities,
    /// Device information
    #[command(subcommand)]
    Device(DeviceCommand),
    /// Installed games
    #[command(subcommand)]
    Games(GamesCommand),
    /// Component versions usable in container configs
    #[command(subcommand)]
    Components(ComponentsCommand),
    /// Read and persistently change a game's container config
    #[command(subcommand)]
    Containers(ContainersCommand),
    /// The current (or last finished) game session
    #[command(subcommand)]
    Sessions(SessionsCommand),
    /// Launch a game through the LAUNCH_GAME intent
    Launch(LaunchArgs),
    /// Save a PNG screenshot of the device screen
    Screenshot {
        /// Output file
        #[arg(short = 'o', long = "output", value_name = "FILE")]
        output: PathBuf,
    },
    /// Session log files
    #[command(subcommand)]
    Logs(LogsCommand),
    /// Send a raw agent API command and print its data
    Call {
        /// Command name, e.g. games.list
        command: String,
        /// Command arguments as a JSON object
        #[arg(long, default_value = "{}")]
        args: String,
    },
}

#[derive(Debug, Subcommand)]
pub enum DeviceCommand {
    /// Model, Android version, SoC, GPU, Vulkan, free storage
    Info,
}

#[derive(Debug, Subcommand)]
pub enum GamesCommand {
    /// Installed games
    List {
        /// Only games from this source (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
        #[arg(long)]
        source: Option<String>,
    },
}

#[derive(Debug, Subcommand)]
pub enum ComponentsCommand {
    /// Installed and downloadable component versions
    List {
        /// Only this config key, e.g. wineVersion or dxwrapperConfig.version
        #[arg(long)]
        target: Option<String>,
    },
}

#[derive(Debug, Subcommand)]
pub enum ContainersCommand {
    /// Current config values, env (secrets masked) and read-only keys
    Get(GameArg),
    /// Apply {"values":{..},"env":{"set":{..},"unset":[..]}} from FILE or stdin (-); takes a backup first
    Set {
        #[command(flatten)]
        game: GameArg,
        /// JSON file with the changes, or - for stdin
        #[arg(value_name = "FILE|-")]
        file: String,
    },
    /// Roll the config back to a backup or to the baseline (first) backup
    Restore {
        #[command(flatten)]
        game: GameArg,
        /// Backup id from `containers backups`
        #[arg(
            long,
            value_name = "ID",
            conflicts_with = "baseline",
            required_unless_present = "baseline"
        )]
        backup: Option<String>,
        /// Restore the baseline backup taken before the first change
        #[arg(long)]
        baseline: bool,
    },
    /// List the config backups, oldest first
    Backups(GameArg),
}

#[derive(Debug, Subcommand)]
pub enum SessionsCommand {
    /// The active session, or the last finished one (null if none)
    Current,
    /// Stop the starting or running session
    Stop,
    /// Buffered (redacted) log lines of the session
    Logs {
        /// Number of newest lines (app default 200)
        #[arg(long)]
        tail: Option<u32>,
    },
    /// Wait until the current session is running or has exited
    Wait {
        #[arg(long, value_enum)]
        until: WaitUntil,
        /// Seconds to wait
        #[arg(long, default_value_t = DEFAULT_WAIT_SECS)]
        timeout: u64,
    },
}

#[derive(Debug, Subcommand)]
pub enum LogsCommand {
    /// Download the session's agent_debug log file (adb pull)
    Pull {
        /// Output file (default: the log's file name in the current directory)
        #[arg(short = 'o', long = "output", value_name = "FILE")]
        output: Option<PathBuf>,
    },
}

/// Default for `--timeout` of the waits, matching the app's 5-minute `launching` timeout.
pub const DEFAULT_WAIT_SECS: u64 = 300;

#[derive(Debug, Args)]
pub struct LaunchArgs {
    #[command(flatten)]
    pub game: GameArg,
    /// Verbose Wine/DXVK/VKD3D logging into a dedicated log file (agent_debug)
    #[arg(long)]
    pub debug: bool,
    /// Extra WINEDEBUG channels, comma-separated (e.g. +d3d,-fps); needs --debug
    #[arg(long, value_name = "CHANNELS", requires = "debug")]
    pub wine_debug: Option<String>,
    /// Wait until the new session is running or has exited
    #[arg(long, value_enum)]
    pub wait: Option<WaitUntil>,
    /// Seconds to wait (default 300); needs --wait
    #[arg(long, requires = "wait")]
    pub timeout: Option<u64>,
    /// Launch even if a session is active
    #[arg(long)]
    pub force: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum WaitUntil {
    Running,
    Exited,
}

impl WaitUntil {
    /// The session state that ends the wait.
    pub fn state(self) -> &'static str {
        match self {
            WaitUntil::Running => "running",
            WaitUntil::Exited => "exited",
        }
    }
}

#[derive(Debug, Args)]
pub struct GameArg {
    /// Game id: STEAM_123, or the number alone together with --source
    pub app_id: String,
    /// Game source for a numeric app id (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
    #[arg(long)]
    pub source: Option<String>,
}

/// Game sources the app knows (`app.gamenative.data.GameSource`).
pub const GAME_SOURCES: [&str; 5] = ["STEAM", "CUSTOM_GAME", "GOG", "EPIC", "AMAZON"];

/// A game id: `<SOURCE>_<number>`. The number is what the launch intent sends as `--ei app_id`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppId {
    pub source: String,
    pub number: i32,
}

impl AppId {
    pub fn from_arg(game: &GameArg) -> Result<AppId, CliError> {
        AppId::parse(&game.app_id, game.source.as_deref())
    }

    /// Accepts `STEAM_123` (with an optional matching `--source`) or `123` with `--source`.
    pub fn parse(app_id: &str, source: Option<&str>) -> Result<AppId, CliError> {
        let app_id = app_id.trim();
        let flag = source.map(parse_source).transpose()?;
        let (source, digits) = match app_id.rsplit_once('_') {
            Some((prefix, digits)) => {
                let embedded = parse_source(prefix)?;
                if let Some(flag) = &flag {
                    if *flag != embedded {
                        return Err(CliError::Usage(format!(
                            "app id {app_id} conflicts with --source {flag}"
                        )));
                    }
                }
                (embedded, digits)
            }
            None => {
                let Some(flag) = flag else {
                    return Err(CliError::Usage(format!(
                        "app id '{app_id}' needs a source: use STEAM_{app_id} or --source"
                    )));
                };
                (flag, app_id)
            }
        };
        let number = parse_number(digits).ok_or_else(|| {
            CliError::Usage(format!(
                "invalid app id '{app_id}': expected <SOURCE>_<number> with a number from 1 to {}",
                i32::MAX
            ))
        })?;
        Ok(AppId { source, number })
    }
}

impl std::fmt::Display for AppId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}_{}", self.source, self.number)
    }
}

fn parse_source(text: &str) -> Result<String, CliError> {
    let upper = text.trim().to_ascii_uppercase();
    if GAME_SOURCES.contains(&upper.as_str()) {
        Ok(upper)
    } else {
        Err(CliError::Usage(format!(
            "unknown game source '{text}' (one of {})",
            GAME_SOURCES.join(", ")
        )))
    }
}

/// A positive `Int` (the intent extra type); digits only, no sign.
fn parse_number(digits: &str) -> Option<i32> {
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    digits.parse::<i32>().ok().filter(|n| *n > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(id: &str, source: Option<&str>) -> Result<String, CliError> {
        AppId::parse(id, source).map(|a| a.to_string())
    }

    #[test]
    fn app_id_with_embedded_source() {
        assert_eq!(parse("STEAM_123", None).unwrap(), "STEAM_123");
        assert_eq!(parse("steam_123", None).unwrap(), "STEAM_123");
        assert_eq!(parse("CUSTOM_GAME_7", None).unwrap(), "CUSTOM_GAME_7");
        assert_eq!(parse("STEAM_0123", None).unwrap(), "STEAM_123");
        assert_eq!(parse("STEAM_123", Some("steam")).unwrap(), "STEAM_123");
        let app = AppId::parse("GOG_42", None).unwrap();
        assert_eq!(
            app,
            AppId {
                source: "GOG".into(),
                number: 42
            }
        );
    }

    #[test]
    fn numeric_app_id_with_source_flag() {
        assert_eq!(parse("123", Some("STEAM")).unwrap(), "STEAM_123");
        assert_eq!(parse("9", Some("custom_game")).unwrap(), "CUSTOM_GAME_9");
    }

    #[test]
    fn invalid_app_ids_are_usage_errors() {
        for (id, source) in [
            ("123", None),
            ("STEAM_123", Some("GOG")),
            ("XBOX_1", None),
            ("123", Some("XBOX")),
            ("STEAM_", None),
            ("STEAM_0", None),
            ("STEAM_-1", None),
            ("STEAM_+1", None),
            ("STEAM_1x", None),
            ("STEAM_2147483648", None),
            ("", Some("STEAM")),
        ] {
            let err = parse(id, source).unwrap_err();
            assert_eq!(err.exit_code(), 3, "{id} {source:?}");
        }
        assert_eq!(parse("STEAM_2147483647", None).unwrap(), "STEAM_2147483647");
    }
}
