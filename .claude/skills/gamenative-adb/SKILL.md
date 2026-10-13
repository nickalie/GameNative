---
name: gamenative-adb
description: Drive GameNative on an Android device over ADB with the gnctl CLI — list installed games and component versions, read and change a game's container config (Wine, DXVK/VKD3D, driver, Box64/FEXCore, env vars) with backup and rollback, launch with verbose Wine/DXVK logging, wait for the game to run, take screenshots, read and pull logs, stop the game and restore the config. Use when asked to test, debug or tune a game's launch config on a device, try another DXVK/Wine/driver version, collect Wine logs, or "run this game on the phone and see what happens", even if gnctl is not named.
---

# GameNative over ADB (gnctl)

`gnctl` (`tools/gnctl`, build with `cargo build --release`) wraps the app's Agent API. Full reference:
`docs/agent-api.md` and `tools/gnctl/README.md`. Every command prints JSON; exit codes: 0 ok, 2 adb,
3 usage/protocol/local file, 4 app or session error (`error.code`), 5 timeout.

## Before you start

1. `adb devices` lists exactly one device (or pass `--serial`).
2. `gnctl capabilities` answers. `DISABLED` → ask the user to turn on **Settings → Debug → Agent API (ADB)**
   (no command can do it). `NOT_READY` → the app is starting, retry.
3. `adb shell input keyevent WAKEUP` before launching; with a secure lock screen the user may have to unlock
   (how `am start` behaves behind the keyguard is not verified).

## The loop

```sh
gnctl games list                                        # app_id, has_container
gnctl games add /sdcard/Games/PC/MyGame                 # folder → CUSTOM_GAME_<id>; see "Custom games"
gnctl components list --target dxwrapperConfig.version  # valid values; installed / downloadable
gnctl components install fexcoreVersion 2609-0 --wait   # fetch a downloadable version
gnctl containers get STEAM_123                          # current values, env, read_only
echo '{"values":{"dxwrapperConfig.version":"2.4.1"}}' | gnctl containers set STEAM_123 -
                                                        # note its backup_id: the config this session started from
gnctl launch STEAM_123 --debug --wait running --timeout 300
gnctl screenshot -o shot.png                            # look at it
gnctl sessions logs --tail 200                          # redacted buffer
gnctl sessions stop
gnctl sessions wait --until exited --timeout 60
mkdir -p logs && gnctl logs pull -o logs/              # full agent_<appId>.log (unredacted)
# next experiment: containers set … → launch → …
gnctl containers restore STEAM_123 --backup <id>        # id from the first set; on failure, and when done
gnctl containers get STEAM_123                          # confirm it matches the config you started from
```

## Rules

- **Never leave a game on an experimental config.** Record the `backup_id` returned by the first
  `containers set` of this session (it is `null` when nothing changed; take the first non-null one): that
  backup holds the config as it was when you started. When the experiment fails,
  and when you finish, restore it with `containers restore --backup <that id>`, or a later id for a known good
  intermediate step. Report the final config to the user. Only the newest 10 non-baseline backups are kept, so
  record the id right away; if it is lost, find it by `created_at` in `containers backups`.
- **`--baseline` is not "this session's start".** The baseline is the first backup the game ever got, taken
  before any agent's first change, possibly days ago; restoring it also reverts everything the user changed in
  the app since then. Use `--baseline` only when the user asks for the config from before the agent.
- **`NO_CONTAINER`** → the game was never launched. Do one normal launch first (`gnctl launch STEAM_123
  --wait running`, then stop it), then edit the config.
- **`wineVersion` changes may upgrade the Wine prefix irreversibly** (`wine_version_changed` warning);
  restoring the config does not downgrade the prefix. Ask before changing `wineVersion` on a game the user
  cares about.
- **`containerVariant` is UI-only** (glibc/bionic); `containers set` rejects it. So are the fexcore TSO/X87/
  MultiBlock modes, the whole `envVars`/`dxwrapperConfig`/`graphicsDriverConfig`/`wincomponents` strings
  (use dotted keys and `env`), and `WINEDEBUG` in `env` (use `--debug --wine-debug`).
- **`MISSING_COMPONENTS`** → nothing was written. Install each `downloadable` one with `gnctl components install
  <target> <value> --wait` (one download at a time; Wine/Proton take minutes), then repeat the `containers set`.
  Only an entry with `downloadable=false` needs a different version from `components list`.
- **Never send the user to the app** for something gnctl can do. If gnctl lacks a command for it, add the command
  to the Agent API instead of asking for a manual step.
- **`SESSION_ACTIVE`** → configs cannot change while the game has a session that has not exited. Check
  `gnctl sessions current` and clear it per state, as for `SESSION_BUSY` below.
- **`needs_unpacking`** → the next launch unpacks the game again and takes longer.
- **Channels:** `--wine-debug` needs `--debug`; at most 8, no `all`/`relay`/`server`/`heap`/`snoop`/`file`,
  nothing the base already sets (`seh`, `loaddll`, `process`, `timestamp`, `pid`, `tid`). gnctl refuses bad
  ones with exit 3 before launching.

## Custom games

- `games add PATH` adds a folder on the device to the library; it is idempotent (`added=false` when the folder
  was already there). `STORAGE_ACCESS_DENIED` → run the adb command from its message (it depends on the
  Android version), wait for `capabilities` to answer again (the grant restarts the app), then retry.
- `executable` in the answer is the exe the first launch picks. `null` (several exes, or the exe sits deeper
  than one subfolder) → launch once anyway to create the container: the session ends at once with
  `launch_error` and a "Cannot Launch" dialog stays up. Dismiss it with `adb shell input keyevent BACK`, then
  set the exe: `{"values":{"executablePath":"runtime\\media\\game.exe"}}` (relative to the game folder), or an
  absolute Windows path — `Z:` is the device root — when the exe computes paths relative to itself.

## Sessions and waits

- States: `launching` → `starting` → `running` → `exiting` → `exited`. `sessions current` shows the active or
  the last finished session with `exit_reason`, `last_launch_error`, `warnings`, `log_file`, `config`.
- `launch --wait running` exit 4 `SESSION_EXITED` → the game exited before a window appeared; read
  `exit_reason`/`last_launch_error`, `sessions logs`, `logs pull`. A `last_launch_error` alone is not fatal:
  the game may still run.
- `SESSION_BUSY` → a session has not exited; `error.details.state` says which state. The remedy depends on it:
  - `starting` / `running`: `sessions stop` + `sessions wait --until exited`. Do not use `--force` here:
    the app keeps the live session, and `--force --wait` is refused.
  - `exiting`: only `sessions wait --until exited` (`sessions stop` answers `NO_ACTIVE_SESSION` here).
  - `launching`: `sessions stop` cannot stop it (`NO_ACTIVE_SESSION`). The pre-launch may be waiting at a
    dialog or for a Steam login (see below): take a screenshot. To launch again, `launch --force` replaces
    it (`--wait` works). To change the config instead, the session has to end: the user resolves the dialog
    (dismissing it ends the session with `launch_error`), or it ends by itself with `launch_timeout` 5 minutes
    after it started.
- `sessions stop` during `starting` needs **no retry**: the app queues it until the Wine environment exists and
  then closes the game; repeating it does not queue another stop. Exit reason: `agent_stop`.
- `TIMEOUT` "no new session … appeared" → the launch never started: game not installed, or the launch was
  deferred (Steam not logged in / store service not ready on a cold start — the user may need to log in). A
  Steam launch without login may also sit in `launching` while the app waits for login.
- A `launching` session can pause at a sync-conflict or "session active elsewhere" dialog: take a screenshot
  and ask the user, do not pick a save for them.

## After exit

- Post-exit dialogs can cover the screen: the game-feedback dialog (after a config change, and once after
  the first run of a game), the AI debug-run offer (no window or a run under 90 s), a
  membership pitch. Dismiss with `adb shell input keyevent BACK` and check with a screenshot.
- After a launch through the intent the app calls `finish()` when the game exits, so it drops back to the
  launcher; that is expected.
