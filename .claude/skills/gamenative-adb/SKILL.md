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
gnctl components list --target dxwrapperConfig.version  # valid values; installed / downloadable
gnctl containers get STEAM_123                          # current values, env, read_only
echo '{"values":{"dxwrapperConfig.version":"2.4.1"}}' | gnctl containers set STEAM_123 -
gnctl launch STEAM_123 --debug --wait running --timeout 300
gnctl screenshot -o shot.png                            # look at it
gnctl sessions logs --tail 200                          # redacted buffer
gnctl sessions stop
gnctl sessions wait --until exited --timeout 60
gnctl logs pull -o ./logs/                              # full agent_<appId>.log (unredacted)
# next experiment: containers set … → launch → …
gnctl containers restore STEAM_123 --baseline           # on failure, and when done
gnctl containers get STEAM_123                          # confirm it matches the original
```

## Rules

- **Never leave a game on an experimental config.** When the experiment fails, and when you finish, restore:
  `containers restore --baseline` (the config before the agent first touched the game) or `--backup <id>` for
  a known good intermediate step (`containers backups`). Report the final config to the user.
- **`NO_CONTAINER`** → the game was never launched. Do one normal launch first (`gnctl launch STEAM_123
  --wait running`, then stop it), then edit the config.
- **`wineVersion` changes may upgrade the Wine prefix irreversibly** (`wine_version_changed` warning);
  restoring the config does not downgrade the prefix. Ask before changing `wineVersion` on a game the user
  cares about.
- **`containerVariant` is UI-only** (glibc/bionic); `containers set` rejects it. So are the fexcore TSO/X87/
  MultiBlock modes, the whole `envVars`/`dxwrapperConfig`/`graphicsDriverConfig`/`wincomponents` strings
  (use dotted keys and `env`), and `WINEDEBUG` in `env` (use `--debug --wine-debug`).
- **`MISSING_COMPONENTS`** → nothing was written. Pick an installed version from `components list`, or ask the
  user to install the downloadable one in the app; the API never downloads.
- **`SESSION_ACTIVE`** → stop the session first; configs cannot change while the game has a session.
- **`needs_unpacking`** → the next launch unpacks the game again and takes longer.
- **Channels:** `--wine-debug` needs `--debug`; at most 8, no `all`/`relay`/`server`/`heap`/`snoop`/`file`,
  nothing the base already sets (`seh`, `loaddll`, `process`, `timestamp`, `pid`, `tid`). gnctl refuses bad
  ones with exit 3 before launching.

## Sessions and waits

- States: `launching` → `starting` → `running` → `exiting` → `exited`. `sessions current` shows the active or
  the last finished session with `exit_reason`, `last_launch_error`, `warnings`, `log_file`, `config`.
- `launch --wait running` exit 4 `SESSION_EXITED` → the game exited before a window appeared; read
  `exit_reason`/`last_launch_error`, `sessions logs`, `logs pull`. A `last_launch_error` alone is not fatal:
  the game may still run.
- `SESSION_BUSY` → a session is active. Stop it (`sessions stop` + `sessions wait --until exited`) instead of
  `--force`; `--force --wait` over a live session is refused anyway, the app keeps the live session.
- `sessions stop` during `starting` needs **no retry**: the app queues it until the Wine environment exists and
  then closes the game; repeating it does not queue another stop. Exit reason: `agent_stop`.
- `TIMEOUT` "no new session … appeared" → the launch never started: game not installed, or the launch was
  deferred (Steam not logged in / store service not ready on a cold start — the user may need to log in). A
  Steam launch without login may also sit in `launching` while the app waits for login.
- A `launching` session can pause at a sync-conflict or "session active elsewhere" dialog: take a screenshot
  and ask the user, do not pick a save for them.

## After exit

- Post-exit dialogs can cover the screen: the game-feedback dialog (after a config change, and the one-time
  Discord/support prompt after the first run), the AI debug-run offer (no window or a run under 90 s), a
  membership pitch. Dismiss with `adb shell input keyevent BACK` and check with a screenshot.
- After a launch through the intent the app calls `finish()` when the game exits, so it drops back to the
  launcher; that is expected.
