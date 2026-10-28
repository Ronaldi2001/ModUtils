// Not packed into the jar. The files every Bukkit adapter (bukkit:v1_21_11, ...) takes in place of the ones in common, as a
// module of its own so the IDE can open and check them. Every adapter compiles its own copy of them in its build.
plugins {
    id("java-library")
    id("io.papermc.paperweight.userdev")
}

group = "me.ronaldi2001.modutils.bukkitadapter"

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":shared"))
    compileOnly(project(":common"))
    compileOnly("com.viaversion:viaversion-api:5.11.0")
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    // Not part of the plugin; it stands in for the adapters when the IDE checks these files
    options.isFailOnError = false
}
