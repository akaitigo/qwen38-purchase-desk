package com.example.purchasedesk

import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.Hashing
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.RequestInput
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.User
import com.example.purchasedesk.service.Service
import java.io.File
import kotlin.io.path.absolutePathString

/**
 * Small helpers shared across tests: a throwaway SQLite file, a seeded set of
 * users, and a convenience factory for valid request inputs.
 */
class TempDb : AutoCloseable {
    val dir: File
    val path: String
    val db: Database
    val service: Service

    init {
        dir = createTempDir()
        path = File(dir, "test.db").absolutePath
        db = Database(path)
        service = Service(db)
    }

    /** Creates the user if absent; returns the role. */
    fun add(username: String, role: Role, password: String = "pw-$username"): Role {
        if (db.user(username) == null) {
            db.createUser(username, role, Hashing.hashPassword(password))
        }
        return role
    }

    fun employee(name: String = "alice"): User = User(name, Role.EMPLOYEE)
    fun approver(name: String = "boss"): User = User(name, Role.APPROVER)

    fun sampleInput(itemName: String = "ボールペン", qty: Long = 2, price: Long = 100) =
        RequestInput(itemName, qty, price, "会議で使用")

    override fun close() {
        try {
            db.close()
        } catch (_: Exception) {
        }
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    companion object {
        private fun createTempDir(): File {
            val f = File("/tmp", "purchasedesk-test-${System.nanoTime()}")
            f.mkdirs()
            return f
        }
    }
}

/** Convenience: run [block] with a fresh [TempDb], always closing it. */
inline fun withDb(block: (TempDb) -> Unit) {
    val t = TempDb()
    try {
        block(t)
    } finally {
        t.close()
    }
}
