import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("java-library")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

val versions = listOf(
    "v1_20_6", "v1_21_1", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_8", "v1_21_10", "v1_21_11", "v26_1", "v26_2", "v26_3"
)
// "paper" or "bukkit" and the version: the adapter modules <platform>/<version>
val adapters = listOf("paper", "bukkit").flatMap { platform -> versions.map { "$platform:$it" } }

// The adapters tell the jar below where theirs is
adapters.forEach { evaluationDependsOn(":$it") }

dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    implementation(project(":paper"))
    implementation(project(":bukkit"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    processResources {
        val props = mapOf("version" to project.version.toString())
        // Without this a changed version can keep the old one in plugin.yml, from a cached or up-to-date build
        inputs.property("version", props.getValue("version"))
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) { expand(props) }
    }

    // The one jar for every version: the core plus each adapter's classes (already moved into their own packages)
    shadowJar {
        archiveClassifier = ""
        archiveBaseName = "ModUtils"
        adapters.forEach { id ->
            // The jar of an adapter: its classes alone, moved into their own package (see gradle/adapter.gradle.kts)
            @Suppress("UNCHECKED_CAST")
            val adapterJar = project(":$id").extensions.extraProperties["adapterJar"] as Provider<RegularFile>
            dependsOn(adapterJar)
            from(zipTree(adapterJar)) { exclude("META-INF/**") }
        }
        manifest {
            // Tells Paper the plugin is already Mojang-mapped, so it is never remapped
            attributes("paperweight-mappings-namespace" to "mojang")
        }
    }
    jar { enabled = false }
    assemble { dependsOn(shadowJar) }

    // /run/<version> keeps one world and config per server version
    runServer { enabled = false }
    listOf("1.20.6", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.10", "1.21.11", "26.1.2", "26.2", "26.3").forEach { mc ->
        register<RunServer>("runServer-$mc") {
            group = "run paper"
            description = "Runs a Paper $mc server with the plugin"
            minecraftVersion(mc)
            runDirectory = layout.projectDirectory.dir("run/$mc")
            pluginJars.from(shadowJar.flatMap { it.archiveFile })
            jvmArgs("-Xms2G", "-Xmx2G")
            javaLauncher = project.javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        }
    }
}
