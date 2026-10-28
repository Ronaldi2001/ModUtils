pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "ModUtils"

// The contract between the core and the platforms (scheduler, commands, platform hooks): Bukkit API only
include("api")

// Version-independent, platform-independent core: the plugin class, config, modules and the adapter loader
include("shared")

// One module per server platform. Paper (and Folia) uses the region schedulers and Brigadier; Bukkit (Spigot,
// CraftBukkit) uses the plain Bukkit ones.
include("paper", "bukkit")

// The Minecraft-internals code every version adapter compiles (written once, newest form). It is a module of its own only
// so the IDE can resolve it: it is compiled against 1.21.11 (-PideVersion=26.3 for another) and is not part of the jar.
include("common")

// One adapter per Minecraft version and platform: paper:<id> is what Paper runs, bukkit:<id> is for Spigot and
// CraftBukkit. Each holds only the files of its own version; the rest comes from common and the platform's adapter folder
// (see gradle/adapter.gradle.kts).
val versions = listOf(
    "v1_20_6", "v1_21_1", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_8", "v1_21_10", "v1_21_11",
    "v26_1", "v26_2", "v26_3"
)
versions.forEach { include("paper:$it", "bukkit:$it") }
// The Bukkit-flavoured files every Bukkit adapter takes, as a module of its own for the IDE (not part of the jar)
include("bukkit:adapter")

// Packs everything into the one jar and holds the run-server tasks
include("plugin")
