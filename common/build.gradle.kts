// Not packed into the jar. This module exists so the IDE can open, search and check the shared adapter code; the
// adapters (paper:v1_21_11, bukkit:v1_21_11, ...) each compile their own copy of it in their build.
//
// The shared code is compiled by every adapter against its own Minecraft, but the IDE can only have it against one at a
// time: the one in `ideVersion` (gradle.properties, or -PideVersion=26.3). Change it and reload the Gradle project to
// browse and check the shared code against another version. Files an adapter replaces with its own (VersionShim for
// 26.3) show errors then, which is expected: the shared copy is for the older versions.
plugins {
    id("java-library")
    id("io.papermc.paperweight.userdev")
}

// Same project name as the core (:shared), so without its own group Gradle takes the two for the same component and
// the dependency on :shared below resolves to this module itself (circular compileJava)
group = "me.ronaldi2001.modutils.nms"

val bundles = mapOf(
    "1.20.6" to "1.20.6-R0.1-SNAPSHOT",
    "1.21.1" to "1.21.1-R0.1-SNAPSHOT",
    "1.21.3" to "1.21.3-R0.1-SNAPSHOT",
    "1.21.4" to "1.21.4-R0.1-SNAPSHOT",
    "1.21.5" to "1.21.5-R0.1-SNAPSHOT",
    "1.21.6" to "1.21.6-R0.1-SNAPSHOT",
    "1.21.8" to "1.21.8-R0.1-SNAPSHOT",
    "1.21.10" to "1.21.10-R0.1-SNAPSHOT",
    "1.21.11" to "1.21.11-R0.1-SNAPSHOT",
    "26.1" to "26.1.2.build.+",
    "26.2" to "26.2.build.+",
    "26.3" to "26.3.build.+"
)
val ideVersion = providers.gradleProperty("ideVersion").getOrElse("1.21.11")

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":shared"))
    compileOnly("com.viaversion:viaversion-api:5.11.0")
    paperweight.paperDevBundle(bundles[ideVersion] ?: error("ideVersion must be one of ${bundles.keys}, not $ideVersion"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// The files Paper does its own way (paper/adapter) are checked here with the rest. The Bukkit ones are in bukkit:adapter.
sourceSets.main {
    java.srcDir(rootProject.file("paper/adapter/src/main/java"))
}

tasks.withType<JavaCompile>().configureEach {
    // 1.21.x servers run on Java 21, 26.x needs 25
    options.release = if (ideVersion.startsWith("26")) 25 else 21
    // Not part of the plugin: for another version than 1.21.11 it can't compile on its own (see above)
    options.isFailOnError = ideVersion == "1.21.11"
}
