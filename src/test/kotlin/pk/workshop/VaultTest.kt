package pk.workshop

import pk.workshop.db.Db
import pk.workshop.security.Vault
import java.io.File
import kotlin.test.*

class VaultTest {
    @Test fun encryptedDatabaseOpensOnlyWithPassword() {
        val dir = kotlin.io.path.createTempDirectory("ws").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        val code = Vault.setup("admin", "secret123")
        assertEquals(1L, Db.scalarLong("SELECT count(*) FROM accounts WHERE sys='CASH'"))
        Db.close()
        val bytes = File(dir, "workshop.db").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("CREATE TABLE"), "database must be encrypted")
        assertFailsWith<Exception> { Vault.login("admin", "wrong-pass") }
        Vault.login("admin", "secret123"); Db.close()
        Vault.resetPassword(code.lowercase(), "newpass99"); Db.close()
        Vault.login("ADMIN", "newpass99")
        assertTrue(Db.scalarLong("SELECT count(*) FROM accounts") > 20)
        Db.close()
    }
}
