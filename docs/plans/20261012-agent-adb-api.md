# Agent ADB API and gnctl CLI

## Overview
- Add ADB-based control for AI agents (Codex, Claude Code) to the GameNative fork (`nickalie/GameNative`, upstream `utkarshdalal/GameNative`): list games and components, read and persistently change launch configs (with backup and rollback), launch with verbose logging, inspect/stop the session, read Wine logs, take screenshots — without tapping through the UI and without using logcat as a control protocol.
- Launching already exists: the `app.gamenative.LAUNCH_GAME` intent (`utils/IntentLaunchManager.kt`, extras `app_id` (**Int**), `game_source`). The agent does **not** use the intent's temporary `container_config` override: settings are changed only persistently via `containers.set` (with backup) and `containers.restore`. The intent gains two extras, `agent_debug` and `wine_debug_channels` (honored only when the Agent API toggle is on); everything else is a back channel through an exported ContentProvider `${applicationId}.agent` called with `adb shell content call`.
- Host side: Rust CLI `gnctl`. Screenshots (`screencap`) and full logs (`adb pull`) are fetched by the CLI directly via adb.
- Upstream strategy: work in the fork; the user pitches the idea in Discord `#code-changes` (CONTRIBUTING.md requires approval of new features before a PR). Upstream PR = `app.gamenative.agent` package + one-line hooks + settings toggle + launch extras, without the CLI/skill. The PR is opened only after approval and with the user's confirmation.

## Context (from discovery)
- Kotlin/Compose, Hilt, Room, Java 17, compileSdk 36, kotlinter. Flavors `legacy`/`legacyXr`/`modern`/`modernXr`; Samsung S24 Ultra uses `modernDebug`. `release-gold` appends `.gold` to the applicationId → the authority and the CLI's `-n` are derived from the package name.
- Tests: `app/src/test/java/app/gamenative/...`, JUnit + Robolectric (`@Config(application = Application::class)`, no PluviaApp/Hilt) + mockk. Precedent for container-write tests: `ContainerConfigDialogContainerUpdateTest.kt:88-97` (`Container("STEAM_123456")`, `setRootDir(temp)`, `user.reg`, `PrefManager.init`, mocked `SteamService.instance`).
- ⚠️ The `XServerScreen` composable (`ui/screen/xserver/XServerScreen.kt`) sits at the dex verifier's register limit — extra parameters/locals have caused runtime VerifyErrors on device (kdoc `ui/screen/xr/ImmersiveSessionHooks.kt:6-9`). **Nothing is added to the composable body.** Hooks go into separate functions (`setupXEnvironment`, `exit`), `MainViewModel`, `PluviaMain.launchIntentApp`, `MainActivity`, `PluviaApp`, and reach the tracker through a static singleton.
- Session:
  - `setupXEnvironment(...)` (`XServerScreen.kt:~4221`, called only from `:2471` on the WineSetup thread when `xEnvironment == null`): `ProcessHelper.removeAllDebugCallbacks()` (`:4277`); `WINEDEBUG` from `debugRun`/`diagnostics`/`PrefManager.enableWineDebug`+`wineDebugChannels` (`:4283-4302`); `val captureLogs` and `logFile` name in `getExternalFilesDir()/wine_logs/` (`:4306-4313`, captured by the callback lambda); its own debug callback (`:4315`, invoked under `synchronized(debugCallbacks)`, `ProcessHelper.java:423-426`); later `envVars.putAll(container.envVars)` (`:4395`), then `DebugRunParams.applyToDebugEnv` only when `debugRun` (`:4401-4403`), then `TU_DEBUG` (`:4406`).
  - `exit(..., reason)` (`:5245`, private function outside the composable; `isExiting` check `:5257`; reasons `quick_menu`, `activity_destroyed`, `guest_terminated`, `force_close`, `playing_blocked`, `processes_exited`) → teardown → `PluviaApp.shutdownEnvironment()` (`:5296`, `PluviaApp.kt:272`; also called from `MainActivity.kt:223, 466`, `ImmersiveXrActivity.kt:499, 512`).
  - `MainViewModel.onWindowMapped` (`:961-968`) → `running`; `MainViewModel.onGameLaunchError` (`~:1044`) → `last_launch_error`.
  - Stop: `PluviaApp.events.emit(SteamEvent.ForceCloseApp)` — precedent `MainViewModel.abortBoot()` (`:1103-1112`); `ForceCloseApp` is also emitted by `SteamService.kt:4933`. `EventDispatcher.emit` is synchronous → post on the main looper.
  - The built-in debug run (`MainViewModel.exitSteamApp` `:726-733` → `DebugReportUtils.createPendingReport`) moves the log into `debug_reports/<appId>_<ts>/log.gz` (truncated) and shows `ShowDebugReportDialog` — **the agent does not use it**, so `agent_debug` bypasses `debugRun`. Channel safety already exists in `utils/DebugRunParams.kt`: `BASE_WINEDEBUG` (`:40`), `CHANNEL_PATTERN`, `BANNED_CHANNELS` (all, relay, server, heap, snoop, file), `MAX_CHANNELS=8`, `mergeWineDebug` (`:110`).
  - Post-exit dialogs: `applyToContainer` sets `config_changed=true` (`ContainerUtils.kt:633-636`) → `ShowGameFeedbackDialog` after the next exit (`MainViewModel.kt:782-791`); also `maybeOfferAiDebugRun` and the first `discord_support_prompt` (`~:760-775`). After an external launch the activity calls `finish()` on exit (`PluviaMain.kt:2186-2190`).
- Intent launch: `MainActivity.handleLaunchIntent` (cold path keeps the `LaunchRequest`; warm path `MainActivity.kt:374` emits `ExternalGameLaunch(appId)` without extras; deferral `PluviaMain.kt:629-650` rebuilds the request) → single funnel `PluviaMain.launchIntentApp` (`:491`), `finalAppId == appId` (`:261`). Relaunch protection covers only the same Steam game (`:491-503`). `MainActivity` is exported — any app can send `LAUNCH_GAME`. Pattern for a per-appId side store: `TemporaryConfigStore` in `IntentLaunchManager`.
- Games: `sync/FrontendSyncManager.FrontendSyncEntryPoint` (`:45`, 4 DAOs) + `getInstalledGames()`; custom games via `CustomGameScanner.scanAsLibraryItems()` deduplicated against Steam (`LibraryViewModel.kt:957-967`).
- Config edits — reuse the AI-suggestion pipeline:
  - validation: `api/SupportSuggestion.kt` `Companion.isValid(Change)` (`:83`) — `TOP_LEVEL_KEYS` = `SuggestionConfigKeys.APPLICABLE_CONFIG_KEYS` minus `containerVariant`, fexcore modes, whole `envVars`/`dxwrapperConfig`/`wincomponents`/`graphicsDriverConfig`; `KV_PARENTS` for dotted keys; `ENUM_VALUES`; env ops with `BLOCKED_ENV = WINEDEBUG`; length limits;
  - folding dotted keys and env ops into full values: private `SupportSuggestionApplier.updatesFor` (`:85-115`) → make `internal`;
  - applying: `ContainerUtils.applyBestConfigMapToContainerData` (`:404-466`, flat keys only) + `applyToContainer(context, container, data, saveToDisk = true)` + `SessionReport.markConfigApplied(container, source)`;
  - **not** `SupportSuggestionApplier.applyEdits` (`:191-208`, suspend, auto-downloads via `ensureComponents` → `ManifestInstaller`), **not** `IntentLaunchManager.validateContainerConfig/mergeConfigurations`;
  - component checks: `BestConfigService.resolveMissingManifestInstallRequests` (`:531`, downloadable but not installed) ∪ `parseConfigResult(...).missingComponents` (unknown versions; `validateComponentVersions` `:372-420` treats manifest entries as available);
  - hazards: some values (`videoMemorySize`, …) live in `.wine/user.reg` (`ContainerUtils.kt:270-291, 491-506`); `getGPUCards(...)[live.videoPciDeviceID]!!` (`:495`) crashes on any apply if the **current** id is unknown;
  - snapshot precedent: `SupportSuggestionApplier.snapshotFile(container, …)` (`:48`) under the container's root dir.
- Components: same sources as `BestConfigService.validateComponentVersions` (`:314-420`) — `ManifestComponentHelper.loadInstalledContentLists` + `R.array.*_entries` resources (`StringUtils.parseIdentifier`) + manifest + on modern builds dxvk/vkd3d from disk (`dxWrapperOnDiskVersions`, `bundledDxWrapperBase`); drivers = "System" + `AdrenotoolsManager`. `ManifestRepository.loadManifest` hits the network when the cache is >24h old (`ManifestRepository.kt:28-35`); offline reads use `PrefManager.componentManifestJson` + `parseManifest`. WoWBox64 versions are written to `box64Version` when `wineVersion` contains `arm64ec` (`ContainerConfigDialog.kt:803-811`, `EmulationTab.kt:113-124`). `graphicsDriverVersion` is legacy (UI sets `""`); `graphicsDriverConfig` is the live field.
- Device: `utils/SessionReport.deviceProperties/gpuProperties` (private top-level, `:64`, `:101`).
- Readiness: `PrefManager.dataStore` is `lateinit`, initialized in `PluviaApp.onCreate` (`:87`); providers are created earlier.
- Env parsing: `com.winlator.core.envvars.EnvVars`.
- Shallow clone (`--depth 1`, base `c78519c`); remotes `origin` (fork), `upstream` (not fetched).

## Development Approach
- **testing approach**: Regular (code first, then tests in the same task)
- complete each task fully before moving to the next
- make small, focused changes: new code lives in `app.gamenative.agent`; edits to existing files are one-line hooks outside the `XServerScreen` composable, each wrapped in `runCatching` so tracker failures never break `exit()`/`shutdownEnvironment()`/launch; reuse existing mechanisms (`SupportSuggestion.isValid`, `updatesFor`, `applyBestConfigMapToContainerData`, `BestConfigService`, `DebugRunParams`, `FrontendSyncEntryPoint`, `SessionReport`) instead of writing our own
- dispatcher and command dependencies are injected as functions (`isEnabled`, `isReady`, game/component/config sources, main-thread poster, clock); only the factory in `AgentProvider` touches `PrefManager`, the Hilt EntryPoint and `ContentsManager` — otherwise Robolectric tests without PluviaApp won't start
- each command is registered in the dispatcher in the task that introduces it, so it is callable through the provider right away
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change; run Gradle builds/tests longer than ~30s in the background (`run_in_background`)
- maintain backward compatibility: with the toggle off and no new extras, app behavior is unchanged

## Testing Strategy
- **unit tests (Kotlin)**: `./gradlew :app:testModernDebugUnitTest --tests 'app.gamenative.agent.*'` (+ affected existing tests) and `./gradlew :app:lintKotlin`.
- **unit tests (Rust)**: `cargo test --manifest-path tools/gnctl/Cargo.toml` + `cargo clippy`; `adb` behind an `AdbRunner` trait, faked in tests.
- **on-device smoke**: mandatory gates in Task 4 and Task 8 — hooks in `XServerScreen.kt`/`PluviaMain`/`MainActivity` and the warm/deferred launch paths can't be proven by unit tests, and VerifyErrors only show up when entering a game.
- **e2e**: the project has no UI e2e tests; the vertical scenario is in Post-Completion.

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview
- **Transport**: `adb shell content call --uri content://<pkg>.agent --method command --arg <base64url(JSON)>` → `AgentProvider.call()` → `AgentDispatcher` → Bundle `{json}`. Responses are capped at 256 KB (`RESPONSE_TOO_LARGE`) to avoid `TransactionTooLargeException`. All other provider entry points are rejected.
- **Security**: `Binder.getCallingUid()` ∈ {2000 shell, 0 root} at every entry point; "Agent API (ADB)" toggle (off by default) → `DISABLED`; before `PrefManager` is initialized → `NOT_READY`; no command can flip the toggle itself. Launch extras are ignored unless the toggle is on (exported `MainActivity`). The provider never reads account tokens/credentials; env values in responses are masked on token boundaries; lines in the log buffer are redacted (`-AUTH_PASSWORD=…`, `token=…`). The log file fetched via `adb pull` is not redacted — shell can read it anyway; documented. Shizuku apps run as UID 2000 — documented.
- **Runtime wiring**: `object AgentRuntime { val tracker: AgentSessionTracker; val launchOptions: AgentLaunchOptionsStore }` — static entry point used by all hooks.
- **Launch**: `gnctl launch` → `adb shell am start -n <pkg>/app.gamenative.MainActivity -a app.gamenative.LAUNCH_GAME --ei app_id N --es game_source SRC [--ez agent_debug true] [--es wine_debug_channels '+d3d,+vulkan']`. `MainActivity.handleLaunchIntent` overwrites the per-appId side store on **every** `LAUNCH_GAME` (empty options when no extras or toggle off), on both cold and warm paths; `PluviaMain.launchIntentApp` takes them in the real-launch branch and calls `tracker.onLaunchRequested(appId, options)`; the store entry is cleared on `NotFound`/launch error; `setupXEnvironment` reads the current session's options from the tracker.
- **`agent_debug`**: `WINEDEBUG = DebugRunParams.mergeWineDebug(BASE_WINEDEBUG, channels)` with channels filtered by the existing `DebugRunParams` rules (pattern, banned channels, max 8), plus `DXVK_LOG_LEVEL=info`, `DXVK_LOG_PATH=none`, `VKD3D_DEBUG=warn`. Two insertion points in `setupXEnvironment`: (1) before `:4306` — `AgentHooks.logFileFor(context, appId)` returns `wine_logs/agent_<appId>.log` when the session has `agent_debug` and feeds `captureLogs`/`logFile` (plus `onStarting`); (2) after `:4404` inside the `container != null` block, before `TU_DEBUG` — `AgentHooks.applyEnv(appId, envVars)`, so it wins over `container.envVars`. `debugRun` stays false → no `createPendingReport`, no log relocation, no report dialog. Options are one-shot: cleared on exit/error, never leak into the next launch.
- **Sessions**: `AgentSessionTracker` — `session_id`, states `launching → starting → running → exiting → exited`, `exit_reason`, `last_launch_error`, `warnings[]` (e.g. dropped debug channels), launch options, compact config snapshot, ring buffer of log lines, log file path. `launching` times out (5 min → `exited`, `exit_reason=launch_timeout`). `onExited` is idempotent and only acts from `starting/running/exiting`. Stop = `stopRequestedByAgent` flag (valid until this session exits) + `ForceCloseApp` on the main looper; `force_close` with the flag set → `exit_reason=agent_stop`.
- **Configs** (persistent only, same boundary as AI suggestions): `containers.get` → `containers.set` (backup) → `launch` → `containers.restore` on failure. Each requested change becomes a `SupportSuggestion.Change` checked by `isValid`; accepted changes are folded via `updatesFor`, applied via `applyBestConfigMapToContainerData` + `applyToContainer(saveToDisk=true)` + `SessionReport.markConfigApplied(container, "agent_api")`. Not changeable in v1: `containerVariant`, fexcore modes, `WINEDEBUG` env (use launch extras), drives, controller/gesture/shooter mappings. A backup is a `configValueOf` snapshot of the applicable keys stored under the container's root dir.
- **Host-side via adb**: `gnctl screenshot` = `adb exec-out screencap -p`; `gnctl logs pull` = `adb pull` of the path in `sessions.current.log_file` (`/storage/emulated/<user>/Android/data/<pkg>/files/wine_logs/agent_<appId>.log`).

## Technical Details
- Request: `{"api_version":1,"command":"games.list","args":{...}}`. Response: `{"api_version":1,"ok":true,"data":...}` / `{"api_version":1,"ok":false,"error":{"code":"...","message":"...","details":{...}?}}`.
- App error codes: `UNKNOWN_COMMAND`, `BAD_ARGS`, `UNSUPPORTED_API_VERSION`, `GAME_NOT_FOUND`, `NO_CONTAINER`, `MISSING_COMPONENTS`, `CONTAINER_INVALID`, `NO_ACTIVE_SESSION`, `SESSION_ACTIVE`, `NO_BACKUP`, `NOT_READY`, `DISABLED`, `RESPONSE_TOO_LARGE`, `INTERNAL` (command exception → `INTERNAL`, the process never crashes). CLI-only code: `SESSION_BUSY` (launch while a session is active, unless `--force`).
- v1 commands:
  - `capabilities` → `{api_version, app_version, package, commands[]}`
  - `device.info` → model, Android SDK, SoC, GPU renderer/vendor/version, Vulkan API version, free storage (`SessionReport` properties → `internal`)
  - `games.list {source?}` → installed games only: `[{app_id:"STEAM_123", game_id:123, game_source, name, has_container}]`
  - `components.list {target?}` → `[{target, value, label, installed, downloadable, variant?}]`, `target` ∈ `wineVersion`, `dxwrapperConfig.version`, `dxwrapperConfig.vkd3dVersion`, `graphicsDriverConfig.version`, `box64Version`, `fexcoreVersion` (verify exact sub-keys against `SuggestionConfigKeys`/`KV_PARENTS`); `variant` ∈ `glibc`/`bionic`/`arm64ec` (`arm64ec` = WoWBox64 values for `box64Version`). Manifest read offline from `PrefManager.componentManifestJson` + `parseManifest`; never a network call inside the binder call.
  - `containers.get {app_id}` → values for `APPLICABLE_CONFIG_KEYS` + read-only `containerVariant`, env parsed via `EnvVars` and masked (`(^|_)(TOKEN|KEY|SECRET|PASSWORD|PASS|AUTH)(_|$)`); no container (game never launched) → `NO_CONTAINER`
  - `containers.set {app_id, values:{key: value}, env?:{set:{}, unset:[]}}`:
    - each entry → `SupportSuggestion.Change`, rejected by `isValid` → `BAD_ARGS` (`details.rejected[]`)
    - current `videoPciDeviceID` not in `getGPUCards` → `CONTAINER_INVALID` (pre-check, avoids the `!!` crash)
    - components: `resolveMissingManifestInstallRequests` ∪ `parseConfigResult(...).missingComponents` non-empty → `MISSING_COMPONENTS` (`details[]` with `downloadable`), no auto-download; may hit the network if the manifest cache is stale (documented; `components.list` doesn't refresh it)
    - active session for this game (`launching..exiting`) → `SESSION_ACTIVE`
    - backup → apply → `{backup_id, changed_fields[], warnings[]}`; `warnings`: `wine_version_changed` (prefix may be upgraded and `restore` won't undo that), `needs_unpacking` (executablePath/launch*Steam/unpackFiles). The feedback dialog after the next exit is documented, not a warning.
  - `containers.restore {app_id, backup_id? | baseline:true}` → writes only keys whose snapshot value differs from the current one; full values (`envVars`, `dxwrapperConfig`, …) are written directly (bypassing `isValid`, since they came from the app itself); `containerVariant` never touched; the rollback itself creates a backup; no backup → `NO_BACKUP`
  - `containers.backups {app_id}` → `[{backup_id, created_at, baseline}]`
  - Backups: `<container.rootDir>/.gamenative/agent_backups/<ts>.json` (die with the container); the first backup is pinned as `baseline` and never rotated; the rest keep the last 10.
  - `sessions.current` → `null` | `{session_id, app_id, state, started_at, running_at?, exited_at?, exit_reason?, last_launch_error?, warnings[], agent_debug, wine_debug_channels?, log_file?, config:{wineVersion, containerVariant, dxwrapper, dxwrapperConfig, graphicsDriver, graphicsDriverConfig, box64Version, emulator}}`; with no current session — the last finished one
  - `sessions.stop` → flag + `ForceCloseApp` via the main-thread poster; not in `starting/running` → `NO_ACTIVE_SESSION`; in `starting` before the view exists it may not take effect (document "retry")
  - `sessions.logs {tail?=200}` → `{lines[], truncated, log_file?}` from the buffer (~2000 lines, O(1) `onLogLine`, response byte cap)
- Launch extras (optional, toggle on only): `agent_debug: Boolean`, `wine_debug_channels: String` (comma-separated, filtered by `DebugRunParams` rules; dropped channels → session `warnings`, the launch proceeds). The CLI applies the same rules upfront.
- `gnctl`: global `--serial`, `--adb PATH`, `--package` (default `app.gamenative`). `app_id` is `STEAM_123` or `123 --source STEAM`. Before `launch` the CLI reads `sessions.current`: active → `SESSION_BUSY` (bypass with `--force`); it remembers `session_id` and with `--wait` waits for a **new** session. Exit codes: `0` ok, `2` adb, `3` protocol/CLI, `4` app error / `exited` while waiting for `running` / `last_launch_error` / session vanished, `5` timeout. Output is JSON on stdout.

## What Goes Where
- **Implementation Steps** (`[ ]` checkboxes): code in the fork, tests, API docs and the skill.
- **Post-Completion** (no checkboxes): vertical scenario on the S24 Ultra, recording for the PR, Discord, upstream PR, cleanup of `ng-gamehub/open-source`.

## Implementation Steps

### Task 1: Protocol and command dispatcher

**Files:**
- Create: `app/src/main/java/app/gamenative/agent/AgentProtocol.kt`
- Create: `app/src/main/java/app/gamenative/agent/AgentDispatcher.kt`
- Create: `app/src/test/java/app/gamenative/agent/AgentDispatcherTest.kt`

- [x] `AgentProtocol.kt`: `AgentRequest`, `AgentResponse`, `AgentError(code, message, details?)`, error code enum, `API_VERSION = 1`, base64url → JSON with a request size limit
- [x] `AgentDispatcher.kt`: `command → handler` registry, `capabilities`, version check, injected `isEnabled`/`isReady` (→ `DISABLED`/`NOT_READY`), exception capture → `INTERNAL`, response size cap → `RESPONSE_TOO_LARGE`
- [x] success tests: `capabilities`, routing, response shape
- [x] error tests: broken base64/JSON, foreign version, unknown command, `DISABLED`, `NOT_READY`, exception → `INTERNAL`, oversized response
- [x] run tests - must pass before Task 2
- ⚠️ `:app:lintKotlin` fails on ~11.9k pre-existing errors across the repo (baseline debt); gate = no lint errors in the new/changed files (check the lint log with `grep /agent/`). Gradle needs JDK 17 (`JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`; system JDK 27 is unsupported) and a gitignored `local.properties` with `sdk.dir`.

### Task 2: Session tracker and runtime singleton

**Files:**
- Create: `app/src/main/java/app/gamenative/agent/AgentSessionTracker.kt`
- Create: `app/src/main/java/app/gamenative/agent/AgentLaunchOptions.kt` (options + per-appId store)
- Create: `app/src/main/java/app/gamenative/agent/AgentRuntime.kt`
- Create: `app/src/main/java/app/gamenative/agent/LogRedactor.kt`
- Create: `app/src/test/java/app/gamenative/agent/AgentSessionTrackerTest.kt`
- Create: `app/src/test/java/app/gamenative/agent/AgentLaunchOptionsTest.kt`

- [x] `AgentSessionTracker`: `onLaunchRequested(appId, options)`, `optionsFor(appId)`, `onStarting(appId, container, logFile?)`, `onRunning()`, `onExiting(reason)`, `onExited()`, `onLaunchError(msg)`, `addWarning(w)`, `requestStop()`, `onLogLine(line)`, `current()`, `isActive(appId)`; `session_id`, injected clock, `launching` timeout, thread safety, compact config snapshot
- [x] `AgentLaunchOptionsStore`: overwrite-on-every-intent semantics, take-once, clear on `NotFound`/error; `AgentRuntime` singleton exposing tracker + store
- [x] `LogRedactor`: patterns `-AUTH_PASSWORD=\S+`, `token=\S+`, etc.; applied in `onLogLine`
- [x] transition tests: full cycle; `starting` without `launching` (UI launch); `onExited` idempotent and ignored from `launching`/no session (stale `shutdownEnvironment`); new session replaces a finished one; `force_close` + flag → `agent_stop`, without flag → `force_close`; flag doesn't outlive the session; `launch_timeout`; `last_launch_error`; options one-shot; store overwrite clears stale options
- [x] buffer and redaction tests: overflow, `tail`, `truncated`, secrets removed
- [x] run tests - must pass before Task 3

### Task 3: ContentProvider, readiness, toggle and sessions.current

**Files:**
- Create: `app/src/main/java/app/gamenative/agent/AgentProvider.kt`
- Create: `app/src/main/java/app/gamenative/agent/commands/SessionCommands.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/app/gamenative/PrefManager.kt`
- Modify: `app/src/main/java/app/gamenative/PluviaApp.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/app/gamenative/agent/AgentProviderTest.kt`

- [x] `AgentProvider`: `call(method="command", arg)` → UID check → dispatcher → Bundle `json`; `query/insert/update/delete/getType/openFile/openTypedAssetFile` → UID check + reject; dependency factory
- [x] manifest: `<provider android:authorities="${applicationId}.agent" android:exported="true">`
- [x] `PrefManager.agentApiEnabled` (default `false`); readiness flag set in `PluviaApp.onCreate` after `PrefManager.init`; `SettingsSwitch` "Agent API (ADB)" with a subtitle about ADB/Shizuku access
- [x] `sessions.current` registered (needed for the Task 4 smoke gate)
- [x] tests (Robolectric): allowed UID → response; foreign UID → `SecurityException` at every entry point; before readiness → `NOT_READY`; disabled → `DISABLED`; `sessions.current` with no session → `null`
- [x] run tests and `:app:lintKotlin` - must pass before Task 4 (agent tests green; lint: no errors in new files or changed lines, repo-wide baseline debt unchanged)

### Task 4: Session hooks (outside the XServerScreen composable)

**Files:**
- Create: `app/src/main/java/app/gamenative/agent/AgentHooks.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` (only `setupXEnvironment` and `exit`)
- Modify: `app/src/main/java/app/gamenative/PluviaApp.kt`
- Modify: `app/src/main/java/app/gamenative/ui/model/MainViewModel.kt`
- Modify: `app/src/test/java/app/gamenative/agent/AgentSessionTrackerTest.kt`

- [x] `AgentHooks`: thin `runCatching` wrappers over `AgentRuntime.tracker` used by every hook
- [x] `setupXEnvironment`: before `:4306` → `AgentHooks.onStarting(appId, container)` (log file hook added in Task 8); in the callback registered after `removeAllDebugCallbacks()` (`:4315`) → `AgentHooks.onLogLine(line)`
- [x] `exit(...)` after the `isExiting` check (`:5257`) → `onExiting(reason)`; `PluviaApp.shutdownEnvironment()` → `onExited()`
- [x] `MainViewModel.onWindowMapped` (when `gameWindowSeen`) → `onRunning()`; `onGameLaunchError` → `onLaunchError`
- [x] tests: hook sequences in real call order (UI launch, `guest_terminated`, `processes_exited`, `agent_stop`, launch error, extra `shutdownEnvironment` from `MainActivity.onCreate`); a throwing tracker doesn't propagate out of `AgentHooks`
- [x] `./gradlew :app:assembleModernDebug` (background) + tests + `:app:lintKotlin`
- [x] ⚠️ on-device smoke gate: install `modernDebug`, enable the toggle, enter a game from the UI and exit — no VerifyError/crash; `adb shell content call … sessions.current` shows `starting → running → exiting → exited` (skipped - no device attached; `adb devices` lists none)

### Task 5: Read commands: device, games, components, containers.get

**Files:**
- Create: `app/src/main/java/app/gamenative/agent/commands/DeviceCommands.kt`
- Create: `app/src/main/java/app/gamenative/agent/commands/GameCommands.kt`
- Create: `app/src/main/java/app/gamenative/agent/commands/ComponentCommands.kt`
- Create: `app/src/main/java/app/gamenative/agent/commands/ContainerCommands.kt`
- Modify: `app/src/main/java/app/gamenative/utils/SessionReport.kt` (`deviceProperties`/`gpuProperties` → `internal`)
- Create: `app/src/test/java/app/gamenative/agent/ReadCommandsTest.kt`

- [x] `device.info` via `SessionReport` properties + `StatFs`
- [x] `games.list`: `FrontendSyncEntryPoint` + `getInstalledGames()` of all DAOs + `CustomGameScanner.scanAsLibraryItems()` with deduplication; `has_container`; `source` filter
- [x] `components.list`: sources as in `BestConfigService.validateComponentVersions` with an offline manifest read; `target`/`variant` (incl. `arm64ec`)/`installed`/`downloadable`; `target` filter
- [x] `containers.get`: values for `APPLICABLE_CONFIG_KEYS` + read-only `containerVariant`, env masking, `NO_CONTAINER`
- [x] register the four commands in the dispatcher
- [x] tests: filters, unknown source/target → `BAD_ARGS`, custom/Steam dedup, masking doesn't hit `*_BYPASS*`/`*PASSTHROUGH*`, `NO_CONTAINER`, components never call the network
- [x] run tests - must pass before Task 6
- ➕ `commands/CommandArgs.kt`: shared argument parsing (`app_id` shape `<SOURCE>_<digits>`, optional strings, `game_source`) for this and later command tasks

### Task 6: containers.set / restore / backups

**Files:**
- Modify: `app/src/main/java/app/gamenative/agent/commands/ContainerCommands.kt`
- Create: `app/src/main/java/app/gamenative/agent/ContainerEdits.kt`
- Create: `app/src/main/java/app/gamenative/agent/ContainerBackups.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/support/SupportSuggestionApplier.kt` (`updatesFor` → `internal`)
- Create: `app/src/test/java/app/gamenative/agent/ContainerEditsTest.kt`
- Create: `app/src/test/java/app/gamenative/agent/ContainerWriteCommandsTest.kt`

- [x] `ContainerEdits`: JSON `values`/`env` → `SupportSuggestion.Change` list → `isValid` (rejections collected) → edits → `updatesFor` → `applyBestConfigMapToContainerData`; `changed_fields` and `warnings` from a `configValueOf` diff
- [x] `ContainerBackups`: `configValueOf` snapshot under `<rootDir>/.gamenative/agent_backups/`, pinned `baseline`, keep last 10, listing; restore computes only differing keys and writes full values directly
- [x] `containers.set`: `SESSION_ACTIVE` → `isValid` → `videoPciDeviceID` pre-check (`CONTAINER_INVALID`) → component check (`MISSING_COMPONENTS`) → backup → `applyToContainer(saveToDisk=true)` + `markConfigApplied(container, "agent_api")`
- [x] `containers.restore` (by `backup_id` or `baseline`, creates a backup itself) and `containers.backups`; register all three commands
- [x] codec tests: dotted keys (`dxwrapperConfig.version`, `graphicsDriverConfig.version`), env set/unset, `WINEDEBUG` env rejected, `containerVariant` rejected, unknown key → `BAD_ARGS` with `details.rejected`, warnings (`wine_version_changed`, `needs_unpacking`)
- [x] command tests (precedent `ContainerConfigDialogContainerUpdateTest`): set A → set B → restore baseline = original; restore touches only differing keys and creates a backup; rotation never removes the baseline; `MISSING_COMPONENTS` writes nothing and creates no backup; unknown current `videoPciDeviceID` → `CONTAINER_INVALID`; `SESSION_ACTIVE` while `launching`; `NO_BACKUP`
- [x] run tests - must pass before Task 7

### Task 7: sessions.stop and sessions.logs

**Files:**
- Modify: `app/src/main/java/app/gamenative/agent/commands/SessionCommands.kt`
- Create: `app/src/test/java/app/gamenative/agent/SessionCommandsTest.kt`

- [x] `sessions.logs {tail}` with a byte cap
- [x] `sessions.stop`: `requestStop()` + `ForceCloseApp` via the injected main-thread poster; `NO_ACTIVE_SESSION` outside `starting/running`
- [x] register both commands; `capabilities` lists every v1 command
- [x] tests: logs tail/cap, stop without a session, stop posts the event via the poster, repeated stop is idempotent
- [x] run tests - must pass before Task 8

### Task 8: Launch extras agent_debug and wine_debug_channels

**Files:**
- Modify: `app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt`
- Modify: `app/src/main/java/app/gamenative/MainActivity.kt` (`handleLaunchIntent`)
- Modify: `app/src/main/java/app/gamenative/ui/PluviaMain.kt` (`launchIntentApp`)
- Modify: `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` (only `setupXEnvironment`)
- Modify: `app/src/main/java/app/gamenative/agent/AgentHooks.kt`
- Modify (visibility if needed): `app/src/main/java/app/gamenative/utils/DebugRunParams.kt`
- Create: `app/src/main/java/app/gamenative/agent/AgentDebugEnv.kt`
- Create: `app/src/test/java/app/gamenative/agent/AgentDebugEnvTest.kt`
- Create: `app/src/test/java/app/gamenative/utils/IntentLaunchManagerTest.kt`

- [x] `IntentLaunchManager`: parse `agent_debug` (`getBooleanExtra`) and `wine_debug_channels`; empty options when the toggle is off
- [x] `MainActivity.handleLaunchIntent`: overwrite the side store on every `LAUNCH_GAME` (cold and warm); `PluviaMain.launchIntentApp` real-launch branch → `onLaunchRequested(appId, options)`; clear on `NotFound`/error
- [x] `AgentDebugEnv` (pure): channel filtering via `DebugRunParams` rules (dropped → warnings), `mergeWineDebug(BASE_WINEDEBUG, …)`, DXVK/VKD3D vars
- [x] `setupXEnvironment`: before `:4306` → `AgentHooks.logFileFor(context, appId)` feeds `captureLogs`/`logFile` and the tracker's `log_file`; after `:4404` (inside `container != null`, before `TU_DEBUG`) → `AgentHooks.applyEnv(appId, envVars)`
- [x] tests: parsing; toggle off → no options; banned/invalid/too many channels → dropped + warnings; env precedence over `WINEDEBUG` from container env; one-shot (next launch has no agent env); store overwrite on an extras-less intent
- [x] `modernDebug` build + tests + `:app:lintKotlin` (agent tests green; lint: no errors in new files or changed lines)
- [x] ⚠️ on-device smoke: `am start … --ez agent_debug true` with the app closed and with it open (warm path), and once with Steam not logged in (deferral) → `sessions.current.agent_debug=true`, `log_file` = `…/wine_logs/agent_<appId>.log`, the file remains after exit and `adb pull` fetches it, no report dialog; the next UI launch has no agent-debug env (skipped - no device attached; `adb devices` lists none)
- ➕ channels sent without `agent_debug` are ignored (session warning `wine_debug_channels_ignored_without_agent_debug`); dropped channels → `wine_debug_channel_dropped:<reason>:<item>`; when an app-triggered debug run (pending AI-applied verification run) coincides with an agent launch, the debug run keeps its log file and env and the agent log/env are skipped

### Task 9: gnctl crate — core and transport

**Files:**
- Create: `tools/gnctl/Cargo.toml`
- Create: `tools/gnctl/src/main.rs`
- Create: `tools/gnctl/src/adb.rs`
- Create: `tools/gnctl/src/protocol.rs`
- Create: `tools/gnctl/src/error.rs`
- ➕ Create: `tools/gnctl/src/lib.rs` (library + thin `main.rs`, so Task 10 helpers are not dead code under `clippy -D warnings`)
- Modify: `.gitignore` (`tools/gnctl/target/`)

- [x] `AdbRunner` trait + implementation via `std::process::Command` (`--serial`/`ANDROID_SERIAL`, `--adb`), binary-safe `exec-out`
- [x] `protocol.rs`: request/response types (`serde`), base64url, parsing `content call` output (`Result: Bundle[{json=...}]`), authority from `--package`
- [x] `error.rs`: error → exit code mapping 2/3/4/5, JSON error on stdout
- [x] tests with a fake `AdbRunner`: arguments, Bundle parsing, adb errors → 2, garbage → 3, `ok=false` → 4
- [x] `cargo test` and `cargo clippy` - must pass before Task 10
- ➕ raw `gnctl call <command> [--args JSON]` subcommand (exercises the transport end to end; Task 10 builds the wrappers on top); clap parse errors exit 3 (not clap's default 2, which means adb)

### Task 10: gnctl commands

**Files:**
- Create: `tools/gnctl/src/cli.rs`
- Create: `tools/gnctl/src/commands.rs`
- Modify: `tools/gnctl/src/main.rs`
- ➕ Modify `tools/gnctl/src/lib.rs` instead of `main.rs` (Task 9 split: `main.rs` stays a one-liner); ➕ Create `tools/gnctl/src/launch.rs` (launch, waits, injectable clock) and `tools/gnctl/src/channels.rs` (WINEDEBUG channel rules); `error.rs` gains `IO_ERROR` (exit 3) for local file errors

- [x] wrapper subcommands: `capabilities`, `device info`, `games list`, `components list`, `containers get|set (FILE|-)|restore [--backup ID|--baseline]|backups`, `sessions current|stop|logs`
- [x] `launch <app_id> [--source] [--debug] [--wine-debug CH] [--wait running|exited --timeout N] [--force]`: `-n <pkg>/app.gamenative.MainActivity`, `--ei app_id`, channel rules applied before calling adb, mandatory BUSY check, waits for a new `session_id`
- [x] `sessions wait --until running|exited --timeout N`; `screenshot -o FILE` (`exec-out screencap -p`); `logs pull [-o FILE]` (`adb pull` of `sessions.current.log_file`)
- [x] tests: `STEAM_123` and `123 --source`, `--ei`/`-n`, `containers set` from file and stdin, banned channels → 3 without calling adb, BUSY/`--force`, waiting for a new id, session vanished → 4, timeout → 5, early `exited` → 4, screenshot/pull write the file, pull without `log_file` → clear error
- [x] `cargo test` and `cargo clippy` - must pass before Task 11
- ➕ CLI-side codes (exit 4): `SESSION_BUSY`, `SESSION_EXITED`, `SESSION_VANISHED`, `NO_ACTIVE_SESSION` (`sessions wait` with no session), `NO_LOG_FILE`; channels the app would drop (invalid/banned/too many/already in `BASE_WINEDEBUG`) are refused with exit 3; `--wine-debug` requires `--debug`; a bare numeric app id requires `--source`; `--force --wait` over a `starting`/`running`/`exiting` session → `SESSION_BUSY` (the app keeps that session, no new one would appear); `NOT_READY` is retried (15 s before launch, for the whole wait while waiting)

### Task 11: API docs and agent skill

**Files:**
- Create: `docs/agent-api.md`
- Create: `tools/gnctl/README.md`
- Create: `.claude/skills/gamenative-adb/SKILL.md`

- [x] `docs/agent-api.md`: enabling, transport, security model (Shizuku, toggle-gated extras, unredacted log file), commands, codes, editable keys (= AI-suggestion boundary) and `warnings`, launch extras and channel rules, stale-manifest network note, raw `content call`/`am start` examples
- [x] `tools/gnctl/README.md`: build (`cargo build --release`), examples, exit codes
- [x] `gamenative-adb` skill: loop "games/components list → containers get → containers set → launch --debug --wait running → screenshot → logs / logs pull → stop → containers restore (--baseline) on failure"; rule: never leave a game on an experimental config; `NO_CONTAINER` → one normal launch first; `wineVersion` changes may irreversibly upgrade the prefix; `containerVariant` is UI-only; post-exit dialogs (feedback, AI debug-run offer, first Discord prompt — `input keyevent BACK`); `finish()` after external launch; `input keyevent WAKEUP` and keyguard; deferred launch without Steam login; `stop` during `starting` → retry (⚠️ deviation: documented as implemented — the stop waits on the main thread until the Wine environment exists, no retry needed)
- [x] snapshot test of `gnctl --help` so docs don't drift from the CLI
- [x] `cargo test` - must pass before Task 12
- ➕ snapshot = `tools/gnctl/tests/help_snapshot.rs`: the `--help` of `gnctl` and every subcommand is embedded in `tools/gnctl/README.md` between `BEGIN/END GENERATED HELP` markers; regenerate with `GNCTL_UPDATE_HELP=1 cargo test --test help_snapshot`

### Task 12: Verify acceptance criteria
- [x] all v1 commands and launch extras implemented and listed in `capabilities`; toggle off → `DISABLED` everywhere and launch extras ignored — `AgentProviderTest.capabilities_listsEveryV1Command` (production dispatcher lists exactly the v1 command set), `AcceptanceEdgeCasesTest.toggleOffDisablesEveryListedCommand` (toggle off → `DISABLED` for every command `capabilities` lists), `IntentLaunchManagerTest` toggle-off case
- [x] edge cases (each mapped to an existing test; `AcceptanceEdgeCasesTest` added for secrets in `containers.set/restore/backups/get` responses): foreign UID, cold start, no session, repeated stop, stuck `launching`, rejected `containers.set` keys, `MISSING_COMPONENTS`, `CONTAINER_INVALID`, oversized response, command crash → `INTERNAL`, secrets never appear in responses or the log buffer
- [x] full suite: `./gradlew :app:testModernDebugUnitTest :app:lintKotlin` (background) and `cargo test --manifest-path tools/gnctl/Cargo.toml`
  - ⚠️ the single-JVM run hangs on base c78519c too (CrashHandler recursion / PostHog lock from the 78 Robolectric tests that boot the real `PluviaApp`); run in package batches instead (counts below are for a3bb46b; bf3c227/8ecb1c5 were not part of these batches): 248 classes, 2317 tests, 8 failures — all pre-existing on base (7 `WorkshopManagerTest` lateinit `dataStore`, 1 `ModArchiveIndexPerformanceTest` timing); lintKotlin 11,903 = base, the only hit on a changed line (`XServerScreen.kt:2546` indent) is the same error the replaced line had on base; cargo 73+2 tests and clippy clean on a3bb46b; at HEAD (with bf3c227/8ecb1c5): cargo 76+2 tests, clippy `--all-targets -D warnings` clean, `app.gamenative.agent.*` filter 227/227 committed tests in 11 classes pass (HEAD after the review fix)
- [x] `./gradlew :app:assembleModernDebug` (background)
- [x] `git diff c78519c --stat` outside `agent/`, `tools/gnctl/`, `docs/`, `.claude/` — only hooks, manifest, pref, setting, strings, `IntentLaunchManager`, `MainActivity`, `PluviaMain`, visibility changes in `SessionReport`/`SupportSuggestionApplier`/`DebugRunParams`
  - ⚠️ c78519c..a3bb46b also touches `PluviaApp` and `MainViewModel` (hook call sites) and adds `PreInstallStepGate.kt` + its test (Task 7 review fix, exit gate for the pre-install chain in `XServerScreen`)

### Task 13: [Final] Update documentation
- [x] short Agent API section in the fork's README (leave upstream sections untouched)
- [x] fork CLAUDE.md: `modernDebug` build, tests, no edits to the `XServerScreen` composable, where agent code lives, reused config/debug mechanisms
- [x] move this plan to `docs/plans/completed/` (done by the harness after the run)

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**
- Samsung Galaxy S24 Ultra, `modernDebug`, "Agent API (ADB)" on: `gnctl games list` → `gnctl components list --target dxwrapperConfig.version` → `gnctl containers get STEAM_<id>` → `gnctl containers set STEAM_<id> dxvk-a.json` → `gnctl launch STEAM_<id> --debug --wait running` → `gnctl screenshot -o a.png` → `gnctl sessions logs` → `gnctl sessions stop` → `gnctl sessions wait --until exited` → `gnctl logs pull` → dismiss post-exit dialogs if shown → `gnctl containers set … dxvk-b.json` → repeat → `gnctl containers restore --baseline` → `containers get` matches the original.
- Check `adb pull` from `Android/data/<pkg>/files/wine_logs/` on the S24U's Android/One UI version (including the multi-user path); if not accessible → ➕ serve the log file through the provider's `openFile`.
- Screen off / keyguard: `am start` behavior.
- Post-exit dialogs after `finish()` on external launch — do they pop up on the next UI open.
- A third-party app gets `SecurityException`; a third-party `LAUNCH_GAME` with `agent_debug` while the toggle is off has no effect.
- Record a screen capture/GIF of the scenario — required for the PR.

**External:**
- The user posts the proposal in Discord `#code-changes` (text based on `docs/agent-api.md`).
- After approval — upstream PR from a separate branch without `tools/gnctl`, `.claude/`, following `.github/pull_request_template.md`; `git fetch upstream master` and rebase first; opened only with the user's confirmation.
- In `ng-gamehub/open-source/` the Kotlin scaffold and Python CLI become obsolete — removal is a separate step with the user's confirmation; `docs/open-source-plan.md` stays as a historical document.
