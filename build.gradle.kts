import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "pk.workshop"
version = "1.0.0"

// Build for the current OS by default; pass -PtargetOs=windows|mac|linux to build a jar for another OS.
val targetOs = (findProperty("targetOs") as String?) ?: "current"

dependencies {
    when (targetOs) {
        "windows" -> implementation(compose.desktop.windows_x64)
        "linux" -> implementation(compose.desktop.linux_x64)
        "mac" -> { implementation(compose.desktop.macos_arm64); implementation(compose.desktop.macos_x64) }
        else -> implementation(compose.desktop.currentOs)
    }
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("io.github.willena:sqlite-jdbc:3.53.4.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    testImplementation(kotlin("test"))
}

// Builds with whatever JDK runs Gradle (21 or newer); the output still runs on Java 21.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }

tasks.test { useJUnitPlatform(); forkEvery = 1; testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }

compose.desktop {
    application {
        mainClass = "pk.workshop.MainKt"
        // `./gradlew run` keeps its data in ./dev-data; `./gradlew run -Pdemo` uses ./demo-data filled with sample data
        // (log in as admin / demo123). Installed copies are unaffected and use the normal per-user folder.
        jvmArgs += if (project.hasProperty("demo"))
            listOf("-Dworkshop.demo=1", "-Dworkshop.dataDir=${rootDir}/demo-data")
        else listOf("-Dworkshop.dataDir=${rootDir}/dev-data")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Workshop Manager"
            packageVersion = "1.0.0"
            vendor = "Workshop"
            modules("java.sql", "java.naming", "java.desktop", "jdk.unsupported", "java.management")
            description = "Workshop Manager - car workshop billing, inventory and bookkeeping"
            copyright = "Workshop Manager"
            linux { iconFile.set(project.file("packaging/icon.png")) }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "Workshop Manager"
                dirChooser = true
                menu = true
                shortcut = true
                perUserInstall = false
                upgradeUuid = "6f3c8a52-2f5e-4c3b-9b2f-7d1e0a6c4b11"
            }
        }
    }
}

tasks.register("printCp") {
    doLast { file("/tmp/ws_cp.txt").writeText((sourceSets["main"].runtimeClasspath).asPath) }
}
