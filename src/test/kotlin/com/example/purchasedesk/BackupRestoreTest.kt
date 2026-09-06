package com.example.purchasedesk

import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.RequestInput
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.User
import com.example.purchasedesk.service.Service
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mirrors the `backup` CLI command (runBackup) using the same sqlite-jdbc
 * online-backup call, then proves the output file is a standalone, bootable
 * database with identical data.
 */
class BackupRestoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File("/tmp", "purchasedesk-backup-${System.nanoTime()}")
        dir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** Same body as Main.kt runBackup. */
    private fun doBackup(dbPath: String, output: String) {
        val srcConn = java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath")
        try {
            srcConn.createStatement().use { it.execute("PRAGMA busy_timeout=10000;") }
            @Suppress("UNCHECKED_CAST")
            val src = unwrapDb(srcConn)
            val progress = object : org.sqlite.core.DB.ProgressObserver {
                override fun progress(remaining: Int, total: Int) {}
            }
            val remaining = src.backup("main", output, progress)
            assertEquals(0, remaining, "backup did not complete")
        } finally {
            srcConn.close()
        }
        consolidate(File(output))
    }

    @Suppress("UNCHECKED_CAST")
    private fun unwrapDb(conn: java.sql.Connection): org.sqlite.core.DB {
        var cur: Class<*>? = conn.javaClass
        while (cur != null && cur != Any::class.java) {
            val f = cur.declaredFields.firstOrNull { it.name == "db" }
            if (f != null) {
                try {
                    f.isAccessible = true
                } catch (_: Exception) {
                }
                return f.get(conn) as org.sqlite.core.DB
            }
            cur = cur.superclass
        }
        throw IllegalStateException("cannot unwrap ${conn.javaClass.name}")
    }

    private fun consolidate(path: File) {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
        try {
            conn.createStatement().use { it.execute("VACUUM;") }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `backup produces a standalone db with identical data`() {
        val dbPath = File(dir, "live.db").absolutePath
        val outPath = File(dir, "backup.db").absolutePath
        val live = Database(dbPath)
        live.createUser("alice", Role.EMPLOYEE, "it:salt:hash")
        live.createUser("boss", Role.APPROVER, "it:salt:hash")
        val service = Service(live)
        val alice = User("alice", Role.EMPLOYEE)
        val boss = User("boss", Role.APPROVER)
        val req = service.create(alice, RequestInput("プリンタ用紙", 5, 1200, "消耗品"))
        service.submit(alice, req.id)
        service.approve(boss, req.id)
        live.close()

        doBackup(dbPath, outPath)

        // The output exists and is a real SQLite file.
        assertTrue(File(outPath).exists())
        assertTrue(File(outPath).length() > 0)

        // Re-open the backup as a fresh DB and verify the data round-trips.
        val restored = Database(outPath)
        val r = restored.getRequest(req.id)
        assertNotNull(r)
        assertEquals("プリンタ用紙", r.itemName)
        assertEquals(5L, r.quantity)
        assertEquals(1200L, r.unitPriceYen)
        assertEquals(6000L, r.totalYen)
        assertEquals(State.APPROVED, r.state)
        val actions = r.history.map { it.action.name }
        assertEquals(listOf("CREATE", "SUBMIT", "APPROVE"), actions)
        assertEquals(Role.EMPLOYEE, restored.user("alice")?.role)
        assertEquals(Role.APPROVER, restored.user("boss")?.role)
        restored.close()
    }

    @Test
    fun `backup is byte independent of wal file`() {
        val dbPath = File(dir, "live2.db").absolutePath
        val outPath = File(dir, "backup2.db").absolutePath
        val live = Database(dbPath)
        live.createUser("alice", Role.EMPLOYEE, "it:salt:hash")
        live.close()
        doBackup(dbPath, outPath)
        // VACUUM consolidates; the standalone file must open on its own.
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$outPath")
        try {
            conn.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM users").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        } finally {
            conn.close()
        }
    }
}
