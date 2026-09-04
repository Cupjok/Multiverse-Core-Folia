<p align="center">
<img src="config/multiverse-banner.png" alt="Multiverse Logo">
</p>

# Multiverse-Core-Folia

**An unofficial, community-maintained fork of [Multiverse-Core](https://github.com/Multiverse/Multiverse-Core) with support for [Folia](https://papermc.io/software/folia)-style regionised servers.**

> ### ⚠️ Unofficial fork — please read
>
> This project is **not official**, and is **not affiliated with, endorsed by, or supported by
> The Multiverse Team**. It is an independent fork maintained by [@Cupjok](https://github.com/Cupjok).
>
> **Do not report issues with this fork to the Multiverse project.** They did not write this code
> and cannot support it. Please open an issue
> [here](https://github.com/Cupjok/Multiverse-Core-Folia/issues) instead.
>
> All credit for Multiverse itself belongs to **The Multiverse Team** and its contributors. If you
> run a regular Paper or Spigot server, use the
> [official Multiverse-Core](https://github.com/Multiverse/Multiverse-Core) — you do not need this fork.

---

## Why this fork exists

Folia replaces the single main server thread with **independently ticking regions**. Multiverse-Core
was written for the traditional threading model, so on Folia it refuses to load, and its world
management and teleportation would be unsafe if it did.

This fork adapts Multiverse-Core to that model: every operation is dispatched to the thread that
actually owns the state it touches — the global region for worlds and their settings, a region for
blocks, an entity's own scheduler for that entity, and async threads for work that must wait.

It is a **drop-in replacement**: the plugin name, commands, permissions, configuration and API are
unchanged from upstream.

## Compatibility

| | |
|---|---|
| **Tested on** | Canvas `26.2-931` (Folia fork), Minecraft `26.2`, Java 26 |
| **Upstream base** | Multiverse-Core `5.8.1-pre.3` |
| **Server software** | Folia and its forks (Canvas), plus Paper and Spigot |
| **Java** | 21 or newer |

Non-Folia servers are unaffected: all regionised behaviour is guarded by a runtime check, so on
Paper and Spigot the plugin behaves exactly as upstream does.

> **Note:** This fork covers **Multiverse-Core only.** The Multiverse addons
> (NetherPortals, Portals, Inventories, SignPortals) are **not** Folia-compatible and are not part
> of this project.

## Installation

1. Download the latest jar from [Releases](https://github.com/Cupjok/Multiverse-Core-Folia/releases).
2. Drop it into your server's `plugins/` folder. If you are migrating, remove any existing
   `Multiverse-Core` jar first — do not run both.
3. Restart the server.

Your existing `worlds.yml` and `config.yml` carry over unchanged.

## What was changed

The port centres on a scheduling facade (`MVScheduler`) plus compatibility shims for two APIs that
behave differently on Folia: world unloading, which can only be done asynchronously, and entity
teleportation, which must use the server's native async teleport.

For the full engineering write-up — the threading rules, every fix, and what was verified on a live
Folia server — see **[PROGRESS.md](PROGRESS.md)**. Known gaps are tracked in **[TODO.md](TODO.md)**.

## Building

```bash
./gradlew build
```

Requires JDK 21. The jar is written to `build/libs/`.

## Documentation

Multiverse's own usage documentation applies unchanged: [mvplugins.org](https://mvplugins.org).

Contributor and maintainer notes for this fork:

- **[CLAUDE.md](CLAUDE.md)** — architecture, the Folia threading model, and licence obligations
- **[PROGRESS.md](PROGRESS.md)** — what was changed and what was verified
- **[TODO.md](TODO.md)** — remaining work and known limitations
- **[HANDOFF.md](HANDOFF.md)** — how to pick this work up

## Credits

**Multiverse-Core is the work of [The Multiverse Team](https://github.com/Multiverse)** and its
contributors — dumptruckman, Rigby, fernferret, lithium3141, main--, benwoo1110, Zax71 and many
others. This fork only adapts their work to Folia; the plugin itself is theirs.

Please support the original project:
[GitHub Sponsors](https://github.com/sponsors/Multiverse) ·
[Open Collective](https://opencollective.com/multiverse-plugins) ·
[Discord](https://discord.gg/NZtfKky)

## License

Multiverse-Core is licensed under the **BSD 3-Clause License**,
`Copyright (c) 2011, The Multiverse Team. All rights reserved.`

This fork is redistributed under the same licence, and retains the original copyright notices in
full. See **[LICENSE.md](LICENSE.md)** for the complete text.

In accordance with the third clause of that licence, the name of The Multiverse Team and the names
of its contributors are **not** used to endorse or promote this fork. Any modifications from
upstream are the responsibility of this fork's maintainer, not of The Multiverse Team.
