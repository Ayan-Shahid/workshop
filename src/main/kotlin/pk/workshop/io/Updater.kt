package pk.workshop.io

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pk.workshop.data.Settings
import pk.workshop.db.Db
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.Properties

/** What this copy is: filled in by the build (see generateAppInfo in build.gradle.kts). */
object AppInfo {
    private val p = Properties().apply { AppInfo::class.java.getResourceAsStream("/app.properties")?.use { load(it) } }
    val version: String = p.getProperty("version", "dev")
    val built: String = p.getProperty("built", "")
    val feed: String = System.getProperty("workshop.updateFeed") ?: p.getProperty("feed", "")
    val updateKey: String = p.getProperty("updateKey", "")
    val os: String = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("mac" in it) "mac" else "linux" }
}

/**
 * Automatic updates.
 *
 * Releases are published on GitHub with a small latest.json that lists, per OS, the program's jars (name, size, SHA-256)
 * and an Ed25519 signature over that list. This copy only accepts a list signed with the key it was built with.
 *
 * Only the jars this copy doesn't already have are downloaded (usually just the app's own few MB) into app/update/, each is
 * checked, and a backup of the records is made. The launcher switches to the new list on the next start and keeps the
 * previous one; if the new version fails to start three times, it goes back by itself.
 */
object Updater {
    sealed interface State
    data object Idle : State
    data object Checking : State
    data class UpToDate(val at: String) : State
    data class Available(val r: Release) : State
    data class Downloading(val r: Release, val done: Long) : State
    data class Ready(val version: String, val notes: String) : State
    data class Failed(val message: String) : State

    data class Jar(val name: String, val size: Long, val sha256: String)
    /** A newer version: its jars in start order, and [need] = the ones this copy doesn't have yet. */
    data class Release(val version: String, val date: String, val notes: String, val base: String, val jars: List<Jar>, val need: List<Jar>) {
        /** Bytes to download (only the jars that changed). */
        val size get() = need.sumOf { it.size }
    }

    var state by mutableStateOf<State>(Idle)
        private set

    const val AUTO = "update.auto"
    const val LAST_CHECK = "update.last_check"
    private const val CHECK_EVERY_HOURS = 6L

    /** The folder holding the program's jars (lib/, current.txt), when the app was started by the launcher (installed copies). */
    val appDir: File? = System.getProperty("workshop.appDir")?.let { File(it) }
    private val upDir get() = File(appDir, "update")

    /** Why updates can't run in this copy, or null when they can. */
    val unavailableReason: String? = when {
        AppInfo.feed.isBlank() || AppInfo.updateKey.isBlank() -> "This copy was built without an update address, so it doesn't update itself."
        appDir == null -> "Updates install only in the packaged app (not when run from the source code)."
        else -> null
    }
    val enabled get() = unavailableReason == null

    var auto: Boolean
        get() = Settings.bool(AUTO, true)
        set(v) = Settings.setBool(AUTO, v)

    // ---------- start-up bookkeeping with the launcher ----------

    /** The app came up properly: the launcher no longer needs to consider going back to the previous version. */
    fun confirmStarted() {
        val t = File(upDir, "trial")
        if (appDir != null && t.exists()) { log("version ${AppInfo.version} started fine"); t.delete() }
    }

    /** The new version couldn't open the records: go back to the previous version at the next start. */
    fun markBroken(): Boolean {
        val t = File(upDir, "trial")
        if (appDir == null || !t.exists()) return false
        t.writeText("${AppInfo.version} 99"); log("version ${AppInfo.version} couldn't open the records; going back at next start")
        return true
    }

    /** A ready update (downloaded in an earlier session), so the banner can offer a restart. */
    fun pendingOnDisk(): String? = File(upDir, "ready").takeIf { appDir != null && it.exists() }?.readText()?.trim()

    // ---------- checking and downloading ----------

    private val http by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(20)).build()
    }

    /** Runs in the background after login: checks now and every few hours; installs by itself when automatic updates are on. */
    fun startBackground() {
        if (!enabled) return
        pendingOnDisk()?.let { state = Ready(it, "") }
        Thread({
            Thread.sleep(15_000)
            while (true) {
                try {
                    if (state !is Ready && state !is Downloading) {
                        val r = check()
                        if (r != null && auto) download(r)
                    }
                } catch (e: Exception) { log("background check failed: ${e.message}") }
                Thread.sleep(CHECK_EVERY_HOURS * 3600_000)
            }
        }, "updates").apply { isDaemon = true; start() }
    }

    /** Asks GitHub for the latest release. Returns it when it's newer than this copy. */
    @Synchronized
    fun check(): Release? {
        if (!enabled) return null
        state = Checking
        return try {
            val res = http.send(HttpRequest.newBuilder(URI(AppInfo.feed)).timeout(Duration.ofSeconds(30)).header("Cache-Control", "no-cache").build(),
                HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 404) { state = UpToDate(now()); return null }  // nothing published yet
            if (res.statusCode() != 200) error("the update server answered ${res.statusCode()}")
            val j = Json.parse(res.body()) as Map<*, *>
            val version = j["version"] as String
            val plat = ((j["platforms"] as? Map<*, *>)?.get(AppInfo.os) as? Map<*, *>)
            val minJava = (j["minJava"] as? Number)?.toInt() ?: 21
            if (Db.isOpen) Settings.set(LAST_CHECK, now())
            when {
                compare(version, AppInfo.version) <= 0 || plat == null -> { state = UpToDate(now()); null }
                version in failedVersions() -> { state = UpToDate(now()); null }
                Runtime.version().feature() < minJava -> {
                    state = Failed("Version $version needs a newer Java runtime. Download the full package from the releases page and reinstall once."); null
                }
                else -> {
                    val jars = (plat["files"] as List<*>).map { f -> f as Map<*, *>; Jar(f["name"] as String, (f["size"] as Number).toLong(), (f["sha256"] as String).lowercase()) }
                    if (jars.any { it.name.contains('/') || it.name.contains('\\') || it.name.startsWith(".") }) error("the update list is not valid")
                    if (!verify(version, AppInfo.os, jars, plat["sig"] as String)) error("the update isn't signed by your workshop's key, so it was refused")
                    val lib = File(appDir, "lib")
                    val need = jars.filter { val f = File(lib, it.name); !(f.exists() && f.length() == it.size) }
                    Release(version, j["date"] as? String ?: "", j["notes"] as? String ?: "", (j["base"] as String).trimEnd('/'), jars, need).also { state = Available(it) }
                }
            }
        } catch (e: Exception) {
            state = Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}. Check the internet connection.")
            null
        }
    }

    /** Downloads the jars that changed, checks each, backs up the records, then leaves it for the launcher to install on the next start. */
    @Synchronized
    fun download(r: Release) {
        val dir = upDir.apply { mkdirs() }
        val staged = File(dir, "lib").apply { mkdirs() }
        if (!staged.canWrite()) { state = Failed("Can't write to ${appDir}. Move the Workshop Manager folder somewhere you can change (for example C:\\Workshop Manager)."); return }
        try {
            var done = 0L
            state = Downloading(r, 0)
            for (jar in r.need) {
                val dest = File(staged, jar.name)
                if (dest.exists() && dest.length() == jar.size && sha256(dest) == jar.sha256) { done += jar.size; continue }  // from an interrupted try
                val part = File(staged, jar.name + ".part")
                val res = http.send(HttpRequest.newBuilder(URI("${r.base}/${jar.name}")).timeout(Duration.ofMinutes(20)).build(), HttpResponse.BodyHandlers.ofInputStream())
                if (res.statusCode() != 200) { res.body().close(); error("download of ${jar.name} failed (${res.statusCode()})") }
                val md = MessageDigest.getInstance("SHA-256")
                res.body().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastShown = done
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (done - lastShown > 256 * 1024) { lastShown = done; state = Downloading(r, done) }
                        }
                    }
                }
                if (part.length() != jar.size || HexFormat.of().formatHex(md.digest()) != jar.sha256) { part.delete(); error("a downloaded file was damaged; it will be downloaded again later") }
                dest.delete(); if (!part.renameTo(dest)) error("couldn't save ${jar.name}")
            }
            // Records first: a backup made by this version, before anything changes
            if (Db.isOpen) Backup.run()
            File(dir, "classpath.txt").writeText(r.jars.joinToString("\n", postfix = "\n") { it.name })
            File(dir, "notes.txt").writeText(r.notes)
            File(dir, "ready").writeText(r.version)
            log("downloaded ${r.version} (${r.need.size} of ${r.jars.size} files changed); installs at next start")
            state = Ready(r.version, r.notes)
        } catch (e: Exception) {
            log("download of ${r.version} failed: ${e.message}")
            state = Failed("Update ${r.version} wasn't installed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return HexFormat.of().formatHex(md.digest())
    }

    fun readyNotes(): String = File(upDir, "notes.txt").takeIf { it.exists() }?.readText() ?: ""

    /** Closes the app and starts it again through the launcher, which installs the waiting update. */
    fun restartNow(beforeExit: () -> Unit) {
        val launcher = System.getProperty("workshop.launcher") ?: return
        val javaCmd = ProcessHandle.current().info().command().orElse(File(System.getProperty("java.home"), "bin/java").path)
        val jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
        val cmd = listOf(javaCmd) + jvmArgs + listOf("-jar", launcher, "--wait", ProcessHandle.current().pid().toString())
        ProcessBuilder(cmd).directory(appDir?.parentFile).inheritIO().start()
        beforeExit()
        kotlin.system.exitProcess(0)
    }

    // ---------- helpers ----------

    /** The signature covers the version, the OS and every jar's name, size and SHA-256 (see packaging/UpdateKeys.java). */
    fun verify(version: String, os: String, jars: List<Jar>, sigB64: String): Boolean = try {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(AppInfo.updateKey)))
        val msg = "WorkshopManager\n$version\n$os\n" + jars.joinToString("") { "${it.name} ${it.size} ${it.sha256}\n" }
        Signature.getInstance("Ed25519").run {
            initVerify(key); update(msg.toByteArray()); verify(Base64.getDecoder().decode(sigB64))
        }
    } catch (e: Exception) { false }

    private fun failedVersions(): Set<String> = File(upDir, "failed").takeIf { it.exists() }?.readLines()?.map { it.trim() }?.toSet() ?: emptySet()

    /** Compares dotted version numbers: 1.10.0 > 1.9.2. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (c != 0) return c }
        return 0
    }

    private fun now() = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')

    private fun log(line: String) {
        try { if (appDir != null) File(upDir.apply { mkdirs() }, "update.log").appendText("${now()}  $line\n") } catch (_: Exception) {}
    }
}

/** Just enough JSON to read latest.json. */
internal object Json {
    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); expect(':'); m[k] = value(); ws(); if (s[i] == ',') i++ else { expect('}'); return m } } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i] == ',') i++ else { expect(']'); return l } } }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    val t = s.substring(st, i); if (t.isEmpty()) error("bad JSON at $st ('$c')"); t.toLongOrNull() ?: t.toDouble() }
            }
        }
        fun expect(c: Char) { if (s[i] != c) error("bad JSON at $i: expected $c"); i++ }
        fun str(): String {
            expect('"'); val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
