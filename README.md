# Mod Utils

**Server-side support for client mods on Minecraft servers, plus a way to keep unwanted mods out.**

Mod Utils lets players with popular client mods get the full experience on a plain Paper or Spigot server, with
support for clients on Fabric or NeoForge. It also has a mod blocker that kicks players whose client has mods you don't
allow.

[Architecture](docs/ARCHITECTURE.md) · [Contributing](CONTRIBUTING.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

[![Available on Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg)](https://modrinth.com/plugin/mod-utils)

---

## Platforms

![Supported Paper Logo](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_vector.svg)
![Supported Purpur Logo](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/purpur_vector.svg)
![Supported Spigot Logo](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/spigot_vector.svg)
![Supported Bukkit Logo](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/bukkit_vector.svg)

One jar runs on **Paper, Folia, Purpur, Spigot and CraftBukkit**, from **1.20.5 up to 26.3**. With
**ViaVersion**, server and client versions can be mixed freely, as long as both are in the supported range for the mod.

| Mod | Server version | Client version |
|---|---|---|
| [Jade](https://modrinth.com/mod/jade) | 1.20.5 or newer | 1.20.5 or newer |
| [The One Probe](https://modrinth.com/mod/the-one-probe) | 1.20.5 or newer | 1.20.5 or newer |
| [AppleSkin](https://modrinth.com/mod/appleskin) | 1.20.5 or newer | 1.20.5 or newer |
| [JEI](https://modrinth.com/mod/jei) | **1.21.3 or newer** | **1.21.3 or newer** |
| [Polymer](https://modrinth.com/mod/polymer) | **1.21.3 or newer** | **1.21.3 or newer** |

- Without ViaVersion, clients must be on the same version as the server. Clients older than the server also need
  [ViaBackwards](https://modrinth.com/plugin/viabackwards).
- JEI and Polymer work together: with Polymer on the client, custom items from your server show correctly in JEI
  recipes. Without it, recipes that use custom items are shown with a stand-in item (set in `config.yml`).
- The server never needs the client mods installed.

## Features

### Mod compat

Each mod can be switched on or off in `config.yml`, and only does something for players who have that mod installed.

| Mod | What the server adds |
|---|---|
| Jade | Furnace and brewing progress, beehives, jukeboxes, chest, barrel and shulker contents, mob growth and breeding timers, pet owners, custom block names |
| The One Probe | Crop growth, furnaces, brewing stands, storage contents, mob health, pet owners |
| AppleSkin | Accurate saturation and exhaustion in the hunger overlay |
| JEI | The + button recipe transfer, and sending your server's recipes, custom ones included |
| Polymer | Custom items show properly in JEI recipes, and operators see them in the creative tab |

Individual features (`furnace`, `itemStorage`, `mobHealth`, ...) can be switched off, and per-player rate limits protect
the server from request spam.

### Mod block

Kicks players whose client has mods you don't want, and lists everything it found. Off by default.

- **Channel detection** blocks mods that use networking, by mod id.
- **Translation detection** finds client-only mods without networking, by checking whether one of their language keys
  exists on the client.
- The kick message is fully customisable, and the permission `modutils.modblock.bypass` skips the check.

### Plugin hooks

Mod Utils hooks into other plugins when they are installed, and does nothing for the ones that aren't.
`/modutils status` lists the active hooks.

| Plugin | What it is used for |
|---|---|
| [ViaVersion](https://modrinth.com/plugin/viaversion) | Supports players on other client versions by translating item and component data |
| [Geyser](https://modrinth.com/plugin/geyser) / [Floodgate](https://modrinth.com/mod/floodgate) | Detects Bedrock players, who are skipped by every compat |
| [CraftEngine](https://modrinth.com/plugin/craftengine) (26.5+) | Shows the custom items in CraftEngine recipes correctly in JEI, with no config needed |
| ItemsAdder, Nexo, Oraxen | Look up their items by id for the `modCompat.polymer.ingredients` setting |

### Update checker

Tells the console, and players with the permission `modutils.update` (operators by default), when a newer version is on
Modrinth. Can be turned off in `config.yml`.

## Commands

`/modutils` needs the permission `modutils.admin` (operators by default).

| Command | What it does |
|---|---|
| `/modutils status` | Supported versions, which compats are on, blocked mods, and the active plugin hooks |
| `/modutils players [name]` | Each player's client brand and version and which client mods they have |
| `/modutils set <setting> <value>` | Changes a setting live, saves it to `config.yml` (comments stay) and restarts only that feature |
| `/modutils reload` | Reloads the config and restarts every feature |

## Install

1. Download the jar from [Modrinth](https://modrinth.com/plugin/mod-utils) and drop it in `plugins/`.
2. Start the server, then edit `plugins/ModUtils/config.yml` and run `/modutils reload`.
3. Every message is in `plugins/ModUtils/translations/en_US.yml` ([MiniMessage](https://docs.advntr.dev/minimessage/format.html)).
   Add other languages by adding more locale files.

Needs Java 21 or newer on the server. On Spigot, the server downloads the Adventure libraries it needs on first start.

## Build from source

You need **JDK 25** (the build uses a Java 25 toolchain; the jar itself targets Java 21).

```bash
./gradlew :plugin:shadowJar
```

The jar is written to `plugin/build/libs/ModUtils-<version>.jar`. It holds the version-independent core and one adapter
per Minecraft version and platform; on startup the plugin loads the one that matches the server.

Run a Paper test server with the plugin, one folder per version under `plugin/run/<version>/`:

```bash
./gradlew :plugin:runServer-1.21.11
```

(also `-1.20.6`, `-1.21.1`, `-1.21.3`, `-1.21.4`, `-1.21.5`, `-1.21.6`, `-1.21.8`, `-1.21.10`, `26.1.2`, `26.2`, `26.3`).

## Project layout

| Path | What |
|---|---|
| `api/` | The contract between the core and the server software |
| `shared/` | The plugin itself: config, translations, hooks, update checker, mod compat, mod block |
| `paper/`, `bukkit/` | One module per server platform, with an adapter per Minecraft version |
| `common/` | The code that uses Minecraft internals, written once and compiled for every version |
| `plugin/` | Packs the single jar; holds `config.yml`, `plugin.yml` and the translations |

How the adapters are put together, and the protocol notes for each client mod, are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Contributing

Bug reports, fixes and new version support are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Mod Utils is licensed under [CC BY-NC-SA 4.0](LICENSE.md): free to use and share with credit, not for commercial use,
and changes must be shared under the same license. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the mods it
talks to; none of them are bundled.

"Minecraft" is a trademark of Mojang AB. This plugin is not affiliated with or endorsed by Mojang or Microsoft.
