# GameNative (fork) — notes for agents

## Build

- Gradle 8.12 fails with a bare error under a newer system Java. Always set
  `JAVA_HOME=$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`.
- A gitignored `local.properties` with `sdk.dir=...` is required (the secrets-gradle plugin reads it;
  `STEAMGRIDDB_API_KEY` is optional).
- Dev build: `./gradlew :app:assembleModernDebug` (~3 min) → `app/build/outputs/apk/modern/debug/`.

## Tests

- Agent API tests (the last two filters cover `IntentLaunchManager.parseAgentOptions` and
  `PreInstallStepGate.kt`, which live outside the agent package):
  ```sh
  ./gradlew :app:testModernDebugUnitTest --tests 'app.gamenative.agent.*' --tests 'app.gamenative.utils.IntentLaunchManagerTest' --tests 'app.gamenative.ui.screen.xserver.PreInstallStepGateTest'
  ```
- Do not run the whole `:app:testModernDebugUnitTest` in one JVM: it hangs (also on upstream master) —
  ~78 Robolectric classes boot the real `PluviaApp`, which ends in `CrashHandler.uncaughtException` →
  `saveCrashToFile` recursion or a `PostHog.register` lock in `PluviaApp.onCreate`. Run per-package batches
  (`--tests 'app.gamenative.utils.*'`, `…ui.*`, …), each under `timeout`, in the background.
- Known pre-existing failures: `WorkshopManagerTest` (order-dependent `PrefManager` lateinit `dataStore`),
  `ModArchiveIndexPerformanceTest` (timing budget).
- `:app:lintKotlin` is red repo-wide (~11,900 pre-existing errors). The gate is "no new errors on touched
  lines", not a green task.
- `tools/gnctl` (Rust host CLI): `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`,
  `cargo test`. `tests/help_snapshot.rs` compares the generated help block in `tools/gnctl/README.md` with
  `--help`; regenerate with `GNCTL_UPDATE_HELP=1 cargo test --test help_snapshot`.

## XServerScreen

The `XServerScreen` composable (`ui/screen/xserver/XServerScreen.kt`) sits at the dex verifier's register
limit: extra parameters or locals in its body cause runtime `VerifyError`s on device that compile clean
(see the kdoc in `ui/screen/xr/ImmersiveSessionHooks.kt`). Do not add anything to the composable body.
Hooks go into the separate functions (`setupXEnvironment`, its nested `chainPreInstallSteps`, `exit`),
`PreInstallStepGate.kt`, or call into `app.gamenative.agent.AgentHooks`. The only agent change inside the
composable replaces an existing argument in place (`AgentHooks.wineSetupFailedMessage`), no new locals.

## Stores (Settings → Stores)

- Per-store switches: `data/StoreToggles` (state flow, default Steam + Custom Games on), runtime start/stop in
  `service/StoreServices`. GOG/Epic/Amazon `start()`/`triggerLibrarySync()` refuse while their store is off;
  Steam is gated at its start sites in `PluviaMain`. Library filtering goes through
  `StoreToggles.librarySources`, tabs through `LibraryTab.store`/`visibleFor`. A store that is off keeps its
  data and credentials.

## Agent API (ADB)

- App code: `app/src/main/java/app/gamenative/agent/` (`AgentProvider` content provider, `AgentDispatcher`,
  `AgentSessionTracker`, `AgentHooks` — the one entry point for hooks in existing files, `commands/`);
  tests in `app/src/test/java/app/gamenative/agent/`, plus `utils/IntentLaunchManagerTest` and
  `ui/screen/xserver/PreInstallStepGateTest` (see Tests).
- Toggle: `PrefManager.agentApiEnabled`, UI in `SettingsGroupDebug`. Launch extras (`agent_debug`,
  `wine_debug_channels`) are parsed in `IntentLaunchManager.parseAgentOptions`.
- Hook call sites outside the package: `MainActivity`, `PluviaApp`, `MainViewModel`, `PluviaMain`,
  `XServerScreen` (`setupXEnvironment`, `exit`). Keep them one-line calls into `AgentHooks` /
  `AgentPreLaunch`.
- Reuse existing mechanisms instead of writing new ones: `SupportSuggestion.isValid` is the editable-key
  boundary for `containers.set`, applied via `SupportSuggestionApplier.updatesFor` and
  `ContainerUtils.applyBestConfigMapToContainerData`; `BestConfigService.parseConfigResult` /
  `resolveMissingManifestInstallRequests` find missing or downloadable components; `DebugRunParams`
  (`mergeWineDebug`, `BASE_WINEDEBUG`, `MAX_CHANNELS`) builds `WINEDEBUG` for `agent_debug` launches.
- Host CLI: `tools/gnctl/`. Docs: `docs/agent-api.md` (protocol reference — update it with any command
  change), `tools/gnctl/README.md`, skill `.claude/skills/gamenative-adb/SKILL.md`.
