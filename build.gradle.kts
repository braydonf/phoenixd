import Versions.ktor
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
import java.io.ByteArrayOutputStream

buildscript {
    dependencies {
        classpath("app.cash.sqldelight:gradle-plugin:${Versions.sqlDelight}")
    }
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform") version Versions.kotlin
    kotlin("plugin.serialization") version Versions.kotlin
    id("app.cash.sqldelight") version Versions.sqlDelight
}

allprojects {
    group = "fr.acinq.lightning"
    version = "0.1-SNAPSHOT"

    repositories {
        // using the local maven repository with Kotlin Multi Platform can lead to build errors that are hard to diagnose.
        // uncomment this only if you need to experiment with snapshot dependencies that have not yet be published.
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        mavenCentral()
        google()
    }
}

/** Get the current git commit hash. */
fun gitCommitHash(): String {
    val stream = ByteArrayOutputStream()
    project.exec {
        commandLine = "git rev-parse --verify --long HEAD".split(" ")
        standardOutput = stream
    }
    return String(stream.toByteArray()).split("\n").first()
}

/**
 * Generates a `BuildVersions` file in build/generated-src containing the current git commit and the lightning-kmp version.
 * See https://stackoverflow.com/a/74771876 for details.
 */
val buildVersionsTask by tasks.registering(Sync::class) {
    group = "build"
    from(
        resources.text.fromString(
            """
            |package fr.acinq.lightning
            |
            |object BuildVersions {
            |    const val phoenixdCommit = "${gitCommitHash()}"
            |    const val phoenixdVersion = "${project.version}-${gitCommitHash().take(7)}"
            |    const val lightningKmpVersion = "${Versions.lightningKmp}"
            |}
            |
            """.trimMargin()
        )
    ) {
        rename { "BuildVersions.kt" }
        into("fr/acinq/lightning")
    }
    into(layout.buildDirectory.dir("generated/kotlin/"))
}

kotlin {
    jvm()

    fun KotlinNativeTargetWithHostTests.phoenixBinaries() {
        binaries {
            executable("phoenixd") {
                entryPoint = "fr.acinq.lightning.bin.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
            executable("phoenix-cli") {
                entryPoint = "fr.acinq.lightning.cli.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
        }
    }

    val currentOs = org.gradle.internal.os.OperatingSystem.current()
    if (currentOs.isLinux) {
        linuxX64 {
            phoenixBinaries()
        }
    }

    if (currentOs.isMacOsX) {
        macosX64 {
            phoenixBinaries()
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(buildVersionsTask.map { it.destinationDir })
            dependencies {
                implementation("fr.acinq.lightning:lightning-kmp:${Versions.lightningKmp}")
                // ktor serialization
                implementation(ktor("serialization-kotlinx-json"))
                // ktor server
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("server-cio"))
                implementation(ktor("server-websockets"))
                implementation(ktor("server-auth"))
                implementation(ktor("server-status-pages")) // exception handling
                // ktor client (needed for webhook)
                implementation(ktor("client-core"))
                implementation(ktor("client-content-negotiation"))
                implementation(ktor("client-auth"))
                implementation(ktor("client-json"))

                implementation("com.squareup.okio:okio:${Versions.okio}")
                implementation("com.github.ajalt.clikt:clikt:${Versions.clikt}")
                implementation("app.cash.sqldelight:coroutines-extensions:${Versions.sqlDelight}")
            }
        }
        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:${Versions.sqlDelight}")
                implementation(ktor("client-okhttp"))
            }
        }
        nativeMain {
            dependencies {
                implementation("app.cash.sqldelight:native-driver:${Versions.sqlDelight}")
            }
        }
        linuxMain {
            dependencies {
                implementation(ktor("client-curl"))
            }
        }
        macosMain {
            dependencies {
                implementation(ktor("client-darwin"))
            }
        }
    }
}

// forward std input when app is run via gradle (otherwise keyboard input will return EOF)
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

sqldelight {
    databases {
        create("PhoenixDatabase") {
            packageName.set("fr.acinq.phoenix.db")
            srcDirs.from("src/commonMain/sqldelight/phoenixdb")
        }
    }
}

