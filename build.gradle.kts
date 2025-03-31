plugins {
    java
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("io.papermc.hangar-publish-plugin") version "0.1.2"
}

group = "dev.mja00"
version = "1.2"

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
    runServer {
        minecraftVersion("1.21.4")
    }
}

val supportedVersions = listOf("1.21.x")

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString() + "-snapshot-" + getCurrentCommitHash())
        id.set("VillagerLobotomy")
        channel.set("Snapshot")
        changelog = fetchLastCommitMessage()

        apiKey.set(System.getenv("HANGAR_API_KEY"))

        platforms {
            paper {
                jar.set(tasks.jar.flatMap { it.archiveFile })
                platformVersions.set(supportedVersions)
            }
        }
    }
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// Thanks emily :)

fun fetchLastCommitMessage(): Provider<String> =
    providers.exec { commandLine("git", "log", "-1", "--pretty=%B") }.standardOutput.asText.map(String::trim)

fun getCurrentCommitHash(): Provider<String> =
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.map(String::trim)