package pk.workshop.security

import pk.workshop.db.AppError
import pk.workshop.db.Db
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Single-admin login.
 *
 * A random 256-bit database key encrypts the whole database. That key is stored only in wrapped form:
 * once under the admin password and once under the recovery code (AES-256-GCM, key from PBKDF2-SHA256).
 * A correct password unwraps the key and opens the database; a wrong one fails the GCM check.
 * Neither the password nor the recovery code is stored anywhere.
 */
object Vault {
    private const val ITER = 310_000
    private val rnd = SecureRandom()

    val dataDir: File get() {
        val home = System.getProperty("user.home")
        val base = System.getenv("APPDATA")?.let { File(it) }
            ?: if (System.getProperty("os.name").lowercase().contains("mac")) File(home, "Library/Application Support") else File(home, ".local/share")
        return File(System.getProperty("workshop.dataDir") ?: File(base, "WorkshopManager").path).apply { mkdirs() }
    }
    val dbFile get() = File(dataDir, "workshop.db")
    private val vaultFile get() = File(dataDir, "vault.properties")

    fun isSetUp(): Boolean = vaultFile.exists() && dbFile.exists()

    fun username(): String = load().getProperty("username", "admin")

    /** Creates the admin and the encrypted database. Returns the one-time recovery code. */
    fun setup(username: String, password: String): String {
        checkPassword(password)
        if (username.isBlank()) throw AppError("Enter a username.")
        if (isSetUp()) throw AppError("The app is already set up.")
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val recovery = newRecoveryCode()
        val p = Properties()
        p["username"] = username.trim()
        p["iter"] = ITER.toString()
        wrapInto(p, "pw", password, key)
        wrapInto(p, "rc", normalize(recovery), key)
        dbFile.delete()
        Db.open(dbFile, hex(key))
        save(p)
        return recovery
    }

    fun login(username: String, password: String) {
        val p = load()
        if (!username.trim().equals(p.getProperty("username"), ignoreCase = true)) throw AppError("Wrong username or password.")
        val key = unwrap(p, "pw", password) ?: throw AppError("Wrong username or password.")
        Db.open(dbFile, hex(key))
    }

    fun resetPassword(recoveryCode: String, newPassword: String) {
        checkPassword(newPassword)
        val p = load()
        val key = unwrap(p, "rc", normalize(recoveryCode)) ?: throw AppError("That recovery code is not correct.")
        wrapInto(p, "pw", newPassword, key)
        save(p)
        Db.open(dbFile, hex(key))
    }

    fun changePassword(current: String, newPassword: String) {
        checkPassword(newPassword)
        val p = load()
        val key = unwrap(p, "pw", current) ?: throw AppError("Current password is not correct.")
        wrapInto(p, "pw", newPassword, key)
        save(p)
    }

    /** Issues a fresh recovery code (the old one stops working). Needs the current password. */
    fun newRecovery(current: String): String {
        val p = load()
        val key = unwrap(p, "pw", current) ?: throw AppError("Current password is not correct.")
        val code = newRecoveryCode()
        wrapInto(p, "rc", normalize(code), key)
        save(p)
        return code
    }

    /** The vault file must travel with backups so a restored database can be opened. */
    val vaultFileForBackup: File get() = vaultFile

    private fun checkPassword(pw: String) {
        if (pw.length < 6) throw AppError("Use a password of at least 6 characters.")
    }

    private fun newRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..5).joinToString("-") { (1..4).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("") }
    }

    private fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    private fun derive(secret: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, iter, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun wrapInto(p: Properties, prefix: String, secret: String, key: ByteArray) {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(secret, salt, ITER), GCMParameterSpec(128, iv))
        val b64 = Base64.getEncoder()
        p["${prefix}_salt"] = b64.encodeToString(salt)
        p["${prefix}_iv"] = b64.encodeToString(iv)
        p["${prefix}_key"] = b64.encodeToString(c.doFinal(key))
    }

    private fun unwrap(p: Properties, prefix: String, secret: String): ByteArray? = try {
        val d = Base64.getDecoder()
        val iter = p.getProperty("iter", ITER.toString()).toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(secret, d.decode(p.getProperty("${prefix}_salt")), iter),
            GCMParameterSpec(128, d.decode(p.getProperty("${prefix}_iv"))))
        c.doFinal(d.decode(p.getProperty("${prefix}_key")))
    } catch (e: Exception) { null }

    private fun load(): Properties = Properties().apply {
        if (!vaultFile.exists()) throw AppError("The app has not been set up yet.")
        vaultFile.inputStream().use { load(it) }
    }

    private fun save(p: Properties) {
        val tmp = File(dataDir, "vault.properties.tmp")
        tmp.outputStream().use { p.store(it, "Workshop Manager key vault - keep with backups") }
        if (!tmp.renameTo(vaultFile)) { vaultFile.delete(); tmp.renameTo(vaultFile) }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
