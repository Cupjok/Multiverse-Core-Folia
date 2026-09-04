# CLAUDE.md — Multiverse-Core-Folia

Guidance for Claude (and any future session) working in this repository.

---

## 1. What this repository is

This is an **unofficial fork** of [Multiverse-Core](https://github.com/Multiverse/Multiverse-Core)
that adds support for **Folia-style regionised servers**, targeting **Folia / Canvas 26.2**.

- Upstream base commit: `4de4e5a6` (upstream tag `5.8.1-pre.3`).
- Fork versioning: `5.8.1-folia.N` — the upstream version it derives from, plus a fork counter.
- Package names, plugin name and command names are **unchanged** from upstream, so this is a
  drop-in replacement for the official jar.

---

## 2. ⚠️ License and attribution — read before editing or publishing

Multiverse-Core is licensed under the **BSD 3-Clause License**, `Copyright (c) 2011,
The Multiverse Team`. This fork is a redistribution in both source and binary form, so **all
three clauses apply to us**. These are legal obligations, not style preferences:

1. **Retain the copyright notice and license text.**
   - `LICENSE.md` must stay in the repository, unmodified, with the original copyright line.
   - The per-file headers that read
     `Multiverse 2 Copyright (c) the Multiverse Team 2011.` **must be preserved**. Never strip or
     rewrite them when editing a file. New files added by this fork should carry the same header
     where they sit alongside upstream code.
2. **Reproduce the notice in binary distributions and accompanying materials.**
   - `LICENSE.md` is shipped and the README carries the notice and attribution. Release notes
     must keep pointing at upstream.
3. **No endorsement.** The names "The Multiverse Team" and its contributors may **not** be used to
   endorse or promote this fork.
   - Never imply this is official, endorsed, affiliated with, or supported by the Multiverse Team.
   - Never label a release "official", and never ask users to report fork bugs to upstream.

**Practical rules for any future change:**

- Keep the "unofficial fork / not affiliated / not endorsed" disclaimer in `README.md` and in the
  GitHub repository description. Do not water it down.
- Keep attribution to The Multiverse Team and a link to the upstream project prominent.
- Keep the original authors in `plugin.yml`. Fork authorship belongs in the README, not by
  replacing upstream credit.
- If a file's upstream header is missing, that is an upstream quirk — do not add or remove headers
  wholesale as a "cleanup" commit.

---

## 3. The Folia threading model (the heart of this fork)

A regionised server has no single main thread. Work is owned by one of several schedulers, and
running on the wrong one either throws or corrupts state. **This is the single most important
thing to understand before changing anything in this repo.**

| Thread | Owns | Get there with |
|---|---|---|
| **Global region** | World list, world creation/unload, world settings, game rules, world border | `MVScheduler#runGlobal`, `MVScheduler.onGlobalRegion` |
| **Region thread** | Blocks and entities in one area | `MVScheduler#runAtLocation` |
| **Entity scheduler** | One entity, following it across regions | `MVScheduler#runAtEntity` |
| **Async** | Nothing server-owned; safe to block | `MVScheduler#runAsync` |

### Hard-won rules — violating these caused real, reproduced failures

1. **Never block a ticking thread waiting on another region.**
   Region ticks run on a shared pool (`EDFSchedulerThreadPool`). Blocking one to wait for another
   region starves the pool. This was reproduced: it stalled the global tick and a region tick by
   **10+ seconds** and only unblocked on timeout. `MVScheduler#isTickThread` guards this.

2. **Never block the global region thread waiting for a world unload.**
   Canvas's unload failure path posts a task back to the global region, so waiting there deadlocks.
   World unloads therefore run from an **async** thread.

3. **Startup is a special case, and it is not uniform.** During plugin enable:
   - World creation/loading **is** allowed (`TickGuard.ensureGlobalOrStartup`).
   - World settings, game rules and block reads are **not** (`RegionizedServer.ensureGlobalTickThread`).
   - The global region scheduler **does not drain yet**, so anything submitted to it during startup
     and waited on will hang the boot.
   `MVScheduler#isStartupPhase` distinguishes this; it flips via `armStartupCompletion`, which
   queues a marker on the global scheduler so the switch happens only once that scheduler is
   proven to be running.

4. **Synchronous Bukkit events may only be fired from a tick thread.** `WorldManager#callEvent`
   hops to the global region when off-tick, and blocks, so cancellable events still work.

### Server-specific API facts (Canvas 26.2, verified by decompiling the server jar)

- `Bukkit.createWorld(WorldCreator)` — works, but **global region or startup only**.
- `Bukkit.unloadWorld(World, boolean)` — **always throws** `UnsupportedOperationException:
  Unsupported in region threading`. The `String` overload delegates to it and throws too.
- Canvas adds `CraftServer#unloadWorldAsync(World, boolean, Consumer<WorldUnloadResult>)`. It must
  be **started on the global region**, runs on a `WorldShutdownThread`, and reports through the
  callback. Reached reflectively via `WorldUnloadCompatibility` so plain Paper/Spigot still work.
- `PaperLib.teleportAsync` **silently falls back to a synchronous teleport** on Minecraft 26.2,
  because PaperLib does not recognise the new version scheme — and the synchronous teleport throws
  on Folia. Use `EntityTeleportCompatibility` (native `Entity#teleportAsync`) instead.
  **Do not reintroduce `PaperLib.teleportAsync`.**
- `Bukkit.isPrimaryThread()` is `true` on **any** tick thread, not just the global one.

---

## 4. Fork-specific code map

| File | Role |
|---|---|
| `utils/scheduler/MVScheduler.java` | The scheduling facade. Start here. |
| `utils/scheduler/MVTask.java` | Server-agnostic cancellable task handle. |
| `utils/compatibility/WorldUnloadCompatibility.java` | Canvas async unload, with sync fallback. |
| `utils/compatibility/EntityTeleportCompatibility.java` | Native `teleportAsync`, PaperLib fallback. |
| `utils/WorldTickDeferrer.java` | Moves world operations off the tick threads on Folia. |
| `world/WorldManager.java` | `awaitGlobal` / `awaitWorldUnload` / `callEvent` marshalling. |
| `teleportation/AsyncSafetyTeleporterAction.java` | Off-tick safety check, then back to the entity. |
| `teleportation/BlockSafety.java` | `readAt` — region-aware block reads. |

**When adding code, ask:** which thread owns the state I am touching, and can this thread wait?

---

## 5. Build, test and deploy

```bash
# Build (Java 21 — Gradle 8.10.2 does not support newer JDKs)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew build            # includes tests
./gradlew build -x test    # faster iteration
```

Output: `build/libs/multiverse-core-<version>.jar`.

### Test server (always use this one)

```
/Users/cupjok/Projects/Active/Minecraft server/Survival SMP Folia 26.2 test
```

Canvas 26.2-931 (Folia fork), Minecraft 26.2, Java 26. Helper scripts:

```bash
./start-bg.sh            # start in background, logs to console-claude.log
./stop-bg.sh             # graceful stop
./cmd.sh "<command>"     # send a console command
```

**`/mv` is taken by another plugin (RegionVerse) on this server.** Always use the namespaced
form in tests: `./cmd.sh "multiverse-core:mv list"`.

### Checking for regressions

The single best signal — this must print `0`:

```bash
sed 's/\x1b\[[0-9;]*m//g' console-claude.log | grep -cE \
  "ThreadViolationException|Cannot modify server settings|Cannot read world asynchronously|Must use teleportAsync|Asynchronous world save"
```

Also watch for `missed deadline` (a stalled tick) and `FoliaWatchdogThread`.

---

## 6. Conventions

- Match upstream style: 4-space indent, Javadoc on public members, `@NotNull`/`@Nullable`.
- Checkstyle (`config/mv_checks.xml`) runs in CI only, not in the Gradle build.
- Services are HK2 `@Service` with constructor `@Inject`. Adding a constructor parameter to a
  command means updating its nested `LegacyAlias` subclass constructor too — an easy thing to miss.
- Comments should explain *why* a thread hop exists, not restate the API call.
