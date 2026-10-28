plugins {
    id("java-library")
}

dependencies {
    compileOnly(project(":api"))
    // The Paper platform: region and entity schedulers (Folia) and Brigadier commands
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21 // runs on the Java 21 of 1.21.11 servers too
}

// The version adapters (paper:v1_21_11, ...) are set up by one function in the root build.gradle.kts
@Suppress("UNCHECKED_CAST")
val configureAdapter = rootProject.extra["configureAdapter"] as (Project) -> Unit
subprojects.filter { it.name.startsWith("v") }.forEach(configureAdapter)
