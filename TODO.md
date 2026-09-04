# TODO.md — remaining work and known limitations

The Folia port is functionally complete and verified (see [PROGRESS.md](PROGRESS.md)). What
follows is honest residual risk, not blocking defects.

---

## 1. Player-facing paths — partly confirmed ⚠️

`/mv tp` was **confirmed working with a real player** by the maintainer on 2026-09-04, on the
Canvas 26.2 test server. The remaining items below were still only verified indirectly (via a
probe plugin driving the API from real region and async threads), because no player was connected
during the port itself.

- [x] `/mv tp` as a real player — **confirmed working**.
- [ ] `/mv spawn` as a player.
- [ ] Gamemode / flight enforcement on world change (`MVPlayerListener#handleGameModeAndFlight`,
      now on the entity scheduler).
- [ ] Player respawn handling, including anchor and bed respawn.
- [ ] Nether/End portal traversal between Multiverse worlds.
- [ ] World entry checks: entry fee, player limit, permissions, blacklist.
- [ ] `--remove-players` on `unload`/`remove`/`delete`/`regen`, which teleports players out first.

**How to close this:** join the test server and walk through each item, watching
`console-claude.log` for the violation patterns listed in CLAUDE.md §5.

## 2. Known behavioural trade-off: safety checks can be skipped

`BlockSafety#readAt` deliberately **skips** the spawn-safety check rather than wait when it is
called from a tick thread that does not own the target location, or during startup. Waiting there
would stall the server (this was reproduced, and it stalled ticks by 10+ seconds).

- Multiverse's own teleport path avoids this: it hops off-tick first, so the check *does* run.
- A third-party plugin calling `BlockSafety` directly from a region thread will silently get an
  unchecked "safe" answer, logged at `FINE`.
- [ ] Consider surfacing this at `WARNING` once, or exposing an async-first `BlockSafety` API.

## 3. Timeouts are heuristic

`WorldManager` uses fixed waits: 300s world create, 120s unload, 120s save, 30s generic;
`BlockSafety` uses 10s. Sane for the test server, unproven on a large heavily-loaded world.
- [ ] Consider making these configurable if anyone reports a timeout.

## 4. Not exercised

- [ ] Multiverse addons (NetherPortals, Portals, Inventories, SignPortals) on Folia — each will
      need its own port; this fork only covers Core.
- [ ] `mv import` of a pre-existing world folder (the code path is shared with `create`/`load`,
      which are tested).
- [ ] Generators/biome providers from third-party plugins.
- [ ] Multi-world servers under real player load; regionised behaviour differs under contention.
- [ ] Plain (non-Canvas) Folia. The fallbacks are written for it and it should be the *easier*
      case, since Canvas is the fork with the extra unload API — but it is untested.
- [ ] Paper and Spigot regression testing. Every Folia branch is guarded by `MVScheduler.isFolia()`
      and should be inert there, but this was not run.

## 5. Build and repo housekeeping

- [ ] Compiles against paper-api `1.21.11`, not the matching `26.2` API. This works (verified on
      the live 26.2 server) and keeps the build on Java 21; moving to the 26.2 API would require
      JDK 25+ and a newer Gradle. Revisit only if API drift actually bites.
- [ ] The inherited GitHub Actions workflows still reference upstream infrastructure (including
      publishing to upstream's Maven package registry). Review before enabling CI on the fork.
- [ ] Upstream's `build.gradle` still points `publishing` at `maven.pkg.github.com/Multiverse/
      Multiverse-Core`. Do not publish there.

## 6. Keeping in sync with upstream

Upstream moves; this fork is pinned at `5.8.1-pre.3`.
- [ ] When rebasing, re-check every site listed in CLAUDE.md §4 — new upstream code will use
      `Bukkit.getScheduler()` and direct world mutation, and will need the same treatment.

---

## ⚠️ Licence obligations — never regress these

Detailed in [CLAUDE.md](CLAUDE.md) §2. Restated because they are easy to break by accident:

- [ ] `LICENSE.md` stays, unmodified, with `Copyright (c) 2011, The Multiverse Team`.
- [ ] Per-file `Multiverse 2 Copyright (c) the Multiverse Team 2011.` headers are **never**
      stripped or rewritten.
- [ ] README and the GitHub repository description keep the **unofficial fork / not affiliated /
      not endorsed** disclaimer and the attribution to The Multiverse Team.
- [ ] Never present a release as official, endorsed or supported by the Multiverse Team, and never
      direct fork bug reports to upstream.
- [ ] Upstream authors stay in `plugin.yml`.
