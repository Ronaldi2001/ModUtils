# Third-party notices

ModUtils talks to client mods over their own network protocols. It does **not** bundle any of these mods, and they are
not dependencies. The licence texts are in [`plugin/src/main/resources/licenses/`](plugin/src/main/resources/licenses)
and are packed into the plugin jar under `licenses/`.

| Project | Licence | How ModUtils relates to it |
|---|---|---|
| [JEI](https://github.com/mezz/JustEnoughItems) (mezz) | MIT, Copyright (c) 2014-2015 mezz | The recipe transfer algorithm (`RecipeTransfer`) is ported from JEI's `BasicRecipeTransferHandlerServer`. The MIT notice must stay with that code. |
| [Jade](https://github.com/Snownee/Jade) (Snownee) | CC BY-NC-SA 4.0 | Compatible with Jade's channels and data layouts (`compat/jade`). No Jade jar is bundled. See the note below. |
| [AppleSkin](https://github.com/squeek502/AppleSkin) (squeek502) | Unlicense (public domain) | Sends the same `appleskin:saturation` / `appleskin:exhaustion` payloads. No conditions. |
| [The One Probe](https://github.com/McJtyMods/TheOneProbe) (McJty) | MIT, Copyright (c) 2016 McJty | Writes the same `theoneprobe:*` packets and element layouts (`compat/top`), read from TOP's source. The MIT notice stays with it. No TOP jar is bundled. |
| [Polymer](https://github.com/Patbox/polymer) (Patbox) | LGPL-3.0 | The client handshake and sync protocol (`compat/polymer`) uses the same channel names, packet layouts and version numbers as Polymer. The code is independent: it runs on Netty and Bukkit, not Fabric API, and shares no code with Polymer. No Polymer jar is bundled. |

"Minecraft" is a trademark of Mojang AB; this plugin is not affiliated with or endorsed by Mojang, Microsoft or the
authors above.

## ModUtils's own licence

ModUtils is released under CC BY-NC-SA 4.0 (see `LICENSE.md`), the same licence as Jade, so any part that follows Jade
is covered. Copyright (c) 2025-2026 Ronaldi2001. Non-commercial use only: no selling the plugin or paywalling features.
