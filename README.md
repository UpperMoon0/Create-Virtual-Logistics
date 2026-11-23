# Create Virtual Logistics

A separate **Forge 1.20.1 / Create 6.0.8** addon providing logical, time-based chain conveyor cargo transport. The chains remain real infrastructure. Packages take time to traverse physical routes at Create's block-per-tick speed, but **physical per-node package queues no longer limit throughput**.

> **Experimental; disabled by default; do not deploy to an irreplaceable world.** This is a source-level prototype with a tested independent transport core. Runtime GameTest acceptance, crash consistency, and Fluid Logistics compatibility must be proven before production use.

## Architecture

- **Physical Create graph**: traverse loaded, mutually connected chain conveyor block entities; discover attached Frogports and address filters.
- **Cargo ledger**: serialized exact item payloads, including Forge capabilities and fluid/fragment NBT, stored in dimension-specific Minecraft SavedData.
- **Virtual source clock**: one cumulative-distance clock per source conveyor; every parcel has an odometer deadline. Motion at 0 or negative RPM pauses forward travel; true reverse-direction route motion remains a production gate.
- **Unbounded logical in-flight count**: enqueue cargo without per-conveyor capacity; enqueue and arrival do not depend on animation.
- **Inventory delivery**: only the native Frogport buffer is permitted by default. Arbitrary external Forge IItemHandler insertion is unsafe and requires explicit opt-in in disposable worlds.
- **Bounded delivery work**: an actual server-tick budget covers ALL delivery attempts, including rejected ones.
- **Client-only sampling**: a small configurable subset of in-flight shipments is rendered; the cargo ledger remains authoritative on the server.

## Scope and known limitations

- Both server and client need the addon while visual packets are enabled.
- No forced chunk loading; unloaded sections pause or defer shipments.
- Current visual proxies are decorative and cannot be physically picked up. Create's normal package interactions require separate virtualization support.
- The route graph is refreshed periodically, and destination receipts revalidate all loaded Create connections and the receiving Frogport. Broken routes retain cargo for retry; automated mid-flight rerouting is not yet implemented.
- Source Frogport extraction still follows Create's native animation cadence. Receiver insertion is no longer gated by frog animations.
- Reversal conservatively pauses forward progress; per-segment speed, true backwards travel and topology revision handling remain release blockers.
- **Minecraft SavedData and inventory writes are not one atomic durable transaction**, even for the Frogport's own buffer. Crashes can still lose or duplicate cargo. Arbitrary third-party insertion is disabled by default; do not claim exactly-once safety.
- Pending cargo without a reachable destination remains in the ledger. `/cvl status` permits inspection, and permission-level-4 `/cvl export` writes an immutable recovery snapshot; automated rollback/replay is unavailable until idempotent receipts are implemented.
- This addon does not yet replace Create's FactoryPanel demand algorithm or Fluid Logistics package fragmentation/reassembly.

These are **release blockers**, not optional polish.

## Opt-in switch

The common config property `enabled` defaults to false, even on servers. Disabling freezes existing logical shipments and clears the client visual sample; `allowUnsafeExternalInsertion` also defaults false.
Only enable in disposable test worlds until native conservation and crash
recovery are certified. Forge GameTests automatically supply an isolated
enabled=true config; no live world config is altered.

- Visual sample packets cap at 64 packages and at most 96 graph nodes each. Long routes remain server-authoritative but skip client rendering.
- `/cvl export` writes an NBT copy under `world/cvl-recovery/` for offline audit. It never imports cargo or turns world and inventory saves into atomic transactions.

## High-volume and coverage verification

When a connected destination is already known, package admission now uses a
20-tick per-source, per-address route cache (maximum 1024 entries per source) and
promotes each captured package to in-flight transport immediately. The slower
128-per-10-tick planner remains only for previously unresolved pending cargo.
This is **logical route admission**, not unlimited source inventory extraction or
physical inventory write throughput.

A lean default Forge GameTest accepts 2,000 packages immediately, conserving
their IDs while Create's physical conveyor list stays empty. The optional
100,000-package **native** acceptance run is intentionally not part of every
CI build:

    ./gradlew -PhighVolumePackages=100000 :forge:runGameTestServer

It verifies admission timing, full SavedData export/reload count conservation,
and clean isolated world save/shutdown. A synthetic Java-only engine benchmark
remains separate. Neither benchmark proves crash-safe transactionality, sustained
Minecraft TPS/MSPT, client FPS or a baseline comparison with stock Create.

The standalone core keeps 100% JaCoCo thresholds. Forge adapter integration
coverage is collected separately using **real Forge game tests**, with the
JaCoCo runtime agent opt-in to avoid adding overhead to regular dev runs:

    ./gradlew -PnativeCoverage=true :forge:runGameTestServer
    ./gradlew -PnativeCoverage=true -PwithFluidLogistics=true :forge:runGameTestServer
    ./gradlew :forge:nativeForgeCoverageReport

The production Forge report excludes GameTest implementation classes (which
otherwise inflate results). Headless server tests do **not** exercise the client
renderer, and mixin-injected production methods need explicit functional
verification in addition to bytecode coverage. Both gaps remain acceptance
work. Detailed release and crash-boundary matrix:
[Production acceptance](docs/PRODUCTION_ACCEPTANCE.md).

## Build and test

Requires JDK 17 (Forge 1.20.1).

    ./gradlew :core:check :forge:build

Core JaCoCo XML/HTML: core/build/reports/jacoco/test/.

Standalone 1k/10k/100k engine benchmark (does NOT measure Minecraft MSPT, Create baseline, FPS or GT compatibility):

    ./gradlew :core:transportBenchmark

Native Forge acceptance tests (isolated throwaway world, never production):

    ./gradlew :forge:runGameTestServer

Isolated Forge runtime with **real** pinned Fluid Logistics 1.3.2 loaded (startup, Create order-fragment/serialized package preservation; GT chemistry and end-to-end reassembly still require full-modpack acceptance):

    ./gradlew -PwithFluidLogistics=true :forge:runGameTestServer

This uses a separate disposable `forge/run-gametest-fluid/` world. The mod is only a runtime test dependency, not bundled or installed in production.

The core tests cover 10,000 concurrent logical packages, shortest-path routing, waiting destination, retry, no per-package tick simulation, speed freeze/resume, persistence snapshots, work budgeting, render cap, and invalid state rejection.

## Production integration

Target: Create 6.0.8 Forge 1.20.1; expected to run alongside Fluid Logistics but **not certified** until a full modpack GameTest acceptance campaign passes.

Source repository: https://github.com/UpperMoon0/Create-Virtual-Logistics
