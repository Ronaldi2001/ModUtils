plugins {
    id("java-library")
}

dependencies {
    api(project(":api"))
    // Bukkit API only: nothing in here may touch Minecraft internals, so one build runs on every version.
    // 1.21.11's API is the oldest we support; newer servers keep it working.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.viaversion:viaversion-api:5.11.0")
    // 26.5 and newer share one API (checked 26.5 to 26.9.1); 0.0.x has a different one
    compileOnly("net.momirealms:craft-engine-core:26.9.1")
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1")
    // ItemsAdder 4.0.17 and 4.0.18-beta-10 share one CustomStack; Nexo 1.15 to 1.29.1 share the item lookup
    compileOnly("beer.devs:itemsadder-api:4.0.17")
    compileOnly("com.nexomc:nexo:1.29.1") { isTransitive = false }
    // Oraxen 1.163.0 to 1.220.1 share the item lookup
    compileOnly("io.th0rgal:oraxen:1.220.1") { isTransitive = false }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21 // runs on the Java 21 of 1.21.11 servers too
}
