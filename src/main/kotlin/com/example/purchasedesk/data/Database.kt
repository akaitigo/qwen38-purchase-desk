package com.example.purchasedesk.data

import com.example.purchasedesk.domain.Action
import com.example.purchasedesk.domain.HistoryEntry
import com.example.purchasedesk.domain.PurchaseRequest
import com.example.purchasedesk.domain.RequestInput
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.User
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.concurrent.locks.ReentrantLock

/**
 * SQLite storage layer. A single connection is shared and guarded by a lock
 * so that every state transition runs atomically under BEGIN IMMEDIATE.
 */
class Database(private val path: String) : AutoCloseable {

    private val lock = ReentrantLock()
    private val conn: Connection

    init {
        // Make sure the parent directory exists (e.g. "/tmp/somedir/app.db").
        File(path).absoluteFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        // Allow multiple JVMs to talk to the same file safely.
        DriverManager.setLoginTimeout(10)
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL;")
            it.execute("PRAGMA busy_timeout=10000;")
            it.execute("PRAGMA foreign_keys=ON;")
        }
        migrate()
    }

    private fun migrate() {
        lock.lock()
        try {
            conn.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        username   TEXT PRIMARY KEY,
                        role       TEXT NOT NULL,
                        pw_hash    TEXT NOT NULL
                    );
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS requests (
                        id             TEXT PRIMARY KEY,
                        owner_username TEXT NOT NULL REFERENCES users(username),
                        item_name      TEXT NOT NULL,
                        quantity       INTEGER NOT NULL,
                        unit_price_yen INTEGER NOT NULL,
                        total_yen      INTEGER NOT NULL,
                        reason         TEXT NOT NULL,
                        state          TEXT NOT NULL
                    );
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS history (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        request_id  TEXT NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
                        action      TEXT NOT NULL,
                        actor_username TEXT NOT NULL,
                        at          TEXT NOT NULL,
                        comment     TEXT NOT NULL DEFAULT ''
                    );
                    """.trimIndent()
                )
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        token      TEXT PRIMARY KEY,
                        csrf_token TEXT NOT NULL,
                        username   TEXT NOT NULL REFERENCES users(username),
                        created_at TEXT NOT NULL
                    );
                    """.trimIndent()
                )
                // Anonymous sessions: CSRF tokens bound to a cookie so that
                // /api/login cannot be driven by a forged or unbound token.
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS anonymous_sessions (
                        token      TEXT PRIMARY KEY,
                        csrf_token TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    );
                    """.trimIndent()
                )
            }
        } finally {
            lock.unlock()
        }
    }

    // ---- users ----

    fun createUser(username: String, role: Role, pwHash: String) {
        lock.lock()
        try {
            conn.prepareStatement("INSERT INTO users(username, role, pw_hash) VALUES(?,?,?)")
                .use {
                    it.setString(1, username)
                    it.setString(2, role.name)
                    it.setString(3, pwHash)
                    it.executeUpdate()
                }
        } finally {
            lock.unlock()
        }
    }

    fun user(username: String): User? {
        var result: User? = null
        lock.lock()
        try {
            conn.prepareStatement("SELECT username, role FROM users WHERE username=?")
                .use {
                    it.setString(1, username)
                    it.executeQuery().use { rs ->
                        if (rs.next()) {
                            result = User(rs.getString(1), Role.valueOf(rs.getString(2)))
                        }
                    }
                }
        } finally {
            lock.unlock()
        }
        return result
    }

    fun userWithHash(username: String): Pair<String, String>? {
        var result: Pair<String, String>? = null
        lock.lock()
        try {
            conn.prepareStatement("SELECT username, pw_hash FROM users WHERE username=?")
                .use {
                    it.setString(1, username)
                    it.executeQuery().use { rs ->
                        if (rs.next()) {
                            result = rs.getString(1) to rs.getString(2)
                        }
                    }
                }
        } finally {
            lock.unlock()
        }
        return result
    }

    // ---- sessions ----

    fun createSession(token: String, csrfToken: String, username: String, createdAt: String) {
        lock.lock()
        try {
            conn.prepareStatement("INSERT INTO sessions(token, csrf_token, username, created_at) VALUES(?,?,?,?)")
                .use {
                    it.setString(1, token)
                    it.setString(2, csrfToken)
                    it.setString(3, username)
                    it.setString(4, createdAt)
                    it.executeUpdate()
                }
        } finally {
            lock.unlock()
        }
    }

    fun session(token: String): Triple<String, String, String>? {
        // Returns (csrf, username, createdAt) or null.
        var result: Triple<String, String, String>? = null
        lock.lock()
        try {
            conn.prepareStatement("SELECT csrf_token, username, created_at FROM sessions WHERE token=?")
                .use {
                    it.setString(1, token)
                    it.executeQuery().use { rs ->
                        if (rs.next()) {
                            result = Triple(rs.getString(1), rs.getString(2), rs.getString(3))
                        }
                    }
                }
        } finally {
            lock.unlock()
        }
        return result
    }

    fun deleteSession(token: String) {
        lock.lock()
        try {
            conn.prepareStatement("DELETE FROM sessions WHERE token=?")
                .use {
                    it.setString(1, token)
                    it.executeUpdate()
                }
        } finally {
            lock.unlock()
        }
    }

    // ---- anonymous sessions ----

    /** Returns (token, csrf) of the anonymous session, or null. */
    fun anonymousSession(cookieToken: String): Pair<String, String>? {
        var result: Pair<String, String>? = null
        lock.lock()
        try {
            conn.prepareStatement("SELECT token, csrf_token FROM anonymous_sessions WHERE token=?")
                .use {
                    it.setString(1, cookieToken)
                    it.executeQuery().use { rs ->
                        if (rs.next()) result = rs.getString(1) to rs.getString(2)
                    }
                }
        } finally {
            lock.unlock()
        }
        return result
    }

    fun createAnonymousSession(token: String, csrfToken: String, createdAt: String) {
        lock.lock()
        try {
            conn.prepareStatement("INSERT INTO anonymous_sessions(token, csrf_token, created_at) VALUES(?,?,?)")
                .use {
                    it.setString(1, token)
                    it.setString(2, csrfToken)
                    it.setString(3, createdAt)
                    it.executeUpdate()
                }
        } finally {
            lock.unlock()
        }
    }

    /** Atomically deletes the anonymous session and returns its CSRF token. */
    fun consumeAnonymousSession(token: String): String? {
        var csrf: String? = null
        lock.lock()
        try {
            beginImmediate()
            try {
                conn.prepareStatement("SELECT csrf_token FROM anonymous_sessions WHERE token=?")
                    .use {
                        it.setString(1, token)
                        it.executeQuery().use { rs ->
                            if (rs.next()) csrf = rs.getString(1)
                        }
                    }
                if (csrf != null) {
                    conn.prepareStatement("DELETE FROM anonymous_sessions WHERE token=?")
                        .use {
                            it.setString(1, token)
                            it.executeUpdate()
                        }
                }
                commit()
            } catch (e: Exception) {
                rollback()
                throw e
            }
        } finally {
            lock.unlock()
        }
        return csrf
    }

    // ---- requests ----

    /** Creates a DRAFT request and writes its CREATE history entry. */
    fun createRequest(
        id: String,
        owner: String,
        input: RequestInput,
        at: String
    ) {
        lock.lock()
        try {
            beginImmediate()
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO requests(id, owner_username, item_name, quantity, unit_price_yen, total_yen, reason, state)
                    VALUES(?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use {
                    it.setString(1, id)
                    it.setString(2, owner)
                    it.setString(3, input.itemName)
                    it.setLong(4, input.quantity)
                    it.setLong(5, input.unitPriceYen)
                    it.setLong(6, input.quantity * input.unitPriceYen)
                    it.setString(7, input.reason)
                    it.setString(8, State.DRAFT.name)
                    it.executeUpdate()
                }
                insertHistory(id, Action.CREATE, owner, at, "")
                commit()
            } catch (e: Exception) {
                rollback()
                throw e
            }
        } finally {
            lock.unlock()
        }
    }

    /** Loads a request by ID (or null). */
    fun getRequest(id: String): PurchaseRequest? {
        lock.lock()
        try {
            conn.prepareStatement(
                "SELECT * FROM requests WHERE id=?"
            ).use {
                it.setString(1, id)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        val state = State.valueOf(rs.getString("state"))
                        val history = loadHistory(id)
                        return PurchaseRequest(
                            rs.getString("id"),
                            rs.getString("owner_username"),
                            rs.getString("item_name"),
                            rs.getLong("quantity"),
                            rs.getLong("unit_price_yen"),
                            rs.getLong("total_yen"),
                            rs.getString("reason"),
                            state,
                            history
                        )
                    }
                }
            }
        } finally {
            lock.unlock()
        }
        return null
    }

    /** Lists requests the caller is allowed to see, newest-first. */
    fun listRequests(user: User): List<PurchaseRequest> {
        lock.lock()
        try {
            val sql = if (user.role == Role.APPROVER) {
                "SELECT id FROM requests ORDER BY id DESC"
            } else {
                "SELECT id FROM requests WHERE owner_username=? ORDER BY id DESC"
            }
            val ids = mutableListOf<String>()
            conn.prepareStatement(sql).use {
                if (user.role == Role.EMPLOYEE) it.setString(1, user.username)
                it.executeQuery().use { rs -> while (rs.next()) ids.add(rs.getString(1)) }
            }
            return ids.mapNotNull { getRequest(it) }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Updates DRAFT or RETURNED request fields, writes an UPDATE history entry.
     * Returns the fresh PurchaseRequest.
     */
    fun updateRequest(
        id: String,
        actor: User,
        input: RequestInput,
        at: String
    ): PurchaseRequest {
        lock.lock()
        try {
            beginImmediate()
            try {
                val current = loadRequestLocked(id)
                    ?: throw IllegalStateException("missing")
                if (current.state != State.DRAFT && current.state != State.RETURNED) {
                    throw IllegalStateException("state")
                }
                if (current.ownerUsername != actor.username) throw IllegalStateException("owner")

                conn.prepareStatement(
                    """
                    UPDATE requests SET item_name=?, quantity=?, unit_price_yen=?, total_yen=?, reason=?
                    WHERE id=?
                    """.trimIndent()
                ).use {
                    it.setString(1, input.itemName)
                    it.setLong(2, input.quantity)
                    it.setLong(3, input.unitPriceYen)
                    it.setLong(4, input.quantity * input.unitPriceYen)
                    it.setString(5, input.reason)
                    it.setString(6, id)
                    it.executeUpdate()
                }
                insertHistory(id, Action.UPDATE, actor.username, at, "")
                commit()
                return loadRequestLocked(id)!!
            } catch (e: Exception) {
                rollback()
                throw e
            }
        } finally {
            lock.unlock()
        }
    }

    /** Transitions DRAFT or RETURNED → SUBMITTED, writes SUBMIT history. */
    fun submitRequest(id: String, actor: User, at: String): PurchaseRequest? {
        return transitionLocked(
            id = id,
            actor = actor,
            from = setOf(State.DRAFT, State.RETURNED),
            to = State.SUBMITTED,
            action = Action.SUBMIT,
            at = at,
            comment = ""
        )
    }

    /** Transitions SUBMITTED → RETURNED, writes RETURN history with the comment. */
    fun returnRequest(id: String, actor: User, comment: String, at: String): PurchaseRequest? {
        return transitionLocked(
            id = id,
            actor = actor,
            from = setOf(State.SUBMITTED),
            to = State.RETURNED,
            action = Action.RETURN,
            at = at,
            comment = comment
        )
    }

    /**
     * Transitions SUBMITTED → APPROVED, writes APPROVE history.
     * A second call (state no longer SUBMITTED) returns null so callers
     * can map it to 409 without double-recording.
     */
    fun approveRequest(id: String, actor: User, at: String): PurchaseRequest? {
        return transitionLocked(
            id = id,
            actor = actor,
            from = setOf(State.SUBMITTED),
            to = State.APPROVED,
            action = Action.APPROVE,
            at = at,
            comment = ""
        )
    }

    private fun transitionLocked(
        id: String,
        actor: User,
        from: Set<State>,
        to: State,
        action: Action,
        at: String,
        comment: String
    ): PurchaseRequest? {
        lock.lock()
        try {
            beginImmediate()
            try {
                val current = loadRequestLocked(id)
                if (current == null) {
                    rollback()
                    return null
                }
                if (!from.contains(current.state)) {
                    rollback()
                    return null
                }
                conn.prepareStatement("UPDATE requests SET state=? WHERE id=?")
                    .use {
                        it.setString(1, to.name)
                        it.setString(2, id)
                        it.executeUpdate()
                    }
                insertHistory(id, action, actor.username, at, comment)
                commit()
                return loadRequestLocked(id)
            } catch (e: Exception) {
                rollback()
                throw e
            }
        } finally {
            lock.unlock()
        }
    }

    private fun loadRequestLocked(id: String): PurchaseRequest? {
        conn.prepareStatement("SELECT * FROM requests WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs ->
                if (rs.next()) {
                    return PurchaseRequest(
                        rs.getString("id"),
                        rs.getString("owner_username"),
                        rs.getString("item_name"),
                        rs.getLong("quantity"),
                        rs.getLong("unit_price_yen"),
                        rs.getLong("total_yen"),
                        rs.getString("reason"),
                        State.valueOf(rs.getString("state")),
                        loadHistory(id)
                    )
                }
            }
        }
        return null
    }

    private fun loadHistory(id: String): List<HistoryEntry> {
        conn.prepareStatement(
            "SELECT action, actor_username, at, comment FROM history WHERE request_id=? ORDER BY id ASC"
        ).use {
            it.setString(1, id)
            it.executeQuery().use { rs ->
                val list = mutableListOf<HistoryEntry>()
                while (rs.next()) {
                    list.add(
                        HistoryEntry(
                            Action.valueOf(rs.getString("action")),
                            rs.getString("actor_username"),
                            rs.getString("at"),
                            rs.getString("comment")
                        )
                    )
                }
                return list
            }
        }
    }

    private fun insertHistory(
        id: String,
        action: Action,
        actor: String,
        at: String,
        comment: String
    ) {
        conn.prepareStatement(
            "INSERT INTO history(request_id, action, actor_username, at, comment) VALUES(?,?,?,?,?)"
        ).use {
            it.setString(1, id)
            it.setString(2, action.name)
            it.setString(3, actor)
            it.setString(4, at)
            it.setString(5, comment)
            it.executeUpdate()
        }
    }

    // Explicit transaction control via SQL (works regardless of the JDBC
    // driver's autoCommit bookkeeping). The whole DB runs behind `lock`.
    private fun beginImmediate() {
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
    }

    private fun commit() {
        conn.createStatement().use { it.execute("COMMIT") }
    }

    private fun rollback() {
        try {
            conn.createStatement().use { it.execute("ROLLBACK") }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        lock.lock()
        try {
            try { conn.close() } catch (_: Exception) { }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** UUIDv7-style: 48-bit ms timestamp + random, so ordering is natural. */
        fun newId(): String {
            val t = System.currentTimeMillis()
            val sb = StringBuilder()
            fun hex(v: Long) { sb.append(java.lang.Long.toHexString(v)) }
            // 48-bit ms timestamp, big-endian
            hex((t ushr 32) and 0xffff)
            hex((t ushr 16) and 0xffff)
            hex(t and 0xffff)
            sb.append("7")
            hex(java.security.SecureRandom().nextLong() and 0xffffL)
            sb.append("8")
            hex(java.security.SecureRandom().nextLong() and 0xffffL)
            return sb.toString()
        }
    }
}
