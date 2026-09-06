package com.example.purchasedesk.http

import com.example.purchasedesk.domain.Action
import com.example.purchasedesk.domain.PurchaseRequest
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.User

/** Server-rendered HTML with mandatory escaping of every user value. */
object Html {

    // ---------- escaping ----------

    fun esc(s: String?): String = s.orEmpty()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    fun num(n: Long): String = n.toString().replace(Regex("(?<=\\d)(?=(\\d{3})+$)"), ",")

    private fun stateLabel(s: State): String = when (s) {
        State.DRAFT -> "下書き"
        State.SUBMITTED -> "提出済み"
        State.APPROVED -> "承認済み"
        State.RETURNED -> "差し戻し済み"
    }

    private fun actionLabel(a: Action): String = when (a) {
        Action.CREATE -> "作成"
        Action.UPDATE -> "更新"
        Action.SUBMIT -> "申請"
        Action.RETURN -> "差し戻し"
        Action.APPROVE -> "承認"
    }

    private fun loginCss(): String =
        "body{font-family:'Hiragino Kaku Gothic ProN','Meiryo',sans-serif;background:#f6f7f9;color:#222;margin:0;}" +
        ".loginbox{max-width:360px;margin:60px auto;background:#fff;padding:28px;border:1px solid #e2e5ea;border-radius:10px;}" +
        "h1{font-size:20px;text-align:center;}" +
        "label{display:block;font-weight:600;margin:14px 0 4px;}" +
        "input{width:100%;box-sizing:border-box;padding:9px 10px;border:1px solid #cfd4dc;border-radius:6px;font-size:14px;}" +
        "button{width:100%;margin-top:18px;background:#1f3a5f;color:#fff;border:0;border-radius:6px;padding:10px;font-size:15px;cursor:pointer;}" +
        ".err{background:#fdecea;color:#8a1f1f;padding:10px 12px;border-radius:8px;margin-bottom:8px;font-size:14px;}" +
        ".btn{display:inline-block;background:#1f3a5f;color:#fff;text-decoration:none;padding:8px 14px;border-radius:6px;margin-top:12px;}"

    // ---------- shared ----------

    private fun head(title: String, csrf: String): String = buildString {
        append("<!doctype html>\n<html lang=\"ja\">\n<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>${esc(title)} | 備品購入申請</title>\n")
        append("<style>")
        append(
            "body{font-family:'Hiragino Kaku Gothic ProN','Meiryo',sans-serif;margin:0;background:#f6f7f9;color:#222;}" +
            "header{background:#1f3a5f;color:#fff;padding:12px 24px;display:flex;justify-content:space-between;align-items:center;}" +
            "header a{color:#cfe0ff;text-decoration:none;margin-left:16px;}" +
            "main{max-width:960px;margin:24px auto;padding:0 16px;}" +
            "card,.card{background:#fff;border:1px solid #e2e5ea;border-radius:10px;padding:20px;margin-bottom:16px;box-shadow:0 1px 2px rgba(0,0,0,.04);}" +
            "label{display:block;font-weight:600;margin:12px 0 4px;}" +
            "input,textarea{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #cfd4dc;border-radius:6px;font-size:14px;}" +
            "textarea{min-height:90px;}" +
            "button,.btn{background:#1f3a5f;color:#fff;border:0;border-radius:6px;padding:9px 16px;font-size:14px;cursor:pointer;}" +
            "button.danger{background:#b23b3b;} button.muted{background:#6b7280;}" +
            ".btn{display:inline-block;text-decoration:none;}" +
            ".row{display:flex;gap:12px;align-items:flex-start;}" +
            ".err{background:#fdecea;color:#8a1f1f;border:1px solid #f5c6c6;padding:10px 14px;border-radius:8px;margin-bottom:16px;}" +
            ".ok{background:#e8f5e9;color:#1e5a26;padding:10px 14px;border-radius:8px;margin-bottom:16px;}" +
            "table{width:100%;border-collapse:collapse;font-size:14px;}" +
            "th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eaecef;}" +
            "th{background:#f0f2f5;}" +
            ".badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:600;}" +
            ".DRAFT{background:#eef0f3;color:#555;}.SUBMITTED{background:#fff4e0;color:#8a5a00;}" +
            ".APPROVED{background:#e3f4e5;color:#1e5a26;}.RETURNED{background:#fde9e9;color:#8a1f1f;}" +
            ".muted{color:#6b7280;}" +
            ".history{font-size:13px;} .history li{margin:4px 0;}"
        )
        append("</style>\n</head>\n<body>\n")
        append("<header><strong>備品購入申請・承認</strong></header>\n")
    }

    private fun nav(user: User?, csrf: String): String = buildString {
        if (user == null) {
            append("<a href=\"/login\">ログイン</a>")
        } else {
            append("ようこそ、${esc(user.username)} さん（${if (user.role == Role.APPROVER) "承認者" else "社員"}）")
            append(" <a href=\"/requests\">一覧</a>")
            if (user.role == Role.EMPLOYEE) append(" <a href=\"/requests/new\">新規申請</a>")
            append(" <form method=\"post\" action=\"/logout\" style=\"display:inline;margin-left:8px;\">")
            append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
            append("<button type=\"submit\" style=\"background:#6b7280;color:#fff;\">ログアウト</button>")
            append("</form>")
        }
    }

    // ---------- login ----------

    fun loginPage(prefill: String? = null, error: String? = null, anonCsrf: String? = null): String = buildString {
        append("<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">")
        append("<title>ログイン | 備品購入申請</title>")
        append("<style>")
        append(loginCss())
        append("</style>")
        append("</head><body>")
        append("<div class=\"loginbox\"><h1>備品購入申請・承認</h1>")
        if (error != null) append("<div class=\"err\">${esc(error)}</div>")
        append("<form method=\"post\" action=\"/login\">")
        val csrf = anonCsrf ?: ""
        append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
        append("<label>ユーザー名</label>")
        append("<input name=\"username\" value=\"${esc(prefill)}\" autocomplete=\"username\" required>")
        append("<label>パスワード</label>")
        append("<input type=\"password\" name=\"password\" autocomplete=\"current-password\" required>")
        append("<button type=\"submit\">ログイン</button>")
        append("</form></div></body></html>")
    }

    // ---------- list ----------

    fun listPage(user: User, reqs: List<PurchaseRequest>, csrf: String): String = buildString {
        // Pull the CSRF token from the live session so forms always carry the
        // matching token. The handler passes the token via a parameter.
        append(head("申請一覧", csrf))
        append(nav(user, csrf))
        append("<main>")
        append("<h1>申請一覧</h1>")
        if (reqs.isEmpty()) {
            append("<div class=\"card\">まだ申請がありません。</div>")
        } else {
            append("<div class=\"card\"><table><thead><tr>")
            append("<th>品名</th><th>数量</th><th>単価（円）</th><th>合計（円）</th>")
            append("<th>申請者</th><th>状態</th><th>未処理</th><th></th>")
            append("</tr></thead><tbody>")
            for (r in reqs) {
                append("<tr>")
                append("<td>${esc(r.itemName)}</td>")
                append("<td>${num(r.quantity)}</td>")
                append("<td>${num(r.unitPriceYen)}</td>")
                append("<td>${num(r.totalYen)}</td>")
                append("<td>${esc(r.ownerUsername)}</td>")
                append("<td><span class=\"badge ${esc(r.state.name)}\">${stateLabel(r.state)}</span></td>")
                append("<td>${if (r.state == State.SUBMITTED) "○" else "–"}</td>")
                append("<td><a class=\"btn\" href=\"/requests/${esc(r.id)}\">詳細</a></td>")
                append("</tr>")
            }
            append("</tbody></table></div>")
        }
        append("</main></body></html>")
    }

    // ---------- new / edit (employee only) ----------

    fun newRequestPage(
        user: User,
        csrf: String,
        editing: PurchaseRequest? = null,
        form: Map<String, String>? = null,
        error: String? = null
    ): String = buildString {
        append(head(if (editing == null) "新規申請" else "申請の修正", csrf))
        append(nav(user, csrf))
        append("<main>")
        append("<h1>${if (editing == null) "新規申請" else "申請の修正"}</h1>")
        if (error != null) append("<div class=\"err\">${esc(error)}</div>")
        append("<form method=\"post\" action=\"/requests/new\">")
        append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
        append("<label>品名（1〜100文字）</label>")
        val itemNameVal = esc(form?.get("itemName") ?: editing?.itemName)
        append("<input name=\"itemName\" maxlength=\"100\" value=\"$itemNameVal\" required>")
        append("<label>数量（1〜1000）</label>")
        val quantityVal = form?.get("quantity") ?: editing?.quantity.toString()
        append("<input type=\"number\" name=\"quantity\" min=\"1\" max=\"1000\" step=\"1\" value=\"$quantityVal\" required>")
        append("<label>単価（円、0〜1,000,000）</label>")
        val unitPriceVal = form?.get("unitPriceYen") ?: editing?.unitPriceYen.toString()
        append("<input type=\"number\" name=\"unitPriceYen\" min=\"0\" max=\"1000000\" step=\"1\" value=\"$unitPriceVal\" required>")
        append("<label>購入理由（1〜1000文字）</label>")
        val reasonVal = esc(form?.get("reason") ?: editing?.reason)
        append("<textarea name=\"reason\" maxlength=\"1000\" required>$reasonVal</textarea>")
        append("<div style=\"margin-top:16px;\">")
        append("<button type=\"submit\">保存（下書き）</button>")
        append(" <a class=\"btn\" href=\"/requests\">キャンセル</a>")
        append("</div></form></main></body></html>")
    }

    // ---------- detail ----------

    fun detailPage(
        user: User,
        req: PurchaseRequest,
        csrf: String,
        form: Map<String, String>? = null,
        error: String? = null
    ): String = buildString {
        append(head("申請詳細", csrf))
        append(nav(user, csrf))
        append("<main>")
        append("<h1>申請詳細 <span class=\"badge ${esc(req.state.name)}\" style=\"vertical-align:middle;\">${stateLabel(req.state)}</span></h1>")
        if (error != null) append("<div class=\"err\">${esc(error)}</div>")
        append("<div class=\"card\">")
        append("<table>")
        append("<tr><th>ID</th><td>${esc(req.id)}</td></tr>")
        append("<tr><th>品名</th><td>${esc(req.itemName)}</td></tr>")
        append("<tr><th>数量</th><td>${num(req.quantity)}</td></tr>")
        append("<tr><th>単価（円）</th><td>${num(req.unitPriceYen)}</td></tr>")
        append("<tr><th>合計（円）</th><td><strong>${num(req.totalYen)}</strong></td></tr>")
        append("<tr><th>購入理由</th><td>${esc(req.reason).replace("\n", "<br>")}</td></tr>")
        append("<tr><th>申請者</th><td>${esc(req.ownerUsername)}</td></tr>")
        append("</table></div>")

        val isOwner = req.ownerUsername == user.username
        val canEdit = user.role == Role.EMPLOYEE && isOwner &&
                (req.state == State.DRAFT || req.state == State.RETURNED)
        val canSubmit = user.role == Role.EMPLOYEE && isOwner &&
                (req.state == State.DRAFT || req.state == State.RETURNED)
        val canApprove = user.role == Role.APPROVER && req.state == State.SUBMITTED
        val canReturn = user.role == Role.APPROVER && req.state == State.SUBMITTED

        if (canEdit || canSubmit || canApprove || canReturn) {
            append("<div class=\"card\"><div class=\"row\">")
            if (canSubmit) {
                append("<form method=\"post\" action=\"/requests/${esc(req.id)}\">")
                append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
                append("<input type=\"hidden\" name=\"action\" value=\"submit\">")
                append("<button type=\"submit\">${if (req.state == State.RETURNED) "再申請" else "申請"}</button>")
                append("</form>")
            }
            if (canEdit) {
                append("<form method=\"post\" action=\"/requests/${esc(req.id)}\">")
                append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
                append("<input type=\"hidden\" name=\"action\" value=\"edit\">")
                append("<a class=\"btn\" href=\"/requests/${esc(req.id)}/edit\">編集</a>")
                append("</form>")
            }
            if (canApprove) {
                append("<form method=\"post\" action=\"/requests/${esc(req.id)}\">")
                append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
                append("<input type=\"hidden\" name=\"action\" value=\"approve\">")
                append("<button type=\"submit\">承認</button>")
                append("</form>")
            }
            if (canReturn) {
                append("<form method=\"post\" action=\"/requests/${esc(req.id)}\" style=\"flex:1;\">")
                append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
                append("<input type=\"hidden\" name=\"action\" value=\"return\">")
                append("<div class=\"row\">")
                val commentVal = esc(form?.get("comment"))
                append("<input name=\"comment\" placeholder=\"差し戻し理由（1〜1000文字）\" maxlength=\"1000\" value=\"$commentVal\">")
                append("<button type=\"submit\" class=\"danger\">差し戻し</button>")
                append("</div></form>")
            }
            append("</div></div>")
        }

        append("<div class=\"card\"><h2>操作履歴</h2>")
        if (req.history.isEmpty()) {
            append("<p class=\"muted\">履歴がありません。</p>")
        } else {
            append("<ol class=\"history\">")
            for (h in req.history) {
                append("<li>")
                append("<strong>${actionLabel(h.action)}</strong>")
                append(" 操作者：${esc(h.actorUsername)} 時刻：${esc(h.at)}")
                if (h.comment.isNotBlank()) {
                    append(" 理由：${esc(h.comment)}")
                }
                append("</li>")
            }
            append("</ol>")
        }
        append("</div>")
        append("<a class=\"btn\" href=\"/requests\">一覧へ戻る</a>")
        append("</main></body></html>")
    }

    // ---------- edit form (GET) ----------

    fun editPage(user: User, req: PurchaseRequest, csrf: String, form: Map<String, String>? = null, error: String? = null): String = buildString {
        append(head("申請の修正", csrf))
        append(nav(user, csrf))
        append("<main>")
        append("<h1>申請の修正</h1>")
        if (error != null) append("<div class=\"err\">${esc(error)}</div>")
        append("<form method=\"post\" action=\"/requests/${esc(req.id)}\">")
        append("<input type=\"hidden\" name=\"csrfToken\" value=\"${esc(csrf)}\">")
        append("<input type=\"hidden\" name=\"action\" value=\"update\">")
        append("<label>品名（1〜100文字）</label>")
        val eItemName = esc(form?.get("itemName") ?: req.itemName)
        append("<input name=\"itemName\" maxlength=\"100\" value=\"$eItemName\" required>")
        append("<label>数量（1〜1000）</label>")
        val eQty = form?.get("quantity") ?: req.quantity.toString()
        append("<input type=\"number\" name=\"quantity\" min=\"1\" max=\"1000\" step=\"1\" value=\"$eQty\" required>")
        append("<label>単価（円、0〜1,000,000）</label>")
        val ePrice = form?.get("unitPriceYen") ?: req.unitPriceYen.toString()
        append("<input type=\"number\" name=\"unitPriceYen\" min=\"0\" max=\"1000000\" step=\"1\" value=\"$ePrice\" required>")
        append("<label>購入理由（1〜1000文字）</label>")
        val eReason = esc(form?.get("reason") ?: req.reason)
        append("<textarea name=\"reason\" maxlength=\"1000\" required>$eReason</textarea>")
        append("<div style=\"margin-top:16px;\">")
        append("<button type=\"submit\">保存</button>")
        append(" <a class=\"btn\" href=\"/requests/${esc(req.id)}\">キャンセル</a>")
        append("</div></form></main></body></html>")
    }

    fun errorPage(message: String): String = buildString {
        append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\"><title>エラー</title>")
        append("<style>")
        append(loginCss())
        append("</style>")
        append("</head><body><main><div class=\"err\">${esc(message)}</div>")
        append("<a class=\"btn\" href=\"/\">ホームへ戻る</a></main></body></html>")
    }

}
