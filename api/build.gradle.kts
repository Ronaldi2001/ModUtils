plugins {
    id("java-library")
}

dependencies {
    // Bukkit API only: the contract between the core and the platforms. The same names exist on Paper, Folia and Spigot.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
