import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

kotlin {
    // ponytail: desktop only for now; android/ios targets arrive with build-order steps 8 and 12.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("net.java.dev.jna:jna:5.17.0") // capture shield: user32.SetWindowDisplayAffinity
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "kumiho.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Kumiho"
            packageVersion = "1.0.0"
        }
    }
}
