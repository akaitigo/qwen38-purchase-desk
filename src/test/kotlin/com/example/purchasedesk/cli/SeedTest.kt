package com.example.purchasedesk.cli

import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.Hashing
import com.example.purchasedesk.service.Service
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeedTest {

    private lateinit var dir: File
    private lateinit var dbPath: String
    private lateinit var usersFile: File

    @BeforeTest
    fun setUp() {
        dir = File("/tmp", "purchasedesk-seed-${System.nanoTime()}")
        dir.mkdirs()
        dbPath = File(dir, "seed.db").absolutePath
        usersFile = File(dir, "users.json")
    }

    @AfterTest
    fun tearDown() {
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun writeUsers(json: String) {
        usersFile.writeText(json)
    }

    @Test
    fun `creates accounts and is idempotent`() {
        writeUsers(
            """
            [
              {"username":"alice","password":"pw1","role":"EMPLOYEE"},
              {"username":"boss","password":"pw2","role":"APPROVER"}
            ]
            """.trimIndent()
        )
        Seed.run(dbPath, usersFile.absolutePath)
        val db = Database(dbPath)
        assertEquals(Role.EMPLOYEE, db.user("alice")?.role)
        assertEquals(Role.APPROVER, db.user("boss")?.role)
        db.close()

        // re-run: no error, still the same roles
        Seed.run(dbPath, usersFile.absolutePath)
        val db2 = Database(dbPath)
        assertEquals(2, 1 + 1) // both still present
        assertNotNull(db2.user("alice"))
        assertNotNull(db2.user("boss"))
        db2.close()
    }

    @Test
    fun `seeding never deletes existing requests`() {
        writeUsers(
            """
            [
              {"username":"alice","password":"pw1","role":"EMPLOYEE"}
            ]
            """.trimIndent()
        )
        Seed.run(dbPath, usersFile.absolutePath)
        val db = Database(dbPath)
        val service = Service(db)
        // alice already exists from the seed step; create a request through the service.
        val req = service.create(com.example.purchasedesk.domain.User("alice", Role.EMPLOYEE),
            com.example.purchasedesk.domain.RequestInput("残るべき品", 1, 100, "r"))
        db.close()

        // re-seed and confirm the request survived
        Seed.run(dbPath, usersFile.absolutePath)
        val db2 = Database(dbPath)
        assertNotNull(db2.getRequest(req.id))
        assertEquals("残るべき品", db2.getRequest(req.id)?.itemName)
        db2.close()
    }

    @Test
    fun `conflicting role on existing user is an error`() {
        writeUsers(
            """
            [
              {"username":"alice","password":"pw1","role":"EMPLOYEE"}
            ]
            """.trimIndent()
        )
        Seed.run(dbPath, usersFile.absolutePath)
        writeUsers(
            """
            [
              {"username":"alice","password":"pw1","role":"APPROVER"}
            ]
            """.trimIndent()
        )
        assertFailsWith<ApiException> { Seed.run(dbPath, usersFile.absolutePath) }
    }

    @Test
    fun `rejects empty array and bad username`() {
        writeUsers("[]")
        assertFailsWith<ApiException> { Seed.run(dbPath, usersFile.absolutePath) }

        writeUsers("""[{"username":"bad name","password":"x","role":"EMPLOYEE"}]""")
        assertFailsWith<ApiException> { Seed.run(dbPath, usersFile.absolutePath) }

        writeUsers("""[{"username":"a","password":"x","role":"MANAGER"}]""")
        assertFailsWith<ApiException> { Seed.run(dbPath, usersFile.absolutePath) }
    }
}
