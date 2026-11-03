### 1.0.3-Alpha

- Added Support for [TrashSlot](https://modrinth.com/mod/trashslot) mod
- Added Support for [Shulker Box Tooltip](https://modrinth.com/mod/shulkerboxtooltip) mod
- Jade & TheOneProbe:
    - Added support for custom blocks with storage
- Added brewing recipes to JEI
- Added config options
    - Polymer:
        - Tab name (Shows as "mod name" in tooltips for mods that display that)
        - Item namespace (Shows as "mod name" in jei list)
    - JEI:
        - Recipe namespace (Shows as Recipe ID name) [copies Polymer Item namespace]
        - Brewing sync (Clients 26.3)
        - Hold Joins Until Ready (Ensures clients wait to join until recipe cache calculated)
- Added bStats [[view](https://bstats.org/plugin/bukkit/Mod%20Utils/35093)] (anonymous usage stats, can be
  turned off with `metrics.enabled` in the config)

---

### 1.0.2-Alpha

- Fixed plugin from not loading on Spigot servers
- Added custom block names to Jade and TheOneProbe (Jade icon can't get updated)
- Correct item is given when taken out of custom creative tab with Polymer

---

### 1.0.1-Alpha

- Fixed ghost item at cursor when clicking custom items
- Added Update Checker
- Added Bukkit Support
- Added prefix for custom item (craftengine:default:topaz, nexo:ruby, etc.)

---

### 1.0.0

First release. Server-side support for client mods on Paper 1.20.5 to 26.3, in one jar.

## Client mod support

- **JEI**: recipe syncing and "+" recipe transfer, cheat permission (1.21.3 and later)
- **Polymer**: client handshake and sync for Polymer clients (1.21.3 and later)
- **Jade**: block and entity tooltips, including furnaces and custom storages
- **The One Probe**: block and entity info
- **AppleSkin**: saturation and exhaustion sync

## Features

- **modBlock**: optionally block players who use listed client mods, with a bypass permission
  (`modutils.modblock.bypass`)
- **Custom items**: works with CraftEngine, ItemsAdder, Nexo and Oraxen items
- **ViaVersion**: clients on other versions than the server get correctly translated items
- **Bedrock**: Geyser and Floodgate players are detected and ignored
- **Folia** supported
- Translations (`en_US` included)

## Commands

- `/modutils status | players | set | reload` (permission `modutils.admin`)

## Notes

- Server-side only. Players still need the client mods installed.
- Each compat can be turned on or off in `config.yml`.