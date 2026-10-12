//! Snapshot of `gnctl --help` (and every subcommand's `--help`) kept in README.md, so the documented
//! command reference cannot drift from the CLI. Help output never touches a device.
//!
//! After changing the CLI, regenerate the README block with:
//! `GNCTL_UPDATE_HELP=1 cargo test --test help_snapshot`

use std::fs;
use std::path::PathBuf;

use clap::CommandFactory;
use gnctl::adb::AdbRunner;
use gnctl::cli::Cli;
use gnctl::launch::SystemClock;

const BEGIN: &str = "<!-- BEGIN GENERATED HELP -->";
const END: &str = "<!-- END GENERATED HELP -->";

/// Output of `gnctl <path...> --help`; fails if the command would need adb.
fn help(path: &[String]) -> String {
    let argv = std::iter::once("gnctl".to_string())
        .chain(path.iter().cloned())
        .chain(std::iter::once("--help".to_string()));
    let mut out = Vec::new();
    let code = gnctl::run(
        argv,
        &mut out,
        &mut std::io::empty(),
        &SystemClock::new(),
        |_| -> Box<dyn AdbRunner> { panic!("--help must not need a device") },
    );
    assert_eq!(code, 0, "gnctl {} --help exited {code}", path.join(" "));
    String::from_utf8(out).expect("help is UTF-8")
}

/// Every command path, depth first in definition order; the root is the empty path.
fn command_paths(command: &clap::Command, prefix: Vec<String>, paths: &mut Vec<Vec<String>>) {
    paths.push(prefix.clone());
    for sub in command.get_subcommands() {
        if sub.get_name() == "help" {
            continue;
        }
        let mut path = prefix.clone();
        path.push(sub.get_name().to_string());
        command_paths(sub, path, paths);
    }
}

fn generated_reference() -> String {
    let mut paths = Vec::new();
    command_paths(&Cli::command(), Vec::new(), &mut paths);
    let mut text = String::new();
    for path in paths {
        let name = if path.is_empty() {
            "gnctl".to_string()
        } else {
            format!("gnctl {}", path.join(" "))
        };
        text.push_str(&format!(
            "### `{name}`\n\n```text\n{}\n```\n\n",
            help(&path).trim_end()
        ));
    }
    text
}

#[test]
fn readme_help_matches_cli() {
    let readme_path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("README.md");
    let readme = fs::read_to_string(&readme_path).expect("README.md is readable");
    let start = readme.find(BEGIN).expect("README.md has the BEGIN marker") + BEGIN.len();
    let end = readme.find(END).expect("README.md has the END marker");
    assert!(start <= end, "markers are out of order");
    let expected = format!("\n\n{}", generated_reference());

    if std::env::var_os("GNCTL_UPDATE_HELP").is_some() {
        let updated = format!("{}{expected}{}", &readme[..start], &readme[end..]);
        fs::write(&readme_path, updated).expect("README.md is writable");
        return;
    }
    assert!(
        readme[start..end] == expected,
        "README.md command reference is out of date; run \
         `GNCTL_UPDATE_HELP=1 cargo test --test help_snapshot` in tools/gnctl"
    );
}

#[test]
fn top_level_help_lists_every_command() {
    let top = help(&[]);
    for sub in Cli::command().get_subcommands() {
        assert!(
            top.contains(sub.get_name()),
            "{} missing from --help",
            sub.get_name()
        );
    }
}
