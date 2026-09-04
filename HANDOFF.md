# HANDOFF.md — continuing this work in a new session

Read this first, then [CLAUDE.md](CLAUDE.md). Everything you need to resume is here.

---

## 1. Thirty-second summary

`Cupjok/Multiverse-Core-Folia` is an **unofficial fork** of Multiverse-Core that makes it work on
**Folia-style regionised servers** (verified on Canvas 26.2 / Minecraft 26.2).

The port is **done and tested**. World create / load / unload / clone / regen / remove / delete,
world settings, game rules, world borders, the confirm queue, and cross-world teleportation all
work with **zero thread violations**. 181 unit tests pass.

`/mv tp` has been confirmed working with a real player. The main thing left is exercising the
**remaining player-facing paths** — spawn, portals, respawn, gamemode enforcement — see
[TODO.md](TODO.md) §1.

---

## 2. Where everything is

| What | Where |
|---|---|
| Repository | `/Users/cupjok/Projects/Active/minecraft-plugins/Multiverse-Core-Folia` |
| GitHub | `https://github.com/Cupjok/Multiverse-Core-Folia` |
| Test server | `/Users/cupjok/Projects/Active/Minecraft server/Survival SMP Folia 26.2 test` |
| Built jar | `build/libs/multiverse-core-<version>.jar` |
| Deployed as | `<test server>/plugins/Multiverse-Core.jar` |

Upstream base: commit `4de4e5a6`, upstream tag `5.8.1-pre.3`. Fork versions are `5.8.1-folia.N`.

---

## 3. Getting productive in five minutes

```bash
cd "/Users/cupjok/Projects/Active/minecraft-plugins/Multiverse-Core-Folia"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home   # Java 21, not 26

./gradlew build -x test          # iterate
./gradlew build                  # with the 181 unit tests

SRV="/Users/cupjok/Projects/Active/Minecraft server/Survival SMP Folia 26.2 test"
cd "$SRV"
./stop-bg.sh
cp "/Users/cupjok/Projects/Active/minecraft-plugins/Multiverse-Core-Folia/build/libs/multiverse-core-local.jar" plugins/Multiverse-Core.jar
./start-bg.sh
until grep -qE "Done \(" console-claude.log; do sleep 2; done

./cmd.sh "multiverse-core:mv list"     # NOTE: plain /mv is taken by RegionVerse on this server
```

**The one check that matters** — must print `0`:

```bash
sed 's/\x1b\[[0-9;]*m//g' console-claude.log | grep -cE \
  "ThreadViolationException|Cannot modify server settings|Cannot read world asynchronously|Must use teleportAsync|Asynchronous world save"
```

Also grep for `missed deadline` (a stalled tick) and `FoliaWatchdogThread`.

---

## 4. What you must understand before changing code

Full detail in [CLAUDE.md](CLAUDE.md) §3. The four rules that were learned the hard way, each from
a reproduced failure:

1. **Never block a ticking thread waiting on another region.** Region ticks share a thread pool;
   blocking one starves it. Observed: global tick and a region tick stalled **10+ seconds**.
2. **Never block the global region waiting for a world unload.** Canvas's unload failure path
   posts back to the global region — waiting there deadlocks. Unloads run from async.
3. **Startup is not uniform.** World creation is legal on the starting thread; world settings and
   block reads are not; and the global scheduler does not drain yet, so waiting on it hangs the
   boot. `MVScheduler#isStartupPhase` exists for exactly this.
4. **Sync events need a tick thread.** `WorldManager#callEvent` hops and blocks so cancellable
   events still work.

Everything routes through **`utils/scheduler/MVScheduler.java`** — read that file first.

Two server-specific traps worth repeating:
- `Bukkit.unloadWorld(...)` **always throws** on Canvas. Use `WorldUnloadCompatibility`.
- `PaperLib.teleportAsync` **silently does a sync teleport** on MC 26.2 and then throws. Use
  `EntityTeleportCompatibility`. **Do not reintroduce PaperLib for teleporting.**

---

## 5. Suggested next steps, in order

1. **Remaining player testing** ([TODO.md](TODO.md) §1) — `/mv tp` is confirmed; still to walk are
   `/mv spawn`, portals, respawn, gamemode enforcement and `--remove-players`.
2. **Paper/Spigot regression check** — confirm the Folia branches stay inert off Folia.
3. **Plain Folia check** — the fallbacks target it but it is untested.
4. Address the `BlockSafety` skip-vs-wait trade-off ([TODO.md](TODO.md) §2) if it proves annoying.
5. Port the Multiverse addons only if asked; this fork is Core only.

---

## 6. ⚠️ Constraints you must not break

### Licence — BSD 3-Clause, `Copyright (c) 2011, The Multiverse Team`

This fork redistributes upstream source and binaries, so all three clauses bind us. Full text and
reasoning in [CLAUDE.md](CLAUDE.md) §2.

- **Keep `LICENSE.md`** in the repo, unmodified, with the original copyright line.
- **Keep the per-file `Multiverse 2 Copyright (c) the Multiverse Team 2011.` headers.** Never strip
  or rewrite them while editing.
- **Keep the notice in what ships:** README and release notes must retain attribution and a link to
  upstream.
- **No endorsement.** The Multiverse Team's name may not be used to promote this fork. Never call a
  release official, never imply affiliation, and never send fork bug reports upstream.
- **Keep the "unofficial fork / not affiliated / not endorsed" disclaimer** in `README.md` and in
  the GitHub repository description. Do not soften it.
- **Keep upstream authors in `plugin.yml`.** Fork credit goes in the README instead.

### Compatibility

- The plugin name, package names and commands intentionally match upstream so this is a drop-in
  replacement. Do not rename them.
- Non-Folia servers must keep working: every Folia branch is guarded by `MVScheduler.isFolia()`.
- Do not publish to upstream's Maven registry — `build.gradle` still points at it.

---

## 7. Testing notes worth keeping

- **`/mv` is taken** on the test server by RegionVerse. Use `multiverse-core:mv ...`.
- **Game rules are namespaced snake_case** on MC 26.2, e.g. `minecraft:spawn_monsters`, not
  `doMobSpawning`. `mv gamerule list <world>` shows valid names.
- **Destructive commands need a confirm OTP.** Scrape it from the log:
  ```bash
  ./cmd.sh "multiverse-core:mv delete myworld"; sleep 5
  OTP=$(sed 's/\x1b\[[0-9;]*m//g' console-claude.log | grep -oE "/mv confirm [0-9]+" | tail -1 | grep -oE "[0-9]+")
  ./cmd.sh "multiverse-core:mv confirm $OTP"
  ```
- **Teleport testing without a player:** a small throwaway probe plugin (`depend:
  [Multiverse-Core]`, `folia-supported: true`) that calls `MultiverseCoreApi.get()` from a real
  region thread and an async thread is how the teleport paths were verified. Spawn an entity via
  `Bukkit.getRegionScheduler()`, teleport it cross-world through
  `MultiverseCoreApi.get().getSafetyTeleporter()`, and assert on `entity.getWorld()`. Recreate it
  when touching teleport code; it catches things console commands cannot reach.
- Server facts were established by **decompiling the server jar** (`javap -c -p` on
  `versions/26.2/canvas-26.2.jar`). If you need to know whether an API is guarded, look — do not
  guess.
