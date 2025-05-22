plugins {
    java
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("io.papermc.hangar-publish-plugin") version "0.1.2"
    id("com.gradleup.shadow") version "9.0.0-beta13"
}

group = "dev.mja00"
version = "1.9.1"

repositories {
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "sonatype"
        url = uri("https://oss.sonatype.org/content/groups/public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("net.kyori:adventure-text-serializer-plain:4.19.0")
    implementation(group = "org.bstats", name = "bstats-bukkit", version = "3.1.0")
}

val targetJavaVersion = 21
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"

    if (targetJavaVersion >= 10 || JavaVersion.current().isJava10Compatible()) {
        options.release.set(targetJavaVersion)
    }
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    runServer {
        dependsOn(shadowJar)
        minecraftVersion("1.21.5")
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }

    shadowJar {
        minimize()

        archiveClassifier = null
        archiveVersion = project.version.toString()

        dependencies {
            include(dependency("org.bstats:bstats-bukkit"))
            include(dependency("org.bstats:bstats-base"))
        }

        relocate("org.bstats", "dev.mja00.villagerLobotomizer.bstats")
    }
}

val supportedVersions = listOf("1.21.x")

hangarPublish {
    publications.register("plugin") {
        val currentCommitHash = getCurrentCommitHash().get()
        println("Current commit hash: $currentCommitHash")
        val isTagged = isThisCommitTagged().get()
        println("Is this commit tagged? $isTagged")
        val tag = if (isTagged) getCommitTag().get() else null
        if (tag != null) {
            println("Tag: $tag")
        }
        id.set("VillagerLobotomy")
        // If the tag is the same as the current project version then we're doing a release publish
        if (tag != null && tag.replace("v", "") == project.version.toString()) {
            println("Tagged and tag matches version, doing a release publish")
            version.set(project.version.toString())
            channel.set("Release")
        } else {
            println("Not tagged or tag does not match version, doing a snapshot publish")
            version.set(project.version.toString() + "-snapshot-" + currentCommitHash)
            channel.set("Snapshot")
        }
        changelog = fetchLastCommitMessage()

        apiKey.set(System.getenv("HANGAR_API_KEY"))

        platforms {
            paper {
                jar.set(tasks.shadowJar.flatMap { it.archiveFile })
                platformVersions.set(supportedVersions)
            }
        }
    }
}

// Add explicit dependency for the publish task
tasks.named("publishPluginPublicationToHangar") {
    dependsOn(tasks.shadowJar)
}

// Thanks emily :)

fun fetchLastCommitMessage(): Provider<String> =
    providers.exec { commandLine("git", "log", "-1", "--pretty=%B") }.standardOutput.asText.map(String::trim)

fun getCurrentCommitHash(): Provider<String> =
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.map(String::trim)

fun isThisCommitTagged(): Provider<Boolean> =
    providers.exec {
        commandLine("git", "describe", "--exact-match", "--tags")
        isIgnoreExitValue = true
        // will spit out fatal: No names found, cannot describe anything. if not tagged
        // Also ignore any exit codes
    }.standardOutput.asText.map {
        it.isNotEmpty() && !it.startsWith("fatal")
    }

fun getCommitTag(): Provider<String> =
    providers.exec {
        commandLine("git", "describe", "--exact-match", "--tags")
    }.standardOutput.asText.map {
        it.trim()
    }
