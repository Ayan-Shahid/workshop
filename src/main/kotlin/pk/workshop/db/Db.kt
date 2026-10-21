package pk.workshop.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/** A result row read by column name. */
class Row(private val m: Map<String, Any?>) {
    fun str(k: String): String = m[k]?.toString() ?: ""
    fun strOrNull(k: String): String? = m[k]?.toString()
    fun long(k: String): Long = when (val v = m[k]) { null -> 0L; is Number -> v.toLong(); else -> v.toString().toLongOrNull() ?: 0L }
    fun longOrNull(k: String): Long? = when (val v = m[k]) { null -> null; is Number -> v.toLong(); else -> v.toString().toLongOrNull() }
    fun int(k: String): Int = long(k).toInt()
    fun bool(k: String): Boolean = long(k) != 0L
}

/**
 * Single encrypted SQLite connection (SQLite3 Multiple Ciphers, ChaCha20).
 * All access is synchronized: the app runs on one PC with one user.
 */
object Db {
    private var conn: Connection? = null
    val lock = Any()
    lateinit var file: File
        private set

    val isOpen get() = conn != null

    fun open(dbFile: File, keyHex: String) {
        close()
        Class.forName("org.sqlite.JDBC")
        file = dbFile
        val url = "jdbc:sqlite:file:${dbFile.absolutePath}?cipher=chacha20&key=$keyHex"
        val c = DriverManager.getConnection(url)
        c.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("PRAGMA synchronous = FULL")
        }
        // Touch the database so a wrong key fails here, not later.
        c.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_master").close() }
        conn = c
        Schema.migrate()
    }

    fun close() {
        synchronized(lock) {
            try { conn?.createStatement()?.use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
            conn = null
        }
    }

    fun checkpoint() = synchronized(lock) { c().createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }; Unit }

    private fun c(): Connection = conn ?: error("Database is not open")

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setNull(idx, Types.NULL)
                is Long -> ps.setLong(idx, a)
                is Int -> ps.setLong(idx, a.toLong())
                is Boolean -> ps.setLong(idx, if (a) 1 else 0)
                is Double -> ps.setDouble(idx, a)
                else -> ps.setString(idx, a.toString())
            }
        }
    }

    private fun read(rs: ResultSet): List<Row> {
        val md = rs.metaData
        val out = ArrayList<Row>()
        while (rs.next()) {
            val m = HashMap<String, Any?>()
            for (i in 1..md.columnCount) m[md.getColumnLabel(i)] = rs.getObject(i)
            out.add(Row(m))
        }
        return out
    }

    fun query(sql: String, vararg args: Any?): List<Row> = synchronized(lock) {
        c().prepareStatement(sql).use { ps -> bind(ps, args); ps.executeQuery().use { read(it) } }
    }

    fun one(sql: String, vararg args: Any?): Row? = query(sql, *args).firstOrNull()

    fun scalarLong(sql: String, vararg args: Any?): Long = synchronized(lock) {
        c().prepareStatement(sql).use { ps -> bind(ps, args); ps.executeQuery().use { if (it.next()) it.getLong(1) else 0L } }
    }

    fun scalarStr(sql: String, vararg args: Any?): String? = synchronized(lock) {
        c().prepareStatement(sql).use { ps -> bind(ps, args); ps.executeQuery().use { if (it.next()) it.getString(1) else null } }
    }

    fun exec(sql: String, vararg args: Any?): Int = synchronized(lock) {
        c().prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }
    }

    fun insert(sql: String, vararg args: Any?): Long = synchronized(lock) {
        c().prepareStatement(sql).use { ps -> bind(ps, args); ps.executeUpdate() }
        c().createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    private var depth = 0

    /** Runs [block] in one transaction; nested calls join the outer transaction. */
    fun <T> tx(block: () -> T): T = synchronized(lock) {
        val c = c()
        if (depth > 0) { depth++; try { return block() } finally { depth-- } }
        depth = 1
        c.autoCommit = false
        try {
            val r = block()
            c.commit()
            r
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
            depth = 0
        }
    }

    fun script(sql: String) = synchronized(lock) {
        c().createStatement().use { st ->
            sql.split(";\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
        }
    }
}

/** A user-facing rule violation (shown as a message, not a crash). */
class AppError(message: String) : Exception(message)

fun fail(msg: String): Nothing = throw AppError(msg)
