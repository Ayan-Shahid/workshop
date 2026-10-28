import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.time.LocalDate
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "pk.workshop"
// Release builds pass -PappVersion=1.2.3 (taken from the git tag); local builds use this default.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.1.0"
version = appVersion
// Where installed copies look for updates (latest.json). Empty = updates switched off (local/dev builds).
val updateFeed = (findProperty("updateFeed") as String?) ?: System.getenv("UPDATE_FEED") ?: ""

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

// Version, build date, update feed and the public key that release files must be signed with
val generateAppInfo = tasks.register("generateAppInfo") {
    val out = layout.buildDirectory.dir("generated/appinfo")
    val keyFile = file("packaging/update-public-key.txt")
    inputs.property("version", appVersion); inputs.property("feed", updateFeed)
    if (keyFile.exists()) inputs.file(keyFile)
    outputs.dir(out)
    doLast {
        val f = out.get().file("app.properties").asFile
        f.parentFile.mkdirs()
        val key = if (keyFile.exists()) keyFile.readText().trim() else ""
        f.writeText("version=$appVersion\nbuilt=${LocalDate.now()}\nfeed=$updateFeed\nupdateKey=$key\n")
    }
}
sourceSets["main"].resources.srcDir(generateAppInfo)

// The small launcher that installs downloaded updates before starting the app (plain Java, no dependencies)
val launcher = sourceSets.create("launcher") { java.srcDir("src/launcher/java") }
tasks.named<JavaCompile>("compileLauncherJava") { options.release.set(21) }
tasks.register<Jar>("launcherJar") {
    archiveFileName.set("WorkshopLauncher.jar")
    destinationDirectory.set(layout.buildDirectory.dir("launcher"))
    from(launcher.output)
    manifest { attributes("Main-Class" to "pk.workshop.launcher.Launcher") }
}

tasks.test { useJUnitPlatform(); forkEvery = 1; if (project.hasProperty("regen")) systemProperty("regen", "1"); testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }

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
            packageVersion = appVersion.substringBefore("-")
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

// launch4j (makes the Windows .exe that starts the bundled Java); used by packaging/package.sh
val launch4j by configurations.creating
dependencies {
    launch4j("net.sf.launch4j:launch4j:3.50")
    launch4j("net.sf.launch4j:launch4j:3.50:workdir-linux64")
}
tasks.register<Copy>("fetchLaunch4j") { from(launch4j); into(layout.buildDirectory.dir("launch4j-jars")) }

// Same code -> same jar bytes, so unchanged libraries keep the same name and are never downloaded twice
tasks.withType<Jar>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }

// The app as separate jars (build/stage/<os>/lib + classpath.txt), as installed copies keep it.
// Each jar's name carries part of its SHA-256, so an update only downloads the jars that changed.
tasks.register("stageApp") {
    dependsOn(tasks.jar)
    val out = layout.buildDirectory.dir("stage/$targetOs")
    doLast {
        val dir = out.get().asFile.apply { deleteRecursively() }
        val lib = File(dir, "lib").apply { mkdirs() }
        val jars = listOf(tasks.jar.get().archiveFile.get().asFile) +
            sourceSets["main"].runtimeClasspath.filter { it.isFile && it.name.endsWith(".jar") }.files
        val names = jars.map { f ->
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            val name = "${f.nameWithoutExtension}-${sha.take(12)}.jar"
            f.copyTo(File(lib, name), overwrite = true); name
        }.distinct()
        File(dir, "classpath.txt").writeText(names.joinToString("\n", postfix = "\n"))
        println("Staged ${names.size} jars in $dir")
    }
}
