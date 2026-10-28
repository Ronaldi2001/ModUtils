# Architecture

How Mod Utils is put together, for people who want to read, fix or extend it. For what the plugin does and how to use
it, see the [README](../README.md).

## Modules

| Path | What |
|---|---|
| `api/` | The contract between the core and the server software: `Platform`, `ModScheduler`, `CommandActions`. Bukkit API only. |
| `shared/` | Plugin class, adapter loader, config, translations, plugin hooks, update checker, mod compat and mod block. Written against `api`, so it runs on every platform. |
| `paper/` | The Paper platform (also Folia): entity and region schedulers, Brigadier `/modutils`, Paper's events. |
| `bukkit/` | The Bukkit platform (Spigot, CraftBukkit): Bukkit scheduler, `/modutils` as a plain command, components sent with adventure-platform-bukkit. |
| `common/` | The compat code (JEI, Jade, The One Probe, AppleSkin, Polymer) and mod block bridge that use Minecraft internals, written once for the newest version. Compiled once per adapter, and by itself for the IDE only. |
| `paper/adapter/`, `bukkit/adapter/` | Files that replace or add to `common` for one platform (for example `AdventureBridge`, `ConfigurePhase`, `SignProbe`). |
| `paper/vX`, `bukkit/vX` | One adapter module per Minecraft version and platform: the dev bundle (Paper) or Spigot mappings (Bukkit), plus any file that must differ for that version only. |
| `plugin/` | Packs everything into the single jar, with `config.yml`, `paper-plugin.yml`, `plugin.yml`, translations and the run-server tasks. |
| `tools/` | Developer tools, see [tools/README.md](../tools/README.md). |

The platform is picked when the plugin turns on (`ModUtilsPlugin.loadPlatform`): Paper if its scheduler classes exist,
else Bukkit. The platform then names the adapter package (`me.ronaldi2001.modutils.nms.<platform>.<version>`), which
`AdapterLoader` loads for the running Minecraft version.

## How an adapter is compiled

An adapter compiles a merged copy of three places, a file with the same path replacing the one before it:

1. `common/src/main/java`: the Minecraft-internals code, written once in the newest form.
2. `<platform>/adapter/src/main/java`: what the platform does its own way (Paper's native calls, or Bukkit's).
3. `<platform>/<version>/src/main/java`: files for that version only (`Nms`, `NmsAdapter`, `VersionShim`, ...).

`Nms` holds the Minecraft calls that changed between versions; the shared code calls them through it. A version's own
`Nms` repeats everything the versions before it changed in the same file.

Before 1.21.11 Mojang called `Identifier` `ResourceLocation`. The shared code is written with the new name, and for those
adapters the build swaps it in while copying (`legacyNames` in the root `build.gradle.kts`). Files that live in a
version's own folder are written with that version's names.

Spigot runs on obfuscated names before 26.1, so the Bukkit adapters are remapped to Spigot's (`reobfJar`) and given the
CraftBukkit package of their version (`craftBukkitPackages`). Every adapter is packed with its classes moved to
`me.ronaldi2001.modutils.nms.<platform>.<id>`, so all of them fit in one jar.

The IDE sees `common` against one version at a time (`ideVersion` in `gradle.properties`).

### When a new Minecraft version breaks the build

1. Compile fails for `paper:vX` or `bukkit:vX`: put the change in that version's `Nms` if it is a call that differs, or
   a copy of the offending file at the same path under `<platform>/vX/src/main/java/...` (see
   `paper/v26_3/.../VersionShim.java`).
2. New version, new modules: add it to `settings.gradle.kts`, `plugin/build.gradle.kts` (`versions` and run tasks),
   `AdapterLoader.ADAPTERS` and `craftBukkitPackages` in the root `build.gradle.kts` (up to 1.21.11), and give both
   `paper/vX` and `bukkit/vX` a `build.gradle.kts` with the same `paperDevBundle`.

## The One Probe

NeoForge clients with [The One Probe](https://github.com/McJtyMods/TheOneProbe) (1.20.6 and newer) get the probe filled
by the server. TOP asks with `theoneprobe:getinfo` / `getentityinfo` and the plugin answers with `returninfo` /
`returnentityinfo`: a tree of text, item, progress and row/column elements (`compat/top`). The layout is the same in
TOP's 1.20.6, 1.21.11 and 26.2 branches, so one writer serves every adapter; only item stacks and text use the
version's own codecs, like the other compats.

Shown: the block or entity name with its mod, crop growth, furnace contents and progress, brewing stand fuel and time,
container contents (in rows, including `StorageSource` barrels), and for entities health, owner and carried items.
Config: `modCompat.top.enabled`, `modCompat.top.debug`, `modCompat.top.minRequestIntervalMs` and `modCompat.top.features.*`.

Written from TOP's source and checked for loading and compiling on every adapter; not yet tried with a real TOP client.
TOP has no 26.3 release yet. The Fabric builds of TOP were not looked at.

## Polymer client support

Players with the [Polymer](https://github.com/Patbox/polymer) client mod can see custom items (items with a custom
`item_model` or `custom_model_data`) as what they are, in JEI recipe inputs and in their inventory. The plugin speaks
Polymer's protocol itself (code in `common/...//polymer`), written from Polymer's source for each version:

| Server / client | Polymer | item + creative tab packets | notes |
|---|---|---|---|
| 1.21.2 - 1.21.4 | 0.10, 0.11 | 9 | the creative tab (`item_group`) contents are two plain lists of stacks |
| 1.21.5 | 0.12 | 11 | |
| 1.21.6 - 1.21.11 | 0.13 - 0.15 | 13 | no extended recipe ingredients in any of 1.21.x |
| 26.1 | 0.16 | 14 | the tab channels are called `creative_tab` from here |
| 26.2 | 0.17 | 15 | |
| 26.3 | 0.18 | 15 | exact (component matching) ingredients |

The handshake, metadata, sync start/finish and item entry layouts are the same in all of them.

1. In the configuration phase it sends `polymer:hello`; clients without the mod ignore it. (Before Paper 1.21.8 there
   is no event for that phase, so the hello goes out on join, which Polymer's late handshake accepts.)
2. The client answers with its handshake and metadata; the plugin replies with its own.
3. On join it syncs the custom items (`polymer:sync/items`), after which stacks of those items sent to that client carry
   Polymer's `$polymer:stack` marker in their custom data (removed again on creative-slot packets coming back).
4. JEI recipes whose inputs are exact (custom) items are written in Polymer's extended ingredient layout.

Config: `modCompat.polymer.enabled` / `modCompat.polymer.debug`. The packet versions follow the client's own Polymer release (by the
Minecraft version it reports); a client whose Polymer writes other versions of the packets is left alone (the log says
so).

### Clients on another Minecraft version than the server

With ViaVersion, a client on any version from 1.21.2 to 26.3 can use Polymer on a server of any of them. The Polymer client
only reads the sync packets if the server's metadata names the client's own protocol, so the plugin answers with the
client's protocol instead of the server's (the metadata is sent once the client's own has arrived). Items are then
written the way the client reads them: ids remapped through ViaVersion, only the components that pick a model, name or
lore kept, plus Polymer's `$polymer:stack` marker. The marker is also added to the stacks the server sends (ViaVersion
passes the custom data through). An item that has no equivalent on the client's version is left out of its tab.

## 1.20.5 to 1.21.1

Two adapters (`v1_20_6` for 1.20.5 and 1.20.6, `v1_21_1` for 1.21 and 1.21.1) serve these servers, with **AppleSkin and
Jade only**. **JEI and Polymer start at 1.21.3** (the first Paper version after 1.21.1): they do not work on these
servers, so the adapters do not compile their code (`excludedFiles` in the root `build.gradle.kts`), do not list them in
`/modutils`, and ignore the `modCompat.jei` and `modCompat.polymer` sections of `config.yml`.

Jade speaks three different languages across these versions, and a server answers every client that can join it, told
apart by the channel the client registers:

| Jade client | Registers | Class |
|---|---|---|
| 1.21.2 and newer | `jade:server_handshake` | `JadeCompat` (the same on every server) |
| 1.21, 1.21.1 (Jade 15) | `jade:server_ping_v1` | `Jade121Compat`: the server pings the client with its provider ids, requests name the providers and carry the block state, the data is written as binary streams under the provider ids |
| 1.20.5, 1.20.6 (Jade 14) | `jade:server_ping` | `Jade1205Compat` (`v1_20_6` only): the ping holds only a config string, requests name nothing but their target, and the server runs every provider; data is plain NBT under keys such as `furnace`, `Bees`, `JadeItemStorage` and `OwnerName` |

A block request from a 1.21.2+ client on a server older than that has one more byte in its block hit (`worldBorderHit`),
which `Jade121Compat` reads. **AppleSkin** uses the same channels on every version.

Written from the source of Jade's 1.20.5 and 1.21 branches and checked for loading only; nothing has been tried with a
client of these versions yet.

## Notes / limits

- Polymer support is untested against a real client so far (1.21.11, 26.1 and 26.2 were written from Polymer's source
  only, and cross-version Polymer is new on top of that), and moving a custom item in a container makes
  the server resend that slot (the client's item hash differs by the marker).
- JEI recipe sync: Fabric API only has it from 1.21.10 (clients on 1.21.9 and older get none, and need none from us),
  NeoForge from 1.21.4. The server's recipes are read in the server's layout (1.21.3/1.21.4, 1.21.5 to 1.21.11,
  26.1/26.2 or 26.3) and written in the client's, so a client on another version than the server (via ViaVersion) gets
  them too, with the items remapped (see `RecipeTranscoder`). Only the data components that pick a model, name or lore
  survive the remapping.
- With `modCompat.jei.debug: true` the plugin checks its recipe conversion on startup (`RecipeSelfTest`): every recipe of the
  server is written in each other recipe layout and back, which the server's own codec must read to the last byte. The
  log line says how many conversions were checked and how many failed (none, on 1.21.3 to 26.3).
- JEI's recipe transfer (the + button) and the packets Jade and AppleSkin use are the same on every version.
- Jade answers the handshake strings `7` (1.21.2 - 1.21.5), `8` (1.21.6 - 1.21.8) and `9` (1.21.9 and newer).
- Custom storages (Jade and The One Probe): other plugins register a `StorageSource` with Bukkit's ServicesManager (see its javadoc).
- Config: `plugins/ModUtils/config.yml` (everything of the mod compat feature is under `modCompat`).
