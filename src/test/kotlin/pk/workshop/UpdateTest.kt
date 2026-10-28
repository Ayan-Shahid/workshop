package pk.workshop

import pk.workshop.db.Db
import pk.workshop.db.Schema
import pk.workshop.io.Json
import pk.workshop.io.Updater
import pk.workshop.security.Vault
import kotlin.test.*

class UpdateTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(Updater.compare("1.10.0", "1.9.2") > 0)
        assertTrue(Updater.compare("v1.2", "1.2.0") == 0)
        assertTrue(Updater.compare("1.2.0", "1.2.1") < 0)
    }

    @Test fun readsTheReleaseFeed() {
        val j = Json.parse("""{"version":"1.2.0","notes":"Fixes \"x\"\nline 2 é","minJava":21,
            "platforms":{"windows":{"sig":"abc","files":[{"name":"a.jar","size":12,"sha256":"ff"}]}},"flag":true,"none":null}""") as Map<*, *>
        assertEquals("1.2.0", j["version"])
        assertEquals("Fixes \"x\"\nline 2 é", j["notes"])
        assertEquals(21L, j["minJava"])
        val files = ((j["platforms"] as Map<*, *>)["windows"] as Map<*, *>)["files"] as List<*>
        assertEquals(12L, (files[0] as Map<*, *>)["size"])
    }

    @Test fun refusesUnsignedOrAlteredUpdates() {
        val jars = listOf(Updater.Jar("workshop-1.2.0-abc.jar", 100, "00".repeat(32)))
        assertFalse(Updater.verify("1.2.0", "windows", jars, java.util.Base64.getEncoder().encodeToString(ByteArray(64))))
        assertFalse(Updater.verify("1.2.0", "windows", jars, "not base64"))
    }

    @Test fun refusesDataFromANewerVersion() {
        val dir = kotlin.io.path.createTempDirectory("ws").toFile()
        System.setProperty("workshop.dataDir", dir.path)
        Vault.setup("admin", "secret123")
        assertEquals(Schema.VERSION, Schema.current())
        Db.exec("UPDATE meta SET v=? WHERE k='schema'", (Schema.VERSION + 1).toString())
        Db.close()
        val e = assertFailsWith<Exception> { Vault.login("admin", "secret123") }
        assertTrue(e is Schema.NewerDataException || e.cause is Schema.NewerDataException, e.toString())
        assertFalse(Db.isOpen)
    }
}
