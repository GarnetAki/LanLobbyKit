import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

dependencies {
    implementation(project(":lobby-ui-compose"))
    implementation(project(":demo:pingpong"))
    implementation(project(":demo:roulette"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

compose.desktop {
    application {
        mainClass = "dev.ebagamedev.lobby.demo.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LanLobbyKitDemo"
            packageVersion = "1.0.0"
        }
    }
}
