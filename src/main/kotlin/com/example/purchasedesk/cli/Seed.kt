package com.example.purchasedesk.cli

import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.domain.Hashing
import com.example.purchasedesk.domain.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Seed {
    /**
     * Reads a JSON array of `{username, password, role}` and creates each
     * account. Existing accounts are left untouched; existing requests are
     * never deleted. Idempotent on re-run.
     */
    fun run(dbPath: String, usersFile: String) {
        val text = File(usersFile).readText()
        val arr = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw ApiException(400, "users-file はJSON配列である必要があります。")
        }
        if (arr.length() == 0) {
            throw ApiException(400, "users-file が空です。少なくとも1名を登録してください。")
        }
        val db = Database(dbPath)
        try {
            val created = mutableListOf<String>()
            val updated = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.getJSONObject(i)
                val username = o.optString("username").trim()
                val password = o.optString("password")
                val roleRaw = o.optString("role").trim().uppercase()
                if (username.isEmpty()) {
                    throw ApiException(400, "要素 $i: username が空です。")
                }
                if (!USERNAME_PATTERN.matches(username)) {
                    throw ApiException(400, "要素 $i: username は英数字・._@ のみ使用できます。")
                }
                if (password.isEmpty()) {
                    throw ApiException(400, "要素 $i: password は空にできません。")
                }
                val role = when (roleRaw) {
                    "EMPLOYEE" -> Role.EMPLOYEE
                    "APPROVER" -> Role.APPROVER
                    else -> throw ApiException(400, "要素 $i: role は EMPLOYEE か APPROVER で指定してください。")
                }
                val existing = db.user(username)
                if (existing == null) {
                    db.createUser(username, role, Hashing.hashPassword(password))
                    created += username
                } else if (existing.role != role) {
                    throw ApiException(
                        400,
                        "要素 $i: $username は既に別のロール（${existing.role}）で存在します。"
                    )
                } else {
                    updated += username
                }
            }
            println("作成: ${created.size}件, 既存: ${updated.size}件")
            created.forEach { println("  + $it") }
            updated.forEach { println("  = $it (スキップ)") }
        } finally {
            db.close()
        }
    }

    private val USERNAME_PATTERN = Regex("^[A-Za-z0-9._@-]+$")
}
