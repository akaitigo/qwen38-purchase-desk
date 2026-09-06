package com.example.purchasedesk.http

import com.example.purchasedesk.domain.PurchaseRequest
import org.json.JSONObject

object Json {
    /**
     * Serializes a PurchaseRequest to a JSONObject matching the API contract:
     * id, ownerUsername, itemName, quantity, unitPriceYen, totalYen, reason,
     * state, history[{action, actorUsername, at, comment}].
     */
    fun requestToJson(req: PurchaseRequest): JSONObject {
        val hist = org.json.JSONArray()
        for (h in req.history) {
            val o = JSONObject()
            o.put("action", h.action.name)
            o.put("actorUsername", h.actorUsername)
            o.put("at", h.at)
            o.put("comment", h.comment)
            hist.put(o)
        }
        return JSONObject()
            .put("id", req.id)
            .put("ownerUsername", req.ownerUsername)
            .put("itemName", req.itemName)
            .put("quantity", req.quantity)
            .put("unitPriceYen", req.unitPriceYen)
            .put("totalYen", req.totalYen)
            .put("reason", req.reason)
            .put("state", req.state.name)
            .put("history", hist)
    }

    fun requestWrapper(req: PurchaseRequest): String =
        JSONObject().put("request", requestToJson(req)).toString()

    fun listWrapper(reqs: List<PurchaseRequest>): String {
        val arr = org.json.JSONArray()
        for (r in reqs) arr.put(requestToJson(r))
        return JSONObject().put("requests", arr).toString()
    }

    fun errorJson(message: String): String =
        JSONObject().put("error", message).toString()

    fun sessionJson(user: Pair<String, String>?, csrf: String): String {
        val root = JSONObject()
        if (user == null) {
            root.put("user", JSONObject.NULL)
        } else {
            root.put("user", JSONObject().put("username", user.first).put("role", user.second))
        }
        root.put("csrfToken", csrf)
        return root.toString()
    }
}
