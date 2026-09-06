package com.example.purchasedesk.domain

/**
 * Request-scoped exception carrying an HTTP status and a user-readable
 * (Japanese) message. Handlers map these to `{error: ...}` responses.
 */
class ApiException(val status: Int, override val message: String) : RuntimeException(message)

/** Result of parsing + validating create/update inputs. */
data class RequestInput(
    val itemName: String,
    val quantity: Long,
    val unitPriceYen: Long,
    val reason: String
)

object Validation {
    const val MIN_ITEM_NAME = 1
    const val MAX_ITEM_NAME = 100
    const val MIN_REASON = 1
    const val MAX_REASON = 1000
    const val MIN_QUANTITY = 1L
    const val MAX_QUANTITY = 1000L
    const val MIN_UNIT_PRICE = 0L
    const val MAX_UNIT_PRICE = 1_000_000L
    const val MAX_TOTAL = 100_000_000L
    const val MAX_RETURN_COMMENT = 1000
    const val MIN_RETURN_COMMENT = 1

    /**
     * Validates create/update fields. [json] must be a valid JSON object;
     * otherwise a 400 is thrown (no DB write happens before this is called).
     *
     * Rejects: non-object JSON, missing fields, non-integer numbers, decimals,
     * out-of-range values, and any total that overflows or exceeds the cap.
     */
    fun parseRequestInput(json: org.json.JSONObject, id: String? = null): RequestInput {
        val itemNameRaw = requireString(json, "itemName")
        val itemName = itemNameRaw.trim()
        if (itemName.length < MIN_ITEM_NAME || itemName.length > MAX_ITEM_NAME) {
            throw ApiException(
                400,
                "品名は空白を除いて1文字以上、100文字以下で入力してください。"
            )
        }

        val quantity = parseLongField(json, "quantity", MIN_QUANTITY, MAX_QUANTITY)
        val unitPriceYen = parseLongField(json, "unitPriceYen", MIN_UNIT_PRICE, MAX_UNIT_PRICE)

        val reasonRaw = requireString(json, "reason")
        if (reasonRaw.trim().isEmpty() || reasonRaw.length > MAX_REASON) {
            throw ApiException(
                400,
                "購入理由は空白以外で1文字以上、1000文字以下で入力してください。"
            )
        }

        // Detect overflow before computing the total.
        if (quantity > 0 && unitPriceYen > MAX_TOTAL / quantity) {
            throw ApiException(
                400,
                "合計金額が上限（100,000,000円）を超えています。数量と単価を調整してください。"
            )
        }

        // The id field is only present on update; we validate that it matches.
        val idField = json.optString("id", null)
        if (id != null && idField != null && idField != id) {
            throw ApiException(400, "指定されたIDが一致しません。")
        }

        return RequestInput(itemName, quantity, unitPriceYen, reasonRaw)
    }

    /** Parses and validates the return comment (1..1000 chars, non-blank after trim). */
    fun parseReturnComment(json: org.json.JSONObject): String {
        val raw = requireString(json, "comment")
        val trimmed = raw.trim()
        if (trimmed.length < MIN_RETURN_COMMENT || trimmed.length > MAX_RETURN_COMMENT) {
            throw ApiException(
                400,
                "差し戻し理由を1文字以上、1000文字以下で入力してください。"
            )
        }
        return trimmed
    }

    private fun parseLongField(
        json: org.json.JSONObject,
        name: String,
        min: Long,
        max: Long
    ): Long {
        if (!json.has(name)) {
            throw ApiException(400, "「$name」を入力してください。")
        }
        val v = json.get(name)
        // org.json exposes ints as Int and longs as Long. Decimals become Double.
        if (v is Boolean) {
            throw ApiException(400, "「$name」は整数で入力してください。")
        }
        val value: Long = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is String -> {
                // HTML form fields arrive as strings; only plain integer literals
                // are accepted (no whitespace, no sign, no digits with separator).
                if (!Regex("^[0-9]+$").matches(v)) {
                    throw ApiException(400, "「$name」は整数で入力してください。")
                }
                v.toLongOrNull() ?: throw ApiException(400, "「$name」の値が大きすぎます。")
            }
            is Double -> {
                // Reject fractional / non-finite numbers.
                if (v.isNaN() || v.isInfinite() || v % 1.0 != 0.0) {
                    throw ApiException(400, "「$name」は整数で入力してください。")
                }
                try {
                    v.toLong()
                } catch (_: NumberFormatException) {
                    throw ApiException(400, "「$name」の値が大きすぎます。")
                }
            }
            else -> throw ApiException(400, "「$name」は整数で入力してください。")
        }
        if (value < min || value > max) {
            throw ApiException(400, "「$name」は ${min}〜${max} の範囲で入力してください。")
        }
        return value
    }

    private fun requireString(json: org.json.JSONObject, name: String): String {
        if (!json.has(name)) {
            throw ApiException(400, "「$name」を入力してください。")
        }
        val v = json.get(name)
        if (v !is String) {
            throw ApiException(400, "「$name」は文字列で入力してください。")
        }
        return v
    }
}
