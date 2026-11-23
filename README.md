# Create Virtual Logistics

A separate **Forge 1.20.1 / Create 6.0.8** addon providing logical, time-based chain conveyor cargo transport. The chains remain real infrastructure. Packages take time to traverse physical routes at Create's block-per-tick speed, but **physical per-node package queues no longer limit throughput**.

> **Experimental; do not deploy to an irreplaceable world.** This is a source-level prototype with a tested independent transport core. Runtime GameTest acceptance, crash consistency, and Fluid Logistics compatibility must be proven before production use.

## Architecture

- **Physical Create graph**: traverse loaded, mutually connected chain conveyor block entities; discover attached Frogports and address filters.
- **Cargo ledger**: serialized exact item payloads, including Forge capabilities and fluid/fragment NBT, stored in dimension-specific Minecraft SavedData.
- **Virtual source clock**: one cumulative-distance clock per source conveyor; every parcel has an odometer deadline. Motion at 0 RPM stops; negative RPM uses absolute speed for now.
- **Unbounded logical in-flight count**: enqueue cargo without per-conveyor capacity; enqueue and arrival do not depend on animation.
- **Inventory delivery**: insert through adjacent Forge IItemHandler where possible, else into Frogport's native inventory; unsuccessful deliveries retry without being dropped.
- **Bounded delivery work**: an actual server-tick budget covers ALL delivery attempts, including rejected ones.
- **Client-only sampling**: a small configurable subset of in-flight shipments is rendered; the cargo ledger remains authoritative on the server.

## Scope and known limitations

- Both server and client need the addon while visual packets are enabled.
- No forced chunk loading; unloaded sections pause or defer shipments.
- Current visual proxies are decorative and cannot be physically picked up. Create's normal package interactions require separate virtualization support.
- The route graph is refreshed periodically; moving/removing destinations while cargo is in transit requires re-routing and recovery work.
- Source Frogport extraction still follows Create's native animation cadence. Receiver insertion is no longer gated by frog animations.
- Speed reversal currently preserves forward route progress with absolute RPM; exact reverse-direction semantics are a future compatibility requirement.
- **Minecraft SavedData and generic third-party inventories are not an atomic transaction**. A crash between successful insertion and persisted completion may duplicate cargo. This pre-release must not claim crash-proof exactly-once delivery.
- Pending cargo without a reachable destination remains in the ledger; admin recovery commands are still required.
- This addon does not yet replace Create's FactoryPanel demand algorithm or Fluid Logistics package fragmentation/reassembly.

These are **release blockers**, not optional polish.

## Build and test

Requires JDK 17 (Forge 1.20.1).

    ./gradlew :core:check :forge:build

Core JaCoCo XML/HTML: core/build/reports/jacoco/test/.

Native Forge acceptance tests (isolated throwaway world, never production):

    ./gradlew :forge:runGameTestServer

The core tests cover 10,000 concurrent logical packages, shortest-path routing, waiting destination, retry, no per-package tick simulation, speed freeze/resume, persistence snapshots, work budgeting, render cap, and invalid state rejection.

## Production integration

Target: Create 6.0.8 Forge 1.20.1; expected to run alongside Fluid Logistics but **not certified** until a full modpack GameTest acceptance campaign passes.

Source repository: https://github.com/UpperMoon0/Create-Virtual-Logistics
