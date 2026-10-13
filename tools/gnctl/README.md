# gnctl

Host-side CLI for the GameNative [Agent API](../../docs/agent-api.md): list games and components, read and
persistently change launch configs with backups, launch games with verbose logging, follow and stop the
session, read logs and take screenshots — over ADB, without the UI.

It needs `adb` on the host, a device with USB/wireless debugging, and **Settings → Debug → Agent API (ADB)**
turned on in GameNative.

## Build

```sh
cd tools/gnctl
cargo build --release        # binary: target/release/gnctl
cargo test                   # unit tests with a fake adb; no device needed
```

## Output and exit codes

Every command prints JSON on stdout: the command's `data` on success, or
`{"ok":false,"error":{"code":"...","message":"...","details":...}}` on failure.

| Exit | Meaning | Error codes |
|---|---|---|
| 0 | OK | — |
| 2 | adb failed: adb missing, no/several devices, provider missing or refused, `am start` error, screencap not a PNG, `adb pull` failed | `ADB_ERROR` |
| 3 | Bad usage, a local file error, or an answer gnctl cannot parse | `USAGE_ERROR`, `IO_ERROR`, `PROTOCOL_ERROR` |
| 4 | The app answered `ok=false`, or a CLI-side session condition | the app's code (`DISABLED`, `NOT_READY`, `NO_CONTAINER`, `BAD_ARGS`, …), `SESSION_BUSY`, `SESSION_EXITED`, `SESSION_VANISHED`, `NO_ACTIVE_SESSION`, `NO_LOG_FILE` |
| 5 | A wait did not finish in time | `TIMEOUT` |

CLI-side codes (exit 4):

- `SESSION_BUSY` — `launch` while a session is `launching`, `starting`, `running` or `exiting`, without
  `--force`. With `--force` over a `starting`/`running`/`exiting` session the intent is sent (output warning
  `active_session_kept`, the app keeps that session), but `--force --wait` is refused with `SESSION_BUSY`
  because no new session would appear. A `launching` session is replaced, so `--force --wait` works there.
- `NOT_READY` — `launch` retries the app's `NOT_READY` for 15 s before giving up.
- `SESSION_EXITED` — waiting for `running` and the session is `exiting`/`exited`; or the session ended with
  `exit_reason` `launch_error`/`launch_timeout`, whatever the wait target. The message has `exit_reason` and
  `last_launch_error`; `details` is the session.
- `SESSION_VANISHED` — the session being waited for is no longer the current one (another launch, app restart).
- `NO_ACTIVE_SESSION` — `sessions wait` with no session at all.
- `NO_LOG_FILE` — `logs pull` when the session has no log file (no `--debug`, or not yet `starting`).

## Selecting the device and the app

- `--serial SERIAL` (else `$ANDROID_SERIAL`, else adb's only device).
- `--adb PATH` — the adb binary (default `adb`).
- `--package NAME` — default `app.gamenative`; the provider authority is `<package>.agent`, so a
  `release-gold` build is `--package app.gamenative.gold`.

Game ids: `STEAM_123`, `CUSTOM_GAME_7`, … or the number with `--source` (`123 --source STEAM`). A bare number
without `--source` is a usage error.

## Examples

```sh
gnctl capabilities
gnctl games list --source STEAM
gnctl games add /sdcard/Games/PC/MyGame          # add a folder as a custom game
gnctl stores list                                # Settings → Stores switches
gnctl stores set GOG on                          # show GOG games, start its service (no login)
gnctl components list --target dxwrapperConfig.version
gnctl components install wineVersion proton-11.0-2-arm64ec-1 --wait --timeout 900
gnctl containers get STEAM_123

# change the config (backup first); FILE or - for stdin
echo '{"values":{"dxwrapperConfig.version":"2.4.1"},"env":{"set":{"DXVK_HUD":"fps"}}}' \
  | gnctl containers set STEAM_123 -

gnctl launch STEAM_123 --debug --wine-debug +d3d,+vulkan --wait running --timeout 300
gnctl screenshot -o shot.png
gnctl sessions logs --tail 100
gnctl sessions stop
gnctl sessions wait --until exited --timeout 60
mkdir -p logs && gnctl logs pull -o logs/   # adb pull of sessions.current.log_file

gnctl containers backups STEAM_123
gnctl containers restore STEAM_123 --backup <id>  # or --baseline: the config before the agent's first change

gnctl call games.list --args '{"source":"GOG"}'   # any raw API command
```

Notes:

- `containers set` input may contain only `values`, `env` and (matching) `app_id`; other top-level keys are a
  usage error, since the app would ignore them.
- `launch` reads `sessions.current` first, sends
  `am start -n <package>/app.gamenative.MainActivity -a app.gamenative.LAUNCH_GAME --ei app_id N --es game_source SRC [--ez agent_debug true] [--es wine_debug_channels …]`,
  and with `--wait` waits for a **new** session of that game (one whose id differs from the session seen
  before the launch). Output: `{app_id, agent_debug, wine_debug_channels?, previous_session_id?, warnings?,
  session?}`.
- `--wine-debug` needs `--debug`. Channels the app would drop (invalid, banned, already in the base
  `WINEDEBUG`, more than 8) are refused with exit 3 before adb is called.
- `--wait exited` / `sessions wait --until exited` succeed for any normal exit; only `launch_error` and
  `launch_timeout` exits fail with `SESSION_EXITED`.
- A launch the app defers (store service not ready), a game that is not installed, or a game whose store is
  off in Settings → Stores (by default only Steam and Custom Games are on; see `stores list`) creates no
  session: `--wait` then ends with `TIMEOUT` ("no new session … appeared").
- `sessions wait` uses one deadline (`--timeout`, default 300 s) for both a cold-starting app (`NOT_READY`) and
  the wait; `NOT_READY` until the deadline → `TIMEOUT`.
- `screenshot` drops any text `screencap` prints before the PNG (multi-display warning). Output `{file, bytes}`.
- `logs pull -o` accepts a file or an existing directory (the remote name is kept); default is the remote file
  name in the current directory. Output `{file, remote, bytes, session_id}`.

## Command reference

Generated from the CLI; `cargo test` fails when it is out of date. Regenerate with
`GNCTL_UPDATE_HELP=1 cargo test --test help_snapshot`.

<!-- BEGIN GENERATED HELP -->

### `gnctl`

```text
Control GameNative on an Android device over ADB

Usage: gnctl [OPTIONS] <COMMAND>

Commands:
  capabilities  API version, app version, package and the commands the app supports
  device        Device information
  games         Installed games; add custom game folders
  stores        Which game stores are on (Settings → Stores); turn them on or off
  components    Component versions usable in container configs
  containers    Read and persistently change a game's container config
  sessions      The current (or last finished) game session
  launch        Launch a game through the LAUNCH_GAME intent
  screenshot    Save a PNG screenshot of the device screen
  logs          Session log files
  call          Send a raw agent API command and print its data
  help          Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
  -V, --version            Print version
```

### `gnctl capabilities`

```text
API version, app version, package and the commands the app supports

Usage: gnctl capabilities [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl device`

```text
Device information

Usage: gnctl device [OPTIONS] <COMMAND>

Commands:
  info  Model, Android version, SoC, GPU, Vulkan, free storage
  help  Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl device info`

```text
Model, Android version, SoC, GPU, Vulkan, free storage

Usage: gnctl device info [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl games`

```text
Installed games; add custom game folders

Usage: gnctl games [OPTIONS] <COMMAND>

Commands:
  list  Installed games
  add   Add a folder on the device as a custom game (needs the app's All files access)
  help  Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl games list`

```text
Installed games

Usage: gnctl games list [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --source <SOURCE>    Only games from this source (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl games add`

```text
Add a folder on the device as a custom game (needs the app's All files access)

Usage: gnctl games add [OPTIONS] <PATH>

Arguments:
  <PATH>  Absolute device path of the game folder, e.g. /sdcard/Games/PC/MyGame

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl stores`

```text
Which game stores are on (Settings → Stores); turn them on or off

Usage: gnctl stores [OPTIONS] <COMMAND>

Commands:
  list  Every store with its switch
  set   Turn a store on or off (starts or stops its service; never logs in or out)
  help  Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl stores list`

```text
Every store with its switch

Usage: gnctl stores list [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl stores set`

```text
Turn a store on or off (starts or stops its service; never logs in or out)

Usage: gnctl stores set [OPTIONS] <SOURCE> <STATE>

Arguments:
  <SOURCE>  STEAM, CUSTOM_GAME, GOG, EPIC or AMAZON
  <STATE>   on or off [possible values: on, off]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl components`

```text
Component versions usable in container configs

Usage: gnctl components [OPTIONS] <COMMAND>

Commands:
  list     Installed and downloadable component versions
  install  Download and install a downloadable version from the component manifest
  help     Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl components list`

```text
Installed and downloadable component versions

Usage: gnctl components list [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --target <TARGET>    Only this config key, e.g. wineVersion or dxwrapperConfig.version
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl components install`

```text
Download and install a downloadable version from the component manifest

Usage: gnctl components install [OPTIONS] <TARGET> <VALUE>

Arguments:
  <TARGET>  Config key the version is for, e.g. wineVersion or dxwrapperConfig.version
  <VALUE>   Version as `components list` reports it, e.g. proton-11.0-2-arm64ec-1

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --variant <VARIANT>  Variant, when the manifest has the version in several (e.g. box64Version 0.4.4-1: bionic or arm64ec)
      --adb <ADB>          Path to the adb binary [default: adb]
      --wait               Wait until the install finished or failed
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
      --timeout <TIMEOUT>  Seconds to wait with --wait [default: 300]
  -h, --help               Print help
```

### `gnctl containers`

```text
Read and persistently change a game's container config

Usage: gnctl containers [OPTIONS] <COMMAND>

Commands:
  get      Current config values, env (secrets masked) and read-only keys
  set      Apply {"values":{..},"env":{"set":{..},"unset":[..]}} from FILE or stdin (-); takes a backup first
  restore  Roll the config back to a backup or to the baseline (first) backup
  backups  List the config backups, oldest first
  help     Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl containers get`

```text
Current config values, env (secrets masked) and read-only keys

Usage: gnctl containers get [OPTIONS] <APP_ID>

Arguments:
  <APP_ID>  Game id: STEAM_123, or the number alone together with --source

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --source <SOURCE>    Game source for a numeric app id (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl containers set`

```text
Apply {"values":{..},"env":{"set":{..},"unset":[..]}} from FILE or stdin (-); takes a backup first

Usage: gnctl containers set [OPTIONS] <APP_ID> <FILE|->

Arguments:
  <APP_ID>  Game id: STEAM_123, or the number alone together with --source
  <FILE|->  JSON file with the changes, or - for stdin

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --source <SOURCE>    Game source for a numeric app id (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl containers restore`

```text
Roll the config back to a backup or to the baseline (first) backup

Usage: gnctl containers restore [OPTIONS] <APP_ID>

Arguments:
  <APP_ID>  Game id: STEAM_123, or the number alone together with --source

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --source <SOURCE>    Game source for a numeric app id (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
      --adb <ADB>          Path to the adb binary [default: adb]
      --backup <ID>        Backup id from `containers backups`
      --baseline           Restore the baseline backup taken before the first change
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl containers backups`

```text
List the config backups, oldest first

Usage: gnctl containers backups [OPTIONS] <APP_ID>

Arguments:
  <APP_ID>  Game id: STEAM_123, or the number alone together with --source

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --source <SOURCE>    Game source for a numeric app id (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl sessions`

```text
The current (or last finished) game session

Usage: gnctl sessions [OPTIONS] <COMMAND>

Commands:
  current  The active session, or the last finished one (null if none)
  stop     Stop the starting or running session
  logs     Buffered (redacted) log lines of the session
  wait     Wait until the current session is running or has exited
  help     Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl sessions current`

```text
The active session, or the last finished one (null if none)

Usage: gnctl sessions current [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl sessions stop`

```text
Stop the starting or running session

Usage: gnctl sessions stop [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl sessions logs`

```text
Buffered (redacted) log lines of the session

Usage: gnctl sessions logs [OPTIONS]

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --tail <TAIL>        Number of newest lines (app default 200)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl sessions wait`

```text
Wait until the current session is running or has exited

Usage: gnctl sessions wait [OPTIONS] --until <UNTIL>

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --until <UNTIL>      [possible values: running, exited]
      --adb <ADB>          Path to the adb binary [default: adb]
      --timeout <TIMEOUT>  Seconds to wait [default: 300]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl launch`

```text
Launch a game through the LAUNCH_GAME intent

Usage: gnctl launch [OPTIONS] <APP_ID>

Arguments:
  <APP_ID>  Game id: STEAM_123, or the number alone together with --source

Options:
      --serial <SERIAL>        Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --source <SOURCE>        Game source for a numeric app id (STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON)
      --adb <ADB>              Path to the adb binary [default: adb]
      --debug                  Verbose Wine/DXVK/VKD3D logging into a dedicated log file (agent_debug)
      --package <PACKAGE>      GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
      --wine-debug <CHANNELS>  Extra WINEDEBUG channels, comma-separated (e.g. +d3d,-fps); needs --debug
      --wait <WAIT>            Wait until the new session is running or has exited [possible values: running, exited]
      --timeout <TIMEOUT>      Seconds to wait (default 300); needs --wait
      --force                  Launch even if a session is active
  -h, --help                   Print help
```

### `gnctl screenshot`

```text
Save a PNG screenshot of the device screen

Usage: gnctl screenshot [OPTIONS] --output <FILE>

Options:
  -o, --output <FILE>      Output file
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl logs`

```text
Session log files

Usage: gnctl logs [OPTIONS] <COMMAND>

Commands:
  pull  Download the session's agent_debug log file (adb pull)
  help  Print this message or the help of the given subcommand(s)

Options:
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl logs pull`

```text
Download the session's agent_debug log file (adb pull)

Usage: gnctl logs pull [OPTIONS]

Options:
  -o, --output <FILE>      Output file (default: the log's file name in the current directory)
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

### `gnctl call`

```text
Send a raw agent API command and print its data

Usage: gnctl call [OPTIONS] <COMMAND>

Arguments:
  <COMMAND>  Command name, e.g. games.list

Options:
      --args <ARGS>        Command arguments as a JSON object [default: {}]
      --serial <SERIAL>    Device serial (default: $ANDROID_SERIAL, else adb's only device)
      --adb <ADB>          Path to the adb binary [default: adb]
      --package <PACKAGE>  GameNative package name (the provider authority is <package>.agent) [default: app.gamenative]
  -h, --help               Print help
```

<!-- END GENERATED HELP -->
