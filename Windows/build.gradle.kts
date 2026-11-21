plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "com.myclivebot"
version = "0.13.15"

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "com.myclivebot.windows.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "TikLBot"
            packageVersion = "0.13.15"
            description = "TikLBot — TikTok LIVE reader"
            vendor = "TikLBot"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/main/resources"))
            windows {
                menuGroup = "TikLBot"
                upgradeUuid = "7C1B4E21-65F0-4B79-A6B7-9A8A3F9A13D2"
                shortcut = true
                dirChooser = true
            }
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("javazoom:jlayer:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("io.github.jvoice-project:piper-jni:1.4.2")
    // JavaFX WebView is embedded in the desktop UI for YouTube search and playback.
    implementation("org.openjfx:javafx-base:17.0.10:win")
    implementation("org.openjfx:javafx-graphics:17.0.10:win")
    implementation("org.openjfx:javafx-web:17.0.10:win")
    implementation("org.openjfx:javafx-media:17.0.10:win")
    implementation("org.openjfx:javafx-swing:17.0.10:win")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test { useJUnitPlatform() }
