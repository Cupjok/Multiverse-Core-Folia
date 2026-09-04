# PROGRESS.md — Folia compatibility work

**Status: complete and verified on Canvas 26.2.** Every world-management and teleport path listed
below was exercised on the live test server with zero thread violations.

- Base: upstream Multiverse-Core `4de4e5a6` (tag `5.8.1-pre.3`)
- Target: Folia / Canvas `26.2-931`, Minecraft 26.2, Java 26
- Build target: paper-api `1.21.11-R0.1-SNAPSHOT`, compiled with Java 21
- Unit tests: **181 passing, 0 failing** (39 suites)

See [CLAUDE.md](CLAUDE.md) for the threading model and the license obligations that constrain
this fork.

---

## 1. What was wrong, and what was done

### 1.1 The plugin would not load at all
Folia refuses plugins that do not opt in.
- **Fix:** `folia-supported: true` in `plugin.yml`.

### 1.2 Bukkit scheduler use throughout
Upstream used `Bukkit.getScheduler()` and `BukkitRunnable`, which have no meaning on a regionised
server.
- **Added `utils/scheduler/MVScheduler.java`** — one facade over the global-region, region, entity
  and async schedulers, falling back to the Bukkit scheduler off Folia so non-Folia servers keep
  working unchanged.
- **Added `utils/scheduler/MVTask.java`** — a cancellable handle abstracting `ScheduledTask` and
  `BukkitTask`.
- Migrated: `WorldTickDeferrer`, `CommandQueueManager`/`CommandQueuePayload`,
  `AsyncSafetyTeleporterAction`, `MVPlayerListener`, `DumpsService`/`DumpsLogPoster`.

### 1.3 World creation had to move to the global region
`CraftServer#createWorld` calls `TickGuard.ensureGlobalOrStartup`, so a player-issued `/mv create`
(on a region thread) would throw.
- **Fix:** `WorldManager#createBukkitWorld` routes the Bukkit call through `awaitGlobal`.

### 1.4 World unloading is impossible synchronously on Canvas
`Bukkit.unloadWorld(World, boolean)` **always throws** `Unsupported in region threading`; the
`String` overload delegates to it. Canvas instead offers an asynchronous
`unloadWorldAsync(World, boolean, Consumer<WorldUnloadResult>)` that must be started on the global
region and completes on its own teardown thread.
- **Added `utils/compatibility/WorldUnloadCompatibility.java`** — reflective use of the Canvas
  entry point, normalising its result (including friendly text for `FAIL_PLAYERS_PRESENT`,
  `FAIL_IS_OVERWORLD`, …), with the synchronous call as fallback on Paper/Spigot.
- **Fix:** `WorldManager#awaitWorldUnload` starts the unload on the global region and waits **on a
  non-global thread**, because Canvas's failure path needs the global region to make progress —
  waiting there would deadlock the server.

### 1.5 World operations had to leave the tick threads
So the unload above has a safe thread to wait on.
- **`WorldTickDeferrer#deferWorldTick` now dispatches to the async scheduler on Folia.**
- Routed through it: `create`, `import`, `load`, `clone`, `unload`, `remove` (`delete` and `regen`
  already used it upstream).

### 1.6 World settings rejected off the global region
`setPVP`, `setDifficulty`, `setKeepSpawnInMemory`, `setSpawnLocation`, weather, game rules and
world borders all call `RegionizedServer.ensureGlobalTickThread`. These are applied during config
load, which happens on the starting server's thread — where they are *not* legal.
- **Fix:** every such write goes through `MVScheduler.onGlobalRegion` — in `WorldConfigNodes`,
  `DataStore` (`GameRulesStore`, `WorldBorderStore`), `WorldManager#cloneWorldTransferData`,
  `GameruleCommand` and `WorldBorderCommand`.

### 1.7 Block reads crashed off the owning region
`BlockSafety` reads blocks to judge spawn safety. On Folia only the owning region may read them,
which broke world import at startup and any cross-world safety check.
- **Fix:** `BlockSafety#readAt` runs the check on the region that owns the location. It refuses to
  wait when called from a tick thread or during startup (see 1.9) and skips the check instead,
  which degrades gracefully rather than stalling or crashing.

### 1.8 Teleportation was broken in two separate ways
- **`PaperLib.teleportAsync` silently fell back to a synchronous teleport** on Minecraft 26.2 —
  PaperLib does not recognise the new version scheme — and the synchronous teleport throws
  `Must use teleportAsync while in region threading`.
  **Fix:** added `utils/compatibility/EntityTeleportCompatibility.java`, which calls the server's
  native `Entity#teleportAsync` and only falls back to PaperLib on servers that lack it.
- **The safety check blocked the calling region thread**, stalling the global tick and a region
  tick by over 10 seconds in testing.
  **Fix:** `AsyncSafetyTeleporterAction#teleportSingle` now resolves the destination on the
  caller's thread, performs the safety check off-tick, and then returns to the **entity's own
  thread** for the dismount/teleport/remount work (which reads entity state). Supported by a new
  `AsyncAttemptsAggregate#ofFuture` so the deferred result still reports success and failure
  normally.

### 1.9 Startup deadlock hazard
`Bukkit.isGlobalTickThread()` is only true while a global tick is actually executing, so it is
false during plugin enable — but the global scheduler does not drain during startup either.
Marshalling naively would have hung the boot.
- **Fix:** `MVScheduler#isStartupPhase`, flipped by `armStartupCompletion(Plugin)`, which queues a
  marker task on the global scheduler so the switch happens exactly when that scheduler is proven
  to be running. World creation runs inline during startup; settings and block reads are deferred.

### 1.10 Entity removal
`EntityPurger` called `Entity#remove` from whichever thread asked for the purge.
- **Fix:** removal is dispatched through the entity's own scheduler.

---

## 2. Verification performed on Canvas 26.2

All run against the live test server, console output checked for thread violations, stalls and
missed tick deadlines after every step.

| Area | Result |
|---|---|
| Plugin loads and enables | ✅ clean, no violations at startup |
| `mv version`, `mv list`, `mv info` | ✅ |
| `mv create` | ✅ world generated and registered |
| `mv modify set pvp/difficulty` | ✅ applied, confirmed via `mv info` |
| `mv gamerule set` / `reset` | ✅ |
| `mv worldborder set` | ✅ |
| `mv unload` | ✅ Canvas `WorldShutdownThread` completes, world marked UNLOADED |
| `mv load` | ✅ |
| `mv clone` | ✅ including the pre-clone world save |
| `mv regen` (with confirm + OTP) | ✅ unload, regenerate, data transfer |
| `mv remove` | ✅ |
| `mv delete` (with confirm + OTP) | ✅ world files removed from disk |
| Confirm queue + expiry timer | ✅ OTP validated, expiry fired on schedule |
| Cross-world block safety from async | ✅ correct result for another world's region |
| Cross-world entity teleport | ✅ entity arrived in target world, no stall |
| Graceful shutdown | ✅ |
| Unit tests | ✅ 181 passed |

**Total Folia thread violations across the full session log: 0.**

Teleportation was verified with a purpose-built probe plugin that drove Multiverse's API from real
region and async threads, since console commands cannot teleport and no player was available. See
[TODO.md](TODO.md) for what that does *not* cover.
