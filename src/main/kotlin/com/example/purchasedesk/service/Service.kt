package com.example.purchasedesk.service

import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.domain.Hashing
import com.example.purchasedesk.domain.PurchaseRequest
import com.example.purchasedesk.domain.RequestInput
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.User
import com.example.purchasedesk.domain.Validation

/**
 * Business-logic layer shared by the JSON API and the HTML handlers so both
 * enforce the exact same validation, permissions and state machine.
 */
class Service(private val db: Database) {

    private fun nowUtc(): String =
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    // ---------- auth ----------

    fun login(username: String, password: String): Pair<User, String> {
        val stored = db.userWithHash(username) ?: throw ApiException(401, "ユーザー名またはパスワードが違います。")
        if (!Hashing.verify(password, stored.second)) {
            throw ApiException(401, "ユーザー名またはパスワードが違います。")
        }
        val token = com.example.purchasedesk.domain.Tokens.randomToken()
        val csrf = com.example.purchasedesk.domain.Tokens.randomToken()
        db.createSession(token, csrf, stored.first, nowUtc())
        return User(stored.first, db.user(stored.first)!!.role) to token
    }

    fun logout(token: String) {
        db.deleteSession(token)
    }

    /** Resolves a session token to the user, or null when invalid/absent. */
    fun sessionUser(token: String): Pair<User, String>? {
        val s = db.session(token) ?: return null
        val u = db.user(s.second) ?: return null
        return Pair(u, s.first) // (user, csrfToken)
    }

    fun csrfFor(token: String): String? = db.session(token)?.first

    // ---------- queries ----------

    fun list(user: User): List<PurchaseRequest> = db.listRequests(user)

    fun get(user: User, id: String): PurchaseRequest {
        validateId(id)
        val req = db.getRequest(id) ?: notFound(id)
        if (!canView(user, req)) notFound(id)
        return req
    }

    private fun canView(user: User, req: PurchaseRequest): Boolean =
        user.role == Role.APPROVER || req.ownerUsername == user.username

    // ---------- mutations ----------

    fun create(user: User, input: RequestInput): PurchaseRequest {
        requireEmployee(user)
        val req = PurchaseRequest(
            id = Database.newId(),
            ownerUsername = user.username,
            itemName = input.itemName,
            quantity = input.quantity,
            unitPriceYen = input.unitPriceYen,
            totalYen = input.quantity * input.unitPriceYen,
            reason = input.reason,
            state = State.DRAFT,
            history = emptyList()
        )
        db.createRequest(req.id, user.username, input, nowUtc())
        return db.getRequest(req.id)!!
    }

    fun update(user: User, id: String, input: RequestInput): PurchaseRequest {
        requireEmployee(user)
        val current = db.getRequest(id) ?: notFound(id)
        if (!canView(user, current)) notFound(id)
        if (current.ownerUsername != user.username) {
            throw ApiException(403, "この申請を編集する権限がありません。")
        }
        // State checked inside DB (atomic); surface a readable 409 on conflict.
        try {
            return db.updateRequest(id, user, input, nowUtc())
        } catch (e: IllegalStateException) {
            throw stateConflict(current.state)
        }
    }

    fun submit(user: User, id: String): PurchaseRequest {
        requireEmployee(user)
        val current = db.getRequest(id) ?: notFound(id)
        if (current.ownerUsername != user.username) {
            throw ApiException(403, "この申請を提出する権限がありません。")
        }
        val result = db.submitRequest(id, user, nowUtc())
        if (result == null) throw stateConflict(current.state)
        return result
    }

    fun `return`(user: User, id: String, comment: String): PurchaseRequest {
        requireApprover(user)
        val current = db.getRequest(id) ?: notFound(id)
        val result = db.returnRequest(id, user, comment, nowUtc())
        if (result == null) throw stateConflict(current.state)
        return result
    }

    fun approve(user: User, id: String): PurchaseRequest {
        requireApprover(user)
        val current = db.getRequest(id) ?: notFound(id)
        val result = db.approveRequest(id, user, nowUtc())
        if (result == null) throw stateConflict(current.state)
        return result
    }

    // ---------- helpers ----------

    private fun requireEmployee(u: User) {
        if (u.role != Role.EMPLOYEE) {
            throw ApiException(403, "社員のみが申請を作成・編集・提出できます。")
        }
    }

    private fun requireApprover(u: User) {
        if (u.role != Role.APPROVER) {
            throw ApiException(403, "承認者のみがこの操作を行えます。")
        }
    }

    private fun stateConflict(state: State): ApiException {
        val msg = when (state) {
            State.APPROVED -> "承認済みの申請は変更できません。"
            State.SUBMITTED -> "この操作は現在の状態（提出済み）では行えません。"
            State.DRAFT -> "この操作は現在の状態（下書き）では行えません。"
            State.RETURNED -> "この操作は現在の状態（差し戻し済み）では行えません。"
        }
        return ApiException(409, msg)
    }

    private fun notFound(id: String): Nothing {
        throw ApiException(404, "申請が見つかりません。（ID: $id）")
    }

    private fun validateId(id: String) {
        if (id.isEmpty() || !ID_PATTERN.matches(id)) {
            throw ApiException(404, "申請が見つかりません。")
        }
    }

    companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    }
}
