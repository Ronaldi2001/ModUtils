import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

// Shared settings. The modules configure themselves in their own build files.
allprojects {

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.viaversion.com")
        maven("https://repo.momirealms.net/releases/")
        maven("https://repo.nexomc.com/releases")
        maven("https://repo.oraxen.com/releases")
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The version adapters: paper/v1_21_11, bukkit/v1_21_11, ... Their modules call this from paper/build.gradle.kts and
// bukkit/build.gradle.kts. It lives here because only the root script can see the classes of the plugins used below.
// // A version module compiles the sources of three places, a file with the same path replacing the one before it:
//   1. common/src/main/java                   the Minecraft-internals code, written once in the newest form
//   2. <platform>/adapter/src/main/java       what the platform does its own way: Paper's native calls, or Bukkit's
//   3. <platform>/<version>/src/main/java     files for this version only (a VersionShim, a NmsAdapter, ...)
//
// Versions before 1.21.11 call Mojang's Identifier ResourceLocation; for those, 1 and 2 are compiled with that name swapped
// in (see `legacyNames`), so they are written once in the newer spelling. The version's own files are written for its names.
//
// An adapter is built for one platform. paper/<version> is what Paper (and Folia) runs: Mojang's names, as Paper's dev
// bundle has them. bukkit/<version> is for Bukkit, Spigot and CraftBukkit: before 26.1 Spigot still runs on obfuscated
// names, so the jar is remapped to Spigot's (`reobfJar`) and the CraftBukkit package gets the version Spigot puts in it
// (`craftBukkitPackages`).
//
// Every adapter is packed with its classes moved to ...modutils.nms.<platform>.<id>, so all of them fit in one jar and the
// loader picks the one for the running server.
val commonSources = rootProject.file("common/src/main/java")

// The version Spigot puts in the CraftBukkit package (org.bukkit.craftbukkit.v1_21_R5.entity.CraftPlayer) up to 1.21.11.
// UNVERIFIED against BuildTools output except 1.20.6 and 1.21.11: check each one when a Bukkit server fails with a
// ClassNotFoundException on org.bukkit.craftbukkit.*. 26.1 and newer are not remapped or relocated.
val craftBukkitPackages = mapOf(
    "v1_20_6" to "v1_20_R4",
    "v1_21_1" to "v1_21_R1",
    "v1_21_3" to "v1_21_R2",
    "v1_21_4" to "v1_21_R3",
    "v1_21_5" to "v1_21_R4",
    "v1_21_6" to "v1_21_R5",
    "v1_21_8" to "v1_21_R5",
    "v1_21_10" to "v1_21_R6",
    "v1_21_11" to "v1_21_R7"
)
// Files of common that only some versions compile: JEI and Polymer start at 1.21.3 (Paper 1.21.2 does not exist), and
// Jade1205Compat is the Jade of 1.20.5 and 1.20.6 clients.
val excludedFiles = mapOf(
    "v1_20_6" to listOf("**/compat/jei/**", "**/compat/polymer/**"),
    "v1_21_1" to listOf("**/compat/jei/**", "**/compat/polymer/**", "**/Jade1205Compat.java")
) + listOf("v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_8", "v1_21_10", "v1_21_11", "v26_1", "v26_2", "v26_3")
    .associateWith { listOf("**/Jade1205Compat.java") }
// Mojang renamed ResourceLocation to Identifier in 1.21.11. Before 1.21, a ResourceLocation was made with its constructor
// instead of fromNamespaceAndPath.
val legacyNames = setOf("v1_20_6", "v1_21_1", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_8", "v1_21_10")

extra["configureAdapter"] = fun(adapter: Project) {
    adapter.run {
        apply(plugin = "java-library")
        apply(plugin = "io.papermc.paperweight.userdev")
        apply(plugin = "com.gradleup.shadow")

        val id = name
        // paper or bukkit: which server the adapter is packed for
        val platform = parent!!.name
        // 1.21.x servers run on Java 21, 26.x needs 25
        val javaRelease = if (id.startsWith("v1_2")) 21 else 25

        dependencies {
            "compileOnly"(project(":api"))
            "compileOnly"(project(":shared"))
            "compileOnly"("com.viaversion:viaversion-api:5.11.0")
        }

        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release = javaRelease
        }

        // The tool that maps a 1.21.x server jar to Mojang's names does not read the class files of Java 25 (it looks at
        // the JDK's own classes too), so it runs on Java 21 for those versions
        if (id.startsWith("v1_2")) {
            extensions.configure<io.papermc.paperweight.userdev.PaperweightUserExtension>("paperweight") {
                val toolchains = project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)
                javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            }
        }

        val platformSources = rootProject.file("$platform/adapter/src/main/java")
        val renameForLegacy = id in legacyNames
        // Before 1.21 a ResourceLocation is made with its constructor
        val oldIdFactory = id == "v1_20_6"

        // The three source places merged into one copy, which is what gets compiled. The module's source root stays its own
        // src/main/java, so the IDE resolves the version's own files against this version's Minecraft (the copy is not a source
        // root, which would show every class twice).
        val adapterSources = tasks.register<Sync>("assembleAdapterSources") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            filteringCharset = "UTF-8"
            // Each place is its own `from`, applied in this order, so a later one replaces an earlier one
            val ownDir = layout.projectDirectory.dir("src/main/java").asFile
            listOf(commonSources, platformSources, ownDir).forEach { dir ->
                val own = dir == ownDir
                from(dir) {
                    if (!own) exclude(excludedFiles[id] ?: listOf())
                    if (renameForLegacy && !own) {
                        filter { line: String ->
                            line.replace("net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation")
                                .replace(Regex("""\bIdentifier\b"""), "ResourceLocation")
                                .replace(".identifier()", ".location()")
                                .replace(".readIdentifier(", ".readResourceLocation(")
                                .replace(".writeIdentifier(", ".writeResourceLocation(")
                                .let { if (oldIdFactory) it.replace("ResourceLocation.fromNamespaceAndPath(", "new ResourceLocation(") else it }
                        }
                    }
                }
            }
            into(layout.buildDirectory.dir("adapter-src"))
        }
        tasks.named<JavaCompile>("compileJava") {
            dependsOn(adapterSources)
            setSource(layout.buildDirectory.dir("adapter-src"))
        }

        // Only while the IDE imports the project: common is a module of its own, and the version's own files refer to it.
        // It never reaches the build.
        if (providers.systemProperty("idea.sync.active").isPresent) {
            dependencies {
                "compileOnly"(project(":common"))
            }
        }

        tasks.named<ShadowJar>("shadowJar") {
            archiveClassifier = "adapter"
            // Only this adapter's own classes: no dependencies, the server and the core provide those
            configurations = emptyList<Configuration>()
            relocate("me.ronaldi2001.modutils.nms", "me.ronaldi2001.modutils.nms.$platform.$id")
            // Spigot's CraftBukkit classes are in a package with the version in it, Paper's are not
            val craftBukkit = if (platform == "bukkit") craftBukkitPackages[id] else null
            if (craftBukkit != null) relocate("org.bukkit.craftbukkit", "org.bukkit.craftbukkit.$craftBukkit")
        }

        // What goes into the plugin jar for this adapter: its shadow jar, remapped to Spigot's names where Spigot needs them
        val shadow = tasks.named<ShadowJar>("shadowJar").get()
        val reobf = platform == "bukkit" && craftBukkitPackages.containsKey(id)
        if (reobf) {
            extensions.configure<io.papermc.paperweight.userdev.PaperweightUserExtension>("paperweight") {
                reobfArtifactConfiguration.set(io.papermc.paperweight.userdev.ReobfArtifactConfiguration.REOBF_PRODUCTION)
            }
            val reobfJar = tasks.named("reobfJar").get()
            (reobfJar.property("inputJar") as RegularFileProperty).set(shadow.archiveFile)
            extensions.extraProperties["adapterJar"] = reobfJar.property("outputJar")
        } else {
            extensions.extraProperties["adapterJar"] = shadow.archiveFile
        }
    }
}
