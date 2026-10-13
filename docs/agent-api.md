# Agent API (ADB)

The Agent API lets an AI agent (Codex, Claude Code, a script) on a computer connected over ADB control
GameNative without tapping through the UI: list games and component versions, read and persistently change
a game's launch config (with backups and rollback), launch a game with verbose logging, follow and stop the
session, read Wine logs and take screenshots. logcat is not used as a control protocol.

It has three parts:

- an exported ContentProvider, `content://<package>.agent`, called with `adb shell content call` — the
  back channel for every command (`app.gamenative.agent.AgentProvider`);
- two extras on the existing `app.gamenative.LAUNCH_GAME` intent, `agent_debug` and `wine_debug_channels`;
- the host CLI [`gnctl`](../tools/gnctl/README.md), which wraps both and also takes screenshots
  (`adb exec-out screencap -p`) and pulls log files (`adb pull`).

The code lives in `app/src/main/java/app/gamenative/agent/`.

## Enabling

1. Enable USB (or wireless) debugging on the device and make sure `adb devices` lists it.
2. In GameNative open **Settings → Debug** and turn on **Agent API (ADB)**. It is off by default.

While the toggle is off every command answers `DISABLED` and the launch extras are ignored. No command can
turn the toggle on or off.

The provider authority is derived from the package name: `app.gamenative.agent` for regular builds,
`app.gamenative.gold.agent` for `release-gold` and so on (`gnctl --package`).

## Security model

- **Callers.** Every provider entry point checks `Binder.getCallingUid()`: only the shell UID (2000) and root
  (0) are allowed, in any Android user (multi-user UIDs are `userId * 100000 + appId`). Any other caller gets a
  `SecurityException`. Apps that use **Shizuku** run their commands as the shell UID, so with the toggle on
  they have the same access as `adb shell`. The settings subtitle says so.
- **Toggle.** Off by default. Before the app has finished starting (preferences not initialized yet) every
  command answers `NOT_READY`; after that, with the toggle off, `DISABLED`.
- **Only `call`.** `query`, `insert`, `update`, `delete`, `openFile`, `openAssetFile` and
  `openTypedAssetFile` check the caller and then throw `UnsupportedOperationException`; `getType` checks the
  caller and returns `null`. A `call` with a method other than `command` returns a `BAD_ARGS` envelope.
- **Launch extras.** `MainActivity` is exported, so any app can send `LAUNCH_GAME`. `agent_debug` and
  `wine_debug_channels` are honored only while the toggle is on; otherwise the launch runs as a plain launch.
- **Secrets.** The provider never reads account tokens or credentials. `containers.get` masks env values whose
  name has a whole `TOKEN`, `KEY`, `SECRET`, `PASSWORD`, `PASS` or `AUTH` segment (`(^|_)(…)(_|$)`, so
  `DXVK_…_BYPASS` or `…_PASSTHROUGH` stay readable) as `***`. Lines in the session log buffer
  (`sessions.logs`) are redacted: `-AUTH_PASSWORD=…`, `--access-token=…`, `token=…`, JSON
  `"refresh_token": "…"`, `Authorization: …` headers and bare `Bearer …` tokens become `[REDACTED]`.
- **Not redacted.** The `agent_debug` log file (`wine_logs/agent_<appId>.log`, fetched with `adb pull`) is the
  raw process output. The shell can read it anyway. Config backups under the container directory store the
  full `envVars` string, unmasked.
- **What an enabled API allows.** Persistent config changes within the AI-suggestion boundary (below),
  adding folders as custom games, installing component versions listed in the app's component manifest,
  launching and stopping games. It cannot install anything outside that manifest, change drives, controls or
  the container variant, and cannot read other app data.

## Transport

```
adb shell content call --uri content://app.gamenative.agent --method command --arg <base64url(JSON)>
```

- The request is `{"api_version":1,"command":"games.list","args":{...}}` encoded as base64url (`-` and `_`
  instead of `+` and `/`; padding optional). `args` may be omitted. The decoded request is limited to 64 KB.
- The `content` tool prints `Result: Bundle[{json=<response>}]`.
- Success: `{"api_version":1,"ok":true,"data":...}`.
- Failure: `{"api_version":1,"ok":false,"error":{"code":"...","message":"...","details":...}}`; `details` is
  optional and command-specific.
- Responses are capped at 256 KB (`RESPONSE_TOO_LARGE`) to stay under the binder transaction limit.
- A command that throws unexpectedly answers `INTERNAL` with the exception class and message; the app
  process does not crash.

Raw example without gnctl (any POSIX shell):

```sh
req=$(printf '%s' '{"api_version":1,"command":"capabilities"}' | base64 | tr '+/' '-_' | tr -d '=\n')
adb shell content call --uri content://app.gamenative.agent --method command --arg "$req"
# Result: Bundle[{json={"api_version":1,"ok":true,"data":{"api_version":1,"app_version":"...","package":"app.gamenative","commands":[...]}}}]
```

## Error codes

| Code | Meaning |
|---|---|
| `UNKNOWN_COMMAND` | No such command. |
| `BAD_ARGS` | Malformed request (base64, JSON, `api_version`, `command`, `args`) or invalid arguments. `containers.set` lists rejected changes in `details.rejected`. |
| `UNSUPPORTED_API_VERSION` | `api_version` is not 1; `details.supported` lists the supported versions. |
| `GAME_NOT_FOUND` | Reserved; no v1 command returns it. |
| `NO_CONTAINER` | The game has no container yet (never launched). Launch it once normally. |
| `MISSING_COMPONENTS` | `containers.set` needs component versions the device does not have; `details` is an array of `{name, type?, downloadable}`. Nothing is written. Install the `downloadable` ones with `components.install`. |
| `CONTAINER_INVALID` | The container's current `videoPciDeviceID` is unknown to the app; fix it in the container settings first. |
| `NO_ACTIVE_SESSION` | `sessions.stop` while no session is `starting` or `running`; `details.state` is the current state, if any. |
| `SESSION_ACTIVE` | `containers.set`/`restore` while the game has a session that has not exited. |
| `NO_BACKUP` | `containers.restore` with an unknown backup id, or no baseline yet. |
| `STORAGE_ACCESS_DENIED` | `games.add_custom` for a folder outside the app's own storage while the app has no All files access. |
| `NOT_READY` | The app process is still starting. Retry. |
| `DISABLED` | The Agent API toggle is off. |
| `RESPONSE_TOO_LARGE` | The response exceeds 256 KB; `details` has `size` and `limit`. Use a filter or a smaller `tail`. |
| `INTERNAL` | A command failed unexpectedly (also a failed container save after the backup was taken; roll back with `containers.restore`). |

## Commands

App ids have the shape `<SOURCE>_<number>`, e.g. `STEAM_123`, `CUSTOM_GAME_7`; sources are `STEAM`,
`CUSTOM_GAME`, `GOG`, `EPIC`, `AMAZON`. Anything else is `BAD_ARGS`.

### `capabilities`

`{api_version, app_version, package, commands[]}` — `commands` lists every registered command, sorted:
`capabilities`, `components.install`, `components.list`, `containers.backups`, `containers.get`, `containers.restore`,
`containers.set`, `device.info`, `games.add_custom`, `games.list`, `sessions.current`, `sessions.logs`, `sessions.stop`.

### `device.info`

A flat object: `manufacturer`, `model`, `android_sdk`, `android_release`, the session-report device
properties (`device_codename`, `board`, `hardware`, `build_fingerprint`, `soc_model`, `cpu_abi`, `cpu_cores`,
`ram_total_mb`, `screen_*`, `kernel_version`, …), GPU properties (`gpu_name`, `gpu_vendor`,
`gpu_driver_version`, `vulkan_api_version`) and `storage_free_bytes` / `external_storage_free_bytes`. A probe
that fails only drops its own fields. GPU detection runs on its own thread with a 5 s timeout.

### `games.list {source?}`

Installed games only: `[{app_id, game_id, game_source, name, has_container}]`. `game_id` is the numeric store
id (`null` when the store id is not numeric). Custom game folders imported as a Steam game are listed once,
as the Steam entry. `has_container=false` means the game was never launched: config commands answer
`NO_CONTAINER` until it is. Unknown `source` → `BAD_ARGS` with `details.sources`.

### `games.add_custom {path}`

Adds a folder on the device as a custom game, as the library's folder picker does:
`{app_id, game_id, game_source, name, path, added, has_container, executable}`.

- `path` is an absolute device path, taken verbatim (whitespace is part of a name). `.`/`..` and a trailing
  slash are normalized and `/sdcard/X` becomes `/storage/emulated/<user>/X`; other symlinks are kept, as the
  folder picker keeps them. `path` in the answer is that form.
- With **Import custom games as Steam games** on, a folder named like the install dir of an owned Steam game
  becomes that Steam game (`STEAM_<id>`), as in the UI.
- `added=false`: the folder was already in the list of added folders; nothing changed. Adding is idempotent.
- `executable` is the exe the first launch picks by itself (relative to the folder), or `null` when the
  folder and its direct subfolders hold no exe or more than one (always `null` for a Steam entry). With `null`
  the first launch creates the container, ends the session at once with `launch_error` ("Launch aborted before
  the game started") and leaves a "Cannot Launch" dialog on screen: dismiss it, then set `executablePath` with
  `containers.set` — relative to the folder (`bin\game.exe`) or an absolute Windows path (`Z:` is the device
  root).
- A folder outside the app's own storage needs storage access, else `STORAGE_ACCESS_DENIED`; the message
  carries the adb command that grants it: Android 11+ `adb shell appops set <package> MANAGE_EXTERNAL_STORAGE
  allow`, Android 10 and earlier `adb shell pm grant <package> android.permission.READ_EXTERNAL_STORAGE`.
  Granting All files access restarts the app process (`NOT_READY` until it is up again).
- Not a directory, or a folder the app refuses → `BAD_ARGS`. The folder is never copied or moved.

### `components.list {target?}`

`[{target, value, label, installed, downloadable, variant?}]` — component versions a config may reference.
`value` is what the config stores, `label` what the UI shows.

| `target` | Notes |
|---|---|
| `wineVersion` | `variant` `glibc` or `bionic`; installed Wine/Proton packages are bionic. |
| `dxwrapperConfig.version` | DXVK versions. |
| `dxwrapperConfig.vkd3dVersion` | VKD3D versions. |
| `graphicsDriverConfig.version` | Driver ids, verbatim (`System`, installed and manifest drivers). |
| `box64Version` | `variant` `glibc`, `bionic`, or `arm64ec` — WoWBox64 versions, used when `wineVersion` is an arm64ec build. |
| `fexcoreVersion` | FEXCore versions. |

Sources are the same as the app's own config validation: bundled entries, installed content and the
component manifest. `installed=false, downloadable=true` entries must be installed (`components.install`)
before a config can use them. The manifest is read from the offline cache only; `components.list` never
touches the network and does not refresh a stale cache (an empty list of downloadable entries on a fresh
install means the cache is empty; `containers.set` or `components.install` fill it). Unknown `target` →
`BAD_ARGS` with `details.targets`.

### `components.install {target, value, variant?}`

Downloads and installs a version from the component manifest, the way the config dialog does:
`{target, value, variant?, state, progress, message?}`.

- `target` is one of the `components.list` targets; `value` the version as listed there (case-insensitive).
  `variant` is needed only when the manifest has that id in several variants (Box64 and WoWBox64 share ids) —
  `BAD_ARGS` with `details.variants` says which.
- The download runs in the background, one at a time; the answer comes at once. `state`: `queued`,
  `downloading` (`progress` 0–1), `installed`, `failed` (`message` says why). Asking again for the same
  component reports the job instead of starting a second download. A failure is answered once (`failed`); the
  call after that starts a new attempt. Jobs live in the app process: after a restart a finished install shows
  up as `installed` through `components.list`.
- Installed content without a variant counts for every variant except `arm64ec`: WoWBox64 is installed
  separately even when Box64 with the same id is present.
- Already installed → `state=installed` at once, nothing is downloaded.
- Not in the cached manifest → the manifest is fetched over the network when the cache is empty or a day old,
  then looked up again; still unknown → `BAD_ARGS`.

### `containers.get {app_id}`

`{app_id, values, env, read_only}`:

- `values` — every key of the AI-suggestion config set (`SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS`) except
  `envVars`, typed as boolean, integer or string. Key/value configs (`dxwrapperConfig`, `graphicsDriverConfig`,
  `wincomponents`) are the raw `key=value,…` strings.
- `env` — the container env parsed into `{NAME: value}`, secret-looking values masked as `***`.
- `read_only` — `["containerVariant"]`.

No container → `NO_CONTAINER`.

### `containers.set {app_id, values?, env?}`

```json
{"app_id":"STEAM_123",
 "values":{"dxwrapperConfig.version":"2.4.1","graphicsDriverConfig.version":"System","videoMemorySize":"4096"},
 "env":{"set":{"DXVK_HUD":"fps"},"unset":["MESA_NO_ERROR"]}}
```

Persistently changes the config, exactly within the boundary of an AI config suggestion (see
[Editable keys](#editable-keys)). Steps, in order:

1. Any session of this game that has not exited (`launching` … `exiting`) → `SESSION_ACTIVE`.
2. Each entry becomes a suggestion change checked by `SupportSuggestion.isValid`. Rejected entries →
   `BAD_ARGS` with `details.rejected = [{key, name?, reason}]`, `reason` ∈ `read_only`, `not_editable`,
   `unknown_key`, `blocked_env`, `invalid_name`, `invalid_value`, `duplicate`. At most 64 changes per request;
   an empty request is `BAD_ARGS`.
3. No container → `NO_CONTAINER`; unknown current `videoPciDeviceID` → `CONTAINER_INVALID`.
4. The changes are folded into full values (dotted keys and env ops into the whole `dxwrapperConfig`/
   `envVars` strings) and applied in memory. If nothing changes, nothing is written and no backup is taken.
5. If a component key changed (`wineVersion`, `dxwrapper`, `dxwrapperConfig`, `box64Version`, `box64Preset`,
   `emulator`, `fexcoreVersion`, `fexcorePreset`, `graphicsDriver`, `graphicsDriverVersion`,
   `graphicsDriverConfig`), the components are checked: versions that are
   downloadable but not installed, or unknown → `MISSING_COMPONENTS`; a config the validator refuses outright
   → `BAD_ARGS`. Nothing is downloaded here: install first with `components.install`. A driver alias is rewritten to the id the runtime resolves.
6. A backup of the current config is taken, then the config is saved (`applyToContainer`, `saveToDisk=true`)
   and marked as applied by `agent_api`. A failed save → `INTERNAL` (the backup is kept).

Result: `{app_id, backup_id, changed_fields[], warnings[]}`. `backup_id` is `null` when nothing changed.
`changed_fields` reports key/value and env entries individually (`dxwrapperConfig.version`,
`envVars.DXVK_HUD`). `warnings`:

- `wine_version_changed` — the next launch may upgrade the Wine prefix; `containers.restore` does not undo that.
- `needs_unpacking` — `executablePath` changed from a non-empty value, `launchBionicSteam`/`launchRealSteam`
  changed, or `unpackFiles` was turned on; the next launch unpacks the game files again.

**Network note.** The component check (step 5) uses the app's component manifest. If the cached manifest is
older than 24 h it may be refreshed over the network inside the call, which then takes longer.

**Side effect.** Every write sets the container's `config_changed` flag, so the app shows its game-feedback
dialog after the next game exit.

### `containers.restore {app_id, backup_id? | baseline: true}`

Exactly one of `backup_id` and `baseline: true`. Writes only the keys whose backed-up value differs from the
current one; full values (`envVars`, `dxwrapperConfig`, …) are written as they were saved, without suggestion
validation (they came from the app). `containerVariant` is never touched. The rollback first takes a backup of
the current config itself, so it can be undone. No component check.

Result: `{app_id, restored_from, backup_id, changed_fields[], warnings[]}` (`backup_id` = the backup just taken,
`null` when nothing differed). Errors: `SESSION_ACTIVE`, `NO_CONTAINER`, `CONTAINER_INVALID`, `NO_BACKUP`,
`BAD_ARGS` (both or neither selector, malformed id).

### `containers.backups {app_id}`

`[{backup_id, created_at, baseline}]`, oldest first; `created_at` is epoch milliseconds.

Backups live in `<container root>/.gamenative/agent_backups/<backup_id>.json` and are deleted together with the
container. The first backup of a container is pinned as the **baseline** (the config before the agent first
changed it) and never rotated; of the others the newest 10 are kept. A backup holds every applicable key except
`containerVariant`.

### `sessions.current`

The active session, or the last finished one; `null` if the app has seen no session since it started.

```json
{"session_id":"…","app_id":"STEAM_123","state":"running","started_at":1760000000000,"running_at":1760000020000,
 "warnings":[],"agent_debug":true,"wine_debug_channels":"+d3d",
 "log_file":"/storage/emulated/0/Android/data/app.gamenative/files/wine_logs/agent_STEAM_123.log",
 "config":{"wineVersion":"…","containerVariant":"…","dxwrapper":"…","dxwrapperConfig":"…","graphicsDriver":"…",
           "graphicsDriverConfig":"…","box64Version":"…","emulator":"…"}}
```

Optional fields: `running_at`, `exited_at`, `exit_reason`, `last_launch_error`, `wine_debug_channels`,
`log_file`, `config`. Times are epoch milliseconds. See [Sessions](#sessions).

### `sessions.stop`

Stops the `starting` or `running` session: marks it as stopped by the agent and, on the main thread, emits
the app's force-close event. Result: `{session_id, app_id, state, already_requested}`.

- In `starting` the wine environment may not exist yet. The stop is not lost and needs no retry: it waits on
  the main thread (re-checking every 250 ms) until the environment exists, then closes it. If the session
  ends some other way first, the queued stop is dropped.
- Repeating the stop is safe: while one is queued for the session another is not queued
  (`already_requested: true`).
- Prerequisite installers (VC++ redistributables and the like) are not waited for: teardown kills the
  installer, and the installer chain does not advance once the screen is exiting (the step stays not done and
  runs again next launch).
- Any other state (or no session) → `NO_ACTIVE_SESSION`.

The session then goes `exiting` → `exited` with `exit_reason=agent_stop`.

### `sessions.logs {tail?}`

`{lines[], truncated, log_file?}` — the newest `tail` lines (default 200) of the session's redacted log
buffer. The buffer keeps the last 2000 lines of process output of the current (or last) session. Lines are
dropped from the old end once they exceed 192 KB. `truncated` is true when earlier lines of the session are not
included for any reason. A negative or non-integer `tail` → `BAD_ARGS`; a larger one is clamped.

## Editable keys

`containers.set` can change exactly what an AI config suggestion can (`SupportSuggestion.isValid`). Values may
be JSON strings, numbers or booleans; they are validated as text.

| Kind | Keys | Rule |
|---|---|---|
| Enum | `dxwrapper` | `wined3d`, `dxvk`, `vkd3d`, `cnc-ddraw` |
| | `audioDriver` | `alsa`, `pulseaudio`, `disabled` |
| | `emulator` | `FEXCore`, `Box64` |
| | `steamType` | `normal`, `light`, `ultralight`, `headless` |
| | `suspendPolicy` | `auto`, `never`, `manual` |
| | `startupSelection` | `0`, `1`, `2` |
| Boolean | `useLegacyDRM`, `steamOfflineMode`, `loadMods`, `epicOfflineMode`, `unpackFiles`, `launchBionicSteam`, `launchRealSteam` | `true`, `false`, `1`, `0` |
| String | `executablePath`, `execArgs`, `graphicsDriver`, `graphicsDriverVersion`, `wineVersion`, `box64Version`, `box64Preset`, `fexcoreVersion`, `fexcorePreset`, `cpuList`, `cpuListWoW64`, `videoMemorySize` | one line, ≤ 512 chars |
| Dotted | `dxwrapperConfig.<sub>`, `graphicsDriverConfig.<sub>` | sub-key `[A-Za-z0-9_]{1,40}`, value ≤ 128 chars without `,` or `=` |
| | `wincomponents.<name>` | `name` ∈ `direct3d`, `directsound`, `directinput8`, `directinput`, `directmusic`, `directplay`, `directshow`, `directx`, `vcrun2010`, `wmdecoder`, `opengl`; value `0` or `1` |
| Env | `env.set {NAME: value}`, `env.unset [NAME]` | name `[A-Za-z_][A-Za-z0-9_]{0,63}`, value one line ≤ 512 chars; each name once per request |

Not editable:

- `containerVariant` — reported by `containers.get`, changed only from the UI (`read_only`).
- `fexcoreTSOMode`, `fexcoreX87Mode`, `fexcoreMultiBlock` (`not_editable`).
- The whole `envVars`, `dxwrapperConfig`, `graphicsDriverConfig` and `wincomponents` strings — edit single
  entries through `env` and dotted keys instead.
- `WINEDEBUG` in `env` (`blocked_env`) — use the launch extras.
- Drives, controller/gesture/shooter mappings and everything else outside the key set.

Use `components.list` for valid component values. `graphicsDriverVersion` is a legacy field (the UI writes
`""`); the live driver is `graphicsDriverConfig.version`.

## Sessions

The app runs one game session at a time. The tracker follows it through these states:

| State | Entered when |
|---|---|
| `launching` | A `LAUNCH_GAME` intent passed the launch funnel and the pre-launch (cloud sync, downloads, Steam login wait) is running. UI launches skip it. |
| `starting` | Environment setup began (`setupXEnvironment`). |
| `running` | The first game window was mapped. Windows of prerequisite installers do not count. |
| `exiting` | Teardown began. |
| `exited` | The environment was shut down, or the launch failed. |

`exit_reason` values: `quick_menu`, `activity_destroyed`, `guest_terminated`, `force_close`, `playing_blocked`,
`processes_exited` (the app's exit paths), `agent_stop` (stopped through `sessions.stop`), `shutdown` (the
environment was shut down without an exit reason), `launch_error`, `launch_timeout`.

Rules:

- **`launching` timeout.** A session still `launching` after 5 minutes ends with `exit_reason=launch_timeout`
  (`exited_at` = start + 5 min).
- **Launch errors.** Most errors the app reports during a launch are non-fatal (a failed unpack, a Steam CEG
  pass, a non-zero guest exit): they only set `last_launch_error` and the game may still run. Only
  `Failed to setup wine…` ends a `launching`/`starting` session, with `exit_reason=launch_error`.
- **Pre-launch stops.** If the pre-launch of an intent launch stops before the game starts (an error dialog,
  a failed download, an exception, cancellation), the `launching` session ends with `exit_reason=launch_error`
  and `last_launch_error` "Launch aborted before the game started" (unless the app reported its own error).
  Exception: a dialog whose confirm restarts the pre-launch — sync conflict (Steam/EA/Rockstar), sync in
  progress, pending upload, app or account session active elsewhere, multiple pending operations — keeps the
  session `launching`. Confirming it continues the same session; dismissing it (or another dialog replacing
  it) ends it with `launch_error`.
- **Repeated `LAUNCH_GAME`.** While a session is `starting`, `running` or `exiting` a new intent (for any
  game) does not replace it: its wine environment is live and reused, so no new session appears. If that intent carried
  agent options, they are dropped and the kept session gets the warning
  `launch_options_ignored_session_active`. A session that is still `launching` is replaced by the new intent.
  An intent for the Steam game that is already running only brings its screen forward.
- **No session yet.** A launch that is deferred (the store service is not ready, Steam not logged in on a cold
  start) creates no session until it actually starts; a game that is not installed never does.
  `sessions.current` keeps showing the previous session meanwhile.
- **One-shot options.** Launch options belong to one session and end with it; the next launch (also from the
  UI) never inherits them.

Session `warnings`:

- `wine_debug_channels_ignored_without_agent_debug` — channels were sent without `agent_debug`;
- `wine_debug_channel_dropped:<reason>:<item>` — a channel was dropped (`invalid`, `banned`, `too_many`,
  `base`; the item is cut to 32 characters);
- `launch_options_ignored_session_active` — see above.

## Launch extras

```
adb shell am start -n app.gamenative/app.gamenative.MainActivity -a app.gamenative.LAUNCH_GAME \
  --ei app_id 123 --es game_source STEAM --ez agent_debug true --es wine_debug_channels '+d3d,+vulkan'
```

- `app_id` must be an **Int** extra (`--ei`); `game_source` is case-insensitive (an unknown value falls back to
  `STEAM`). The intent's temporary `container_config` override exists in the app but is not part of the agent
  workflow: change configs persistently with `containers.set` and roll back with `containers.restore`.
- `agent_debug` (Boolean) and `wine_debug_channels` (String, comma-separated, at most 256 chars) are read only
  while the toggle is on. Every `LAUNCH_GAME` overwrites the stored options of its game, so an extras-less
  intent clears stale ones.

With `agent_debug=true` the session gets:

- `WINEDEBUG = warn+seh,+loaddll,+process,+timestamp,+pid,+tid` (the debug-run base) plus the accepted
  channels; `DXVK_LOG_LEVEL=info`, `DXVK_LOG_PATH=none`, `VKD3D_DEBUG=warn`. They are applied after the
  container env, so they win over a `WINEDEBUG` in the container's `envVars`.
- A dedicated log file `<external files dir>/wine_logs/agent_<appId>.log`, normally
  `/storage/emulated/<user>/Android/data/<package>/files/wine_logs/agent_STEAM_123.log`, reported as
  `log_file`. It is recreated at the next `agent_debug` launch of the same game and is kept after exit; fetch it
  with `adb pull` (or `gnctl logs pull`). It is not truncated, not moved and not redacted.
- No debug-report dialog: the app's built-in debug run is not used. If an app-triggered debug run (the
  verification run after an AI suggestion) coincides with the launch, the debug run keeps its own log file and
  env and the agent's are skipped.

Channel rules (the app's `DebugRunParams` rules; gnctl applies the same rules and refuses the launch instead):

- items are trimmed and lowercased; an item without a sign gets `+`;
- pattern `^[+-]?[a-z0-9_]{1,24}$`, otherwise dropped as `invalid`;
- `all`, `relay`, `server`, `heap`, `snoop`, `file` are dropped as `banned`;
- channels the base already sets (`seh`, `loaddll`, `process`, `timestamp`, `pid`, `tid`) are dropped as `base`;
- duplicates are dropped silently; beyond 8 channels the rest are dropped as `too_many`.

Dropped channels never block the launch; they become session warnings. Channels without `agent_debug` are
ignored.

## After the game exits

- The game-feedback dialog appears after the first run of a game (the one-time Discord/support prompt) and
  after every run that followed a config change (every `containers.set`/`restore` write); custom games are
  exempt. For runs that end without a game window or within 90 s the
  app may instead offer an AI debug run (at most every 3 days per game). Dismiss them with
  `adb shell input keyevent BACK`.
- After a launch through the intent, the activity calls `finish()` when the game exits, returning to whatever
  launched it.

## Other adb features

```sh
adb exec-out screencap -p > screen.png          # screenshot (gnctl screenshot)
adb pull /storage/emulated/0/Android/data/app.gamenative/files/wine_logs/agent_STEAM_123.log
adb shell input keyevent WAKEUP                 # wake the screen before launching
```

## gnctl

See [tools/gnctl/README.md](../tools/gnctl/README.md) for the CLI: build, examples, exit codes and the full
command reference.
