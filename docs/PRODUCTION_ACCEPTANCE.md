# Production acceptance: NOT YET CERTIFIED

The addon is experimental and **disabled by default**. Source merges, native smoke tests,
and high logical throughput are not equivalent to a production safety release.
Do not install/enable on the live TFG world while [#1](../issues/1) or [#2](../issues/2)
remains open.

## Required cargo-ownership protocol (P0 #1)

Minecraft chunk block-entity saves, `SavedData` saves, and third-party `IItemHandler`
mutations are **not one atomic durable transaction**. The current virtual ledger is
not a crash-proof transfer journal. In particular:

| Crash window | Possible durable state | Required fix |
| --- | --- | --- |
| After source extraction, before ledger save | Source missing, cargo not recorded | Source-controlled outbox escrow + durable handoff |
| After ledger save, before source extraction save | Source restored, ledger holds copy | Stable per-package identity and source/ledger deduplication |
| After sink item insertion save, before ledger removal save | Sink item and replayable cargo | Idempotent sink receipt stored atomically alongside accepted item |
| After ledger removal save, before sink save | No cargo anywhere on restart | Durable committed receipt before authoritative removal |

Supporting an arbitrary, non-transactional third-party item handler cannot guarantee
exactly-once semantics; reject unsupported endpoints rather than asserting success.
A recovery export is useful for operators but **does not** solve these windows.

A defensible implementation needs a source-owned durable outbox containing unique IDs,
a write-ahead durable handoff record, and an idempotent sink-owned inbox/receipt with
the inserted package. For supported Frogports, receipt metadata must persist **in the
same block-entity NBT transaction as the destination inventory**. All state transitions
and their ordering must be proven with kill-process recovery. Source extraction from a
third-party handler likewise needs a supported transactional protocol or must be
explicitly excluded.

**Required isolated fault tests:** capture before/after source NBT save, WAL force/commit,
transfer, receiver simulation/execution, callback exception before/after mutation,
recipient chunk save, ledger save/removal, process SIGKILL, cold restart, malformed
payload, legacy physical-package import, and repeated kill/restart. Across every
transition, assert identical IDs, count, contents, Forge capabilities, Fluid Logistics
resource amounts/fragments, and no loss or duplication. Never validate only
`GameTest` in-memory serialization as a substitute for disk recovery.

## Required Create/GT integration (P0 #2)

- Source Frogport extraction must not be animation-throttled, while preserving native
  logistics requests, filters and package metadata. Sink delivery is limited only by
  real accepting inventory and a bounded server work budget.
- Each package must actually traverse powered, mutually connected Create chain
  segments at their own speed; support speed changes, reversals, broken/reconnected
  links and endpoint address updates without advancing through unloaded infrastructure.
  Current mixed-speed routes **freeze**, which prevents early delivery but is not
  feature completion.
- Run a disposable copy of the exact TFG mod inventory (Create 6.0.8, Fluid Logistics
  1.3.2, GT fluids and pipes). Exercise actual H2/O2/N2 / `0_hno3` requests, fragment
  and order completion, fluid repackaging, full/partial inventories, blocked pipe,
  unload/reload, wildcard filters and unknown destinations. Real water/lava
  `PackageResources.unpackPackageResult` tests alone do not prove this.
- Compare stock Create and CVL on identical 1k, 10k and 100k item/GT-fluid workloads.
  Report p50/p95/p99 MSPT, server CPU, heap/GC, SaveData bytes and cold-reload time,
  client FPS and render/network bytes. Core-only 100% JaCoCo does not establish Forge
  adapter coverage; run Forge integration instrumentation and packet decoding limits.
- Both Forge GameTest profiles must pass from **fresh disposable worlds** on JDK17,
  with attested Jenkins source commit and archived artifacts. A locally passing
  shell build does not constitute a Jenkins green gate.

## What the current optional stress profile establishes

`./gradlew -PhighVolumePackages=100000 :forge:runGameTestServer` creates one
isolated Create chain, accepting Frogport, and 100k distinct vanilla Create packages.
It confirms direct virtual admission, no physical conveyor list, count-conserving
saved NBT roundtrip and clean shutdown. It **does not** measure sustained TPS,
client FPS, full GT processing, post-crash recovery or the upstream Create baseline.
The default native suite uses 2k packages to remain lean.

## Hard production guard

`enabled=false` remains the default. Releasing/building/merging this repository
is not permission to deploy a jar, switch the config, load the addon into the live
server, change Create package capacity, restart that server or migrate its world.
Close #1/#2 only after the listed acceptance artifacts exist and are reviewed.
