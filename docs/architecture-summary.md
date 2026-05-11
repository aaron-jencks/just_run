# Just Run Codebase Summary

## Big Picture

This repo is really two apps:
- `app/`: the phone app, which owns the run session, history, settings, widgets, and the main merge logic for daily activity data.
- `wear/`: the Wear OS companion, which shows live run data, sends run control commands back to the phone, samples watch-local health data, and serves watch complications.

At a high level:
- the phone is the source of truth for active runs
- the watch is a live remote display/controller for runs
- both devices maintain their own local daily activity readings
- the phone merges daily activity snapshots for phone UI and widgets
- the watch keeps its own local health snapshot for watch UI and complications

## Top-Level Entry Points

### Phone

- [`MainActivity.kt`](../app/src/main/java/com/example/justrun/MainActivity.kt)
  - Compose UI entry point
  - drives screen navigation between home, setup, settings, running, and summary
- [`AppGraph.kt`](../app/src/main/java/com/example/justrun/AppGraph.kt)
  - app singleton bootstrap
  - builds repositories/controllers
  - starts daily monitoring and Wear sync
- [`TrackingController.kt`](../app/src/main/java/com/example/justrun/TrackingController.kt)
  - small command gateway between UI and `RunTrackingService`
- [`RunTrackingService.kt`](../app/src/main/java/com/example/justrun/tracking/RunTrackingService.kt)
  - the real engine for an active run

### Watch

- [`WearMainActivity.kt`](../wear/src/main/java/com/example/justrun/wear/WearMainActivity.kt)
  - live watch UI
  - requests permissions
  - starts/stops watch HR service as needed
- [`WearDataListenerService.kt`](../wear/src/main/java/com/example/justrun/wear/WearDataListenerService.kt)
  - receives phone -> watch Data Layer updates
  - updates live watch run state
  - opens the watch live screen when a new run arrives
- [`WearHeartRateService.kt`](../wear/src/main/java/com/example/justrun/wear/WearHeartRateService.kt)
  - watch-local foreground service for heart rate and step sampling
- [`HealthComplicationServices.kt`](../wear/src/main/java/com/example/justrun/wear/HealthComplicationServices.kt)
  - watch-face complication data sources

## Core State Models

The most important data classes live in [`AppModels.kt`](../app/src/main/java/com/example/justrun/AppModels.kt):

- `SettingsState`
  - profile data
  - unit system
  - GPS / HR / auto-pause toggles
  - lap settings
  - voice cue settings
  - daily goals
- `ActiveRunState`
  - all mutable live-run state
  - elapsed time, distance, pace, calories, heart rate, cadence
  - lap configuration and lap splits
  - paused / autoPaused flags
  - GPS and HR enablement for this run
  - route points
- `RunRecord`
  - persisted historical run
- `TrackingSession`
  - phone-level active session wrapper
  - includes current `activeRun` and an optional `completedRunId`

## Phone Architecture

### `AppGraph`

[`AppGraph.kt`](../app/src/main/java/com/example/justrun/AppGraph.kt) is the central bootstrapper.

It creates:
- `SettingsRepository`
- `RunRepository`
- `DailyActivityRepository`
- `TrackingController`

It also starts:
- phone daily step monitoring
- Wear sync via `WearSyncManager.start(...)`
- a minute-based phone-side daily calorie refresh loop

That calorie refresh loop recomputes phone-local daily calories from:
- a resting calorie estimate for today
- calories from completed runs today
- calories from the currently active run

### `SettingsRepository`

[`SettingsRepository.kt`](../app/src/main/java/com/example/justrun/SettingsRepository.kt) persists a `SettingsState` into `SharedPreferences` and exposes it as a `StateFlow`.

Key traits:
- settings are loaded once into a `MutableStateFlow`
- `update(...)` writes to prefs, updates the flow, and triggers widget refresh
- some settings are sanitized on load
  - example: distance goals and auto-pause are constrained if GPS is off
- voice cue metric order/enabled lists are normalized on load so new enum values do not disappear from old installs

Important implementation note:
- `watchMirroring` is persisted and editable in the settings UI
- but the current Wear sync code does not branch on it, so it is effectively unused today

### `RunRepository`

[`RunRepository.kt`](../app/src/main/java/com/example/justrun/RunRepository.kt) persists historical runs to `filesDir/runs.json`.

Responsibilities:
- load run history on startup
- add a run when tracking stops
- delete runs
- convert `RunRecord` <-> JSON

There is no Room database here. Persistence is plain JSON.

### `DailyActivityRepository`

[`DailyActivityRepository.kt`](../app/src/main/java/com/example/justrun/DailyActivityRepository.kt) owns the phone-side daily steps/calories/HR snapshot used by:
- the phone home screen
- Android widgets
- phone -> watch daily sync requests

Important design:
- phone step tracking uses `TYPE_STEP_COUNTER`
- the repo keeps both:
  - local phone values
  - remote watch values
- it resolves those into a single `DailyActivitySnapshot`

Internally it stores:
- local steps + timestamp
- remote steps + timestamp
- local calories + timestamp
- remote calories + timestamp
- remote heart rate + timestamp
- step baseline for the cumulative step sensor
- resolved merged snapshot

The merge policy is timestamp-aware and monotonic:
- local and remote candidates are compared by freshness
- remote is preferred when close
- the displayed total never goes backward during the day

### `TrackingController`

[`TrackingController.kt`](../app/src/main/java/com/example/justrun/TrackingController.kt) is intentionally thin.

It does two things:
- translates UI actions into service intents:
  - start
  - pause
  - resume
  - stop
  - mark lap
- exposes a `StateFlow<TrackingSession>` that the service publishes into

It does not compute run logic itself.

### `RunTrackingService`

[`RunTrackingService.kt`](../app/src/main/java/com/example/justrun/tracking/RunTrackingService.kt) is the main runtime engine for an active run.

It owns:
- foreground notification
- GPS subscription
- cadence step detector
- timer tick loop
- live calorie accumulation
- auto-pause logic
- lap detection
- voice cues
- conversion from `ActiveRunState` to `RunRecord` on stop

Core loops:

1. `ACTION_START`
- reads setup + settings from intent extras
- creates `ActiveRunState`
- starts foreground notification
- starts ticker loop
- optionally starts location updates
- optionally registers cadence step detector
- publishes initial `TrackingSession`

2. ticker loop
- runs every second
- if not paused and not auto-paused:
  - increments elapsed time
  - updates cadence
  - accumulates calories
  - checks lap completion
  - checks voice cue boundaries
  - checks target reached / turn-around cues
  - republishes session + notification

3. location callback
- receives GPS points
- filters/accepts points
- derives movement / auto-pause state
- updates route, distance, pace, elevation
- records automatic laps
- checks cues
- republishes session

4. stop
- finalizes last lap
- builds a `RunRecord`
- stores it via `RunRepository`
- clears the active session

### `MainActivity` / Compose UI

[`MainActivity.kt`](../app/src/main/java/com/example/justrun/MainActivity.kt) is a large single-file Compose app.

The important structure is near the top:
- `JustRunApp()` initializes `AppGraph`
- collects:
  - `settings`
  - `runHistory`
  - `trackingSession`
  - `dailyActivity`
- keeps screen navigation in a local `AppScreen` enum

Important screens:
- `HomeScreen`
  - shows weekly/history cards
  - daily activity card
  - run-in-progress card if a run is active
- `RunSetupScreen`
  - edits the planned run goal
- `SettingsScreen`
  - edits `SettingsState`
- `RunScreen`
  - live run UI
  - pause/resume/stop/lap buttons
- summary screen
  - run charts
  - splits
  - GPX export

User actions generally call directly into:
- `AppGraph.trackingController`
- `AppGraph.settingsRepository`
- `AppGraph.runRepository`

## Wear Architecture

### `WearSyncStore`

[`WearSyncStore.kt`](../wear/src/main/java/com/example/justrun/wear/WearSyncStore.kt) is a very small in-memory `StateFlow`.

It holds the current live run state shown on the watch:
- whether a run is active
- start timestamp
- pace/distance/calories
- paused state
- HR flags and current HR
- goal labels for rendering

This is separate from the watch’s daily health snapshot store.

### `WearDataListenerService`

[`WearDataListenerService.kt`](../wear/src/main/java/com/example/justrun/wear/WearDataListenerService.kt) is the main phone -> watch data ingress.

It handles three paths:

1. `PATH_DAILY_ACTIVITY_SETTINGS`
- updates watch config
- persists HR/background flags, goals, and profile into `WearHealthSnapshotStore`
- may restart the watch HR service

2. `PATH_DAILY_ACTIVITY_SYNC`
- receives the phone’s current local daily snapshot request
- merges that phone snapshot into the watch health store as `remote`
- syncs the watch’s local snapshot back to the phone

3. `PATH_LIVE_RUN`
- updates `WearSyncStore` with the live run state
- starts/keeps watch HR service in sync with run state
- if a new run arrives, opens `WearMainActivity`

It also listens for `PATH_OPEN_LIVE_RUN` as a separate explicit “open now” message.

### `WearHeartRateService`

[`WearHeartRateService.kt`](../wear/src/main/java/com/example/justrun/wear/WearHeartRateService.kt) is the watch-local sensor service.

Current split:
- active workout HR:
  - `Health Services MeasureClient`
  - sends HR to the phone during a run
- background/non-run HR:
  - watch heart-rate sensor listener
  - updates watch-local store
- daily steps on watch:
  - `TYPE_STEP_COUNTER`
  - updates watch-local store

It also:
- computes watch-local calorie accumulation from HR samples
- updates `WearSyncStore.heartRate`
- updates `WearHealthSnapshotStore`

Background HR cadence logic currently lives in:
- `BackgroundHeartRateCadenceTracker`

That logic is based on HR stability:
- unstable HR -> faster cadence
- stable HR -> back off to 10s then 20s

### `WearHealthSnapshotStore`

[`WearHealthSnapshotStore.kt`](../wear/src/main/java/com/example/justrun/wear/WearHealthSnapshotStore.kt) is the watch-side equivalent of `DailyActivityRepository`, but with extra watch-only concerns.

It stores:
- local watch steps/calories/HR
- remote phone steps/calories/HR
- goal overrides
- profile values
- HR monitoring flags
- step baseline
- resolved merged snapshot

Important implementation details:
- updates are serialized by a single `Mutex`
- state is cached in memory and persisted to `SharedPreferences`
- complication refreshes are requested after mutations
- watch -> phone daily sync is rate-limited to once per minute unless forced

Two read paths matter:
- `read(...)`
  - merged/resolved health snapshot
- `readWatchDisplay(...)`
  - watch-local-first display snapshot for watch UI/complications

That distinction exists because the watch display should not visibly “argue” with the phone on every sync.

### `HealthComplicationServices`

[`HealthComplicationServices.kt`](../wear/src/main/java/com/example/justrun/wear/HealthComplicationServices.kt) exposes three complication data sources:
- daily steps
- daily calories
- heart rate

These services read from `WearHealthSnapshotStore.readWatchDisplay(...)`, not the phone’s merged state.

That keeps complications responsive to watch-local updates.

### `WearMainActivity`

[`WearMainActivity.kt`](../wear/src/main/java/com/example/justrun/wear/WearMainActivity.kt) is a Compose UI over `WearSyncStore.state`.

It:
- requests permissions
- ensures passive monitoring is registered
- starts/stops `WearHeartRateService`
- renders compact live run metrics
- sends pause/resume/stop/lap commands back to the phone via Data Layer messages

Important UX note:
- the watch does not own the run session
- it is a remote control and live view for the phone-owned run

## Phone/Watch Integration

### Live Run Sync

Phone -> watch live state:
- `WearSyncManager.start(...)` watches `trackingSession + settings`
- it builds `WatchSyncState`
- publishes it to `PATH_LIVE_RUN`

Watch -> phone commands:
- `WearMainActivity.sendCommand(...)` sends message paths:
  - `/pause`
  - `/resume`
  - `/stop`
  - `/mark_lap`
- `WearCommandListenerService` on the phone receives those and calls `TrackingController`

New-run opening behavior:
- the phone also tries to send a one-shot `/open_live_run` message
- the watch now additionally auto-opens when a new active run arrives via the `PATH_LIVE_RUN` data item
- this was added so missed one-shot messages do not leave the watch unaware of a run

### Daily Activity Sync

Current direction:
- the phone initiates a daily sync request every 60s through `WearSyncManager`
- the request carries the phone’s local snapshot and relevant settings/profile info
- the watch merges the phone snapshot as `remote`
- the watch replies with its current local snapshot through `PATH_DAILY_HEALTH`
- the phone merges that into `DailyActivityRepository`

Phone UI and widgets render from:
- `DailyActivityRepository.snapshot`

Watch complications render from:
- `WearHealthSnapshotStore.readWatchDisplay(...)`

That means daily activity is not represented by one single shared object. Each device keeps local raw state and its own merged/display state.

## GPX Import/Export

[`GpxCodec.kt`](../app/src/main/java/com/example/justrun/GpxCodec.kt) is self-contained.

Import:
- parses GPX XML
- derives route points, distance, duration, elevation, pace series
- builds a `RunRecord`

Export:
- takes a `RunRecord`
- writes a GPX track from `routePoints`

The UI hooks for this are in `MainActivity.kt`.

## Where To Start When Editing

If you want to make changes yourself, use this map:

### Run logic
- start here: [`RunTrackingService.kt`](../app/src/main/java/com/example/justrun/tracking/RunTrackingService.kt)

### Phone UI
- start here: [`MainActivity.kt`](../app/src/main/java/com/example/justrun/MainActivity.kt)

### Settings persistence
- start here: [`SettingsRepository.kt`](../app/src/main/java/com/example/justrun/SettingsRepository.kt)
- model changes usually also require edits in [`AppModels.kt`](../app/src/main/java/com/example/justrun/AppModels.kt)

### Watch live run behavior
- live data ingress: [`WearDataListenerService.kt`](../wear/src/main/java/com/example/justrun/wear/WearDataListenerService.kt)
- live watch UI: [`WearMainActivity.kt`](../wear/src/main/java/com/example/justrun/wear/WearMainActivity.kt)
- phone publisher: [`WearSyncManager.kt`](../app/src/main/java/com/example/justrun/wear/WearSyncManager.kt)

### Daily steps / calories / HR
- phone side: [`DailyActivityRepository.kt`](../app/src/main/java/com/example/justrun/DailyActivityRepository.kt)
- watch side: [`WearHealthSnapshotStore.kt`](../wear/src/main/java/com/example/justrun/wear/WearHealthSnapshotStore.kt)
- watch sensors: [`WearHeartRateService.kt`](../wear/src/main/java/com/example/justrun/wear/WearHeartRateService.kt)

### Watch face complications
- start here: [`HealthComplicationServices.kt`](../wear/src/main/java/com/example/justrun/wear/HealthComplicationServices.kt)

## Known Architectural Rough Edges

These are worth knowing before you edit:

- `MainActivity.kt` is large and contains most Compose UI in one file.
- `RunTrackingService.kt` owns a lot of behavior and is the biggest single source of run logic.
- `watchMirroring` exists in settings but is not currently enforced in the sync layer.
- phone and watch daily activity state are both partially merged, which makes the sync story more complex than a pure single-source-of-truth design.
- phone daily calories and watch daily calories are produced by different mechanisms today.
- background heart-rate behavior on the watch is constrained by what the watch APIs actually deliver in background.

That means the best way to work safely is:
1. identify which state owner really owns the feature
2. change the model in `AppModels.kt` if needed
3. change the repository/service that owns the state transition
4. only then update UI
