# AGENTS.md

Context and operational guidelines for AI coding agents working on ReQPet (fork of QPet Companion).

---

## 1. Project Overview

ReQPet (fork of QPet Companion) is an Android Xposed module built with Kotlin. It injects into the Mobile QQ main process (`com.tencent.mobileqq`) using **libxposed (API 102)** to provide an in-process management interface (Jetpack Compose) and background automation loop for the QPet feature.

- **Application ID**: `io.github.congsmile.qqpet`
- **Namespace**: `com.copilot.qqpet`
- **Host Target**: `com.tencent.mobileqq`
- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 37
- **Compile SDK**: 37
- **Language / Toolchain**: Kotlin 2.x, Java 17, Android Gradle Plugin (Kotlin DSL)

---

## 2. Repository Structure

```text
ReQPet/
├── app/                  # Main module source
│   ├── src/main/java/com/copilot/qqpet/
│   │   ├── HookEntry.kt  # Libxposed module entry point (implements XposedInterface)
│   │   ├── RuntimeSwitches.kt # Global runtime switch definitions
│   │   ├── hook/         # HookApi, injector, crash isolation, tinker blocking, network shield
│   │   ├── protocol/     # OIDB channel reflection, protobuf encoders (ProtoWire), protocol clients
│   │   ├── engine/       # Background loop, state machine, task coordinator, diagnostics
│   │   └── ui/           # Full-screen Dialog container, controller lifecycle restore, Jetpack Compose UI, back stack, state management
│   ├── src/main/res/     # Resources, view identifiers, drawables
│   └── src/test/         # Unit test suite (JVM-based state, protocol, and fault injection tests)
├── build.gradle.kts      # Root build configuration
└── settings.gradle.kts   # Root Gradle settings (:app)
```

---

## 3. Core System Invariants

All agent modifications MUST uphold these non-negotiable invariants:

### 3.1 Zero Host Crash
- Under no circumstances should an unhandled exception escape into the host's main Looper or system callbacks.
- Every bridge hook, observer, and reflection dispatch across the host boundary must be safely guarded. Do not leak `CancellationException` into the host Android Looper.

### 3.2 Account Session Isolation
- Background loop, manual operations, and UI data loading share `PetAdventureEngine.sessionMutex`.
- Account switches or logouts increment `sessionGeneration`, invalidate active tokens, and wipe current account memory to prevent cross-account cache poisoning.

### 3.3 Safe Task Settlement
- A task can only be considered completed and settled when `enableSettle == true`, `story.code == 0`, and the local elapsed deadline has passed (`currentTaskEndTimeMillis <= now` with `currentTaskEndTimeMillis > 0`):
  1. **Server ready (`isReadyToSettle`)**: `status > 0`, `startTimestamp > 0`, and `remaining <= 0`.
  2. **Tracked expiry on idle (`isIdle`)**: Server returns `status == 0` (or empty storyId) after task expiration, resolving `pendingSettlementStoryId` (retry) or an active `lastActiveStoryId` matching the expired story (`tracked_expiry`).
- Do not guess task IDs or settle unconfirmed stories. Settle failures retain `pendingSettlementStoryId` for retry; successful settlements clear the tracked story.

### 3.4 Protocol Safety & Rate Limiting
- Never flood the host's OIDB channel. Always respect domain circuit breakers (`ProtocolBreakers`) and rate limits.
- Background loops must employ exponential backoff with jitter on consecutive failures instead of fast retry loops.
- `0x975a_1` read-only status query is exempt from business circuit breakers to ensure recovery perception, but enforces independent interval throttling (>= 15s).

---

## 4. Build and Verification Commands

Run commands via the root Gradle wrapper:

### Build
- Compile debug APK:
  ```bash
  ./gradlew assembleDebug
  ```
  Artifact output: `app/build/outputs/apk/debug/app-debug.apk`

- Compile optimized release APK for startup performance comparisons:
  ```bash
  ./gradlew assembleRelease
  ```
  Artifact output: `app/build/outputs/apk/release/app-release.apk`. Release enables R8 optimization; debug does not.

- Clean build:
  ```bash
  ./gradlew clean assembleDebug
  ```

### Testing
- Run all unit tests:
  ```bash
  ./gradlew testDebugUnitTest
  ```

- Run a specific test class:
  ```bash
  ./gradlew testDebugUnitTest --tests com.copilot.qqpet.engine.FaultInjectionAndConsistencyTest
  ```

- Run a single test method:
  ```bash
  ./gradlew testDebugUnitTest --tests com.copilot.qqpet.engine.FaultInjectionAndConsistencyTest.expiredTrackedStorySettlesWhenServerClearsRunningFields
  ```

---

## 5. Development Workflow & Guidelines

### 5.1 Architecture & Component Boundaries
- **`hook/`**: Contains `HookApi` (exposes LibXposed APIs), `QQSettingInjector` (injects settings entry into host settings list), `CrashInterceptor`, `TinkerBlocker`, and `NetworkSecurityShield`. Note that module entry `HookEntry` resides in root package `com.copilot.qqpet`.
- **`protocol/`**: Contains `OidbChannel` (handles packet reflection via host trpc/oidb engine), `QQPetDirectBridge` (facade for host methods), and protocol clients (`PetCareerProtocolClient`, `PetCareProtocolClient`, `PetSocialProtocolClient`).
- **`engine/`**: Implements single-state-owner background automation. `PetAdventureEngine` coordinates `PetCycleDispatcher`, `PetMaintenanceCoordinator`, `StealthScheduler`, and `RuntimeDiagnostics`.
- **`ui/`**: Uses Jetpack Compose attached via `ComposeInjectionHost` inside self-managed full-screen `QQSettingDialog` with native platform window animations (`Animation_Translucent`) respecting system animation scale. `SettingsDialogController` manages window lifecycle and state restoration across host Activity destruction. Navigation state is managed by `SettingsBackStack` with per-page `SaveableStateProvider`.

### 5.2 Code Style & Comment Rules
- **Zero Decoratives**: No ASCII art, divider bars (`// ======`), or emojis in comments.
- **Ruthless Deletion**: Remove obsolete code, deprecated shims, and unused comments immediately. Never leave commented-out code blocks.
- **Minimalist Comments**: Only document non-obvious business logic decisions ("Why"), never what is self-evident from the code ("What").
- **Language**: Kotlin first. Keep null safety strict; prefer immutable data models and pure calculations where possible.

### 5.3 Git & Commit Conventions
- Use Conventional Commits format:
  - `feat(...)`: New features
  - `fix(...)`: Bug fixes
  - `refactor(...)`: Code refactoring without behavior changes
  - `test(...)`: Adding or updating test cases
  - `docs(...)`: Documentation updates
- When instructed to commit code changes, exclude documentation files (`*.md`) and local agent metadata (`.agents/`) unless explicitly requested.

### 5.4 Settings Startup Diagnostics
- Collect host app tracing for `com.tencent.mobileqq` together with graphics/view, Dalvik, and scheduling events. Confirm `settings.*` markers are present before comparing timings.
- Synchronous markers cover entry clicks, controller dispatch, dialog initialization/show/release, state initialization/attachment/refresh, view creation, owner resume, and state saving.
- `settings.open_to_first_draw` is an API 29+ async span from dialog initialization to the first observed `OnDrawListener` callback. It does not measure display presentation or the full click-to-open interval.
- The existing `settings_first_draw` diagnostic event records `elapsed_ms` from the controller show request and `status` (`success` or `aborted`). Success means draw traversal was observed, not that the frame finished or was presented.
- `settings.account_sync` is an API 29+ async span inside the IO coroutine. Normal account synchronization starts after the first draw callback via a tracked view post; release cancels pending work and closes outstanding spans.
- Attribute reads in `SettingsState.refresh` use the cached snapshot rather than synchronous host reflection.
- HOME category rows are individually keyed lazy items; each group title stays with its first row. Reuse `SettingsGroupItem` for corner/color rules, preserving 3 dp row gaps, 12 dp group gaps, and per-page saved state. Do not bundle a whole HOME category group into one lazy item.
- Compare cold first-open after a QQ process restart separately from warm reopen, using the same release APK and capture configuration. Check `VerifyClass`, initialization/composition/measure work, and FrameTimeline missed deadlines; builds and JVM tests do not establish device startup latency.
- The Compose mapping producer dependency follows the catalog Compose plugin version because AGP's built-in Kotlin version may differ. Keep mapping generation and R8 optimization enabled.
