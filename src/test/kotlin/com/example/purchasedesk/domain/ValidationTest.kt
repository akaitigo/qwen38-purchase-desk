package com.example.purchasedesk.domain

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidationTest {

    private fun valid(input: String, quantity: Long = 2, unitPrice: Long = 100): JSONObject {
        return JSONObject()
            .put("itemName", input)
            .put("quantity", quantity)
            .put("unitPriceYen", unitPrice)
            .put("reason", "会議での使用")
    }

    private fun expect400(json: JSONObject, id: String? = null): ApiException {
        return assertFailsWith<ApiException> { Validation.parseRequestInput(json, id) }
            .also { assertEquals(400, it.status) }
    }

    @Test
    fun `accepts a valid input and computes nothing itself`() {
        val input = Validation.parseRequestInput(valid("ボールペン"))
        assertEquals("ボールペン", input.itemName)
        assertEquals(2L, input.quantity)
        assertEquals(100L, input.unitPriceYen)
    }

    @Test
    fun `trims item name`() {
        val input = Validation.parseRequestInput(valid("  ノート  "))
        assertEquals("ノート", input.itemName)
    }

    @Test
    fun `rejects blank or too-long item name`() {
        expect400(valid("   ").put("itemName", ""))
        expect400(valid("x".repeat(101)))
    }

    @Test
    fun `accepts boundary item names`() {
        Validation.parseRequestInput(valid("x".repeat(1)))
        Validation.parseRequestInput(valid("x".repeat(100)))
    }

    @Test
    fun `rejects missing required fields`() {
        expect400(JSONObject())
        expect400(JSONObject().put("itemName", "a"))
        expect400(JSONObject().put("itemName", "a").put("quantity", 1))
        expect400(JSONObject().put("itemName", "a").put("quantity", 1).put("unitPriceYen", 1))
    }

    @Test
    fun `rejects decimal and non-numeric quantity`() {
        expect400(valid("a").put("quantity", 1.5))
        expect400(valid("a").put("quantity", " 2"))
        expect400(valid("a").put("quantity", "-2"))
        expect400(valid("a").put("quantity", "2.0"))
        expect400(valid("a").put("quantity", "1,000"))
        expect400(valid("a").put("quantity", true))
        expect400(valid("a").put("quantity", JSONObject.NULL))
    }

    @Test
    fun `accepts integer strings from html forms`() {
        val input = Validation.parseRequestInput(
            valid("a").put("quantity", "3").put("unitPriceYen", "1500")
        )
        assertEquals(3L, input.quantity)
        assertEquals(1500L, input.unitPriceYen)
    }

    @Test
    fun `accepts integer-valued doubles`() {
        val input = Validation.parseRequestInput(valid("a").put("quantity", 3.0))
        assertEquals(3L, input.quantity)
    }

    @Test
    fun `rejects quantity out of range`() {
        expect400(valid("a", quantity = 0))
        expect400(valid("a", quantity = 1001))
        Validation.parseRequestInput(valid("a", quantity = 1))
        Validation.parseRequestInput(valid("a", quantity = 1000))
    }

    @Test
    fun `rejects unit price out of range`() {
        expect400(valid("a", unitPrice = -1))
        expect400(valid("a", unitPrice = 1_000_001))
        Validation.parseRequestInput(valid("a", unitPrice = 0))
        Validation.parseRequestInput(valid("a", unitPrice = 1_000_000))
    }

    @Test
    fun `rejects total above the cap without overflow`() {
        // 200 * 500000 = 100,000,000  → OK
        Validation.parseRequestInput(valid("a", quantity = 200, unitPrice = 500_000))
        // 201 * 500000 = 100,500,000 → rejected
        expect400(valid("a", quantity = 201, unitPrice = 500_000))
    }

    @Test
    fun `rejects reason out of range`() {
        expect400(valid("a").put("reason", ""))
        expect400(valid("a").put("reason", "x".repeat(1001)))
        Validation.parseRequestInput(valid("a").put("reason", "x".repeat(1000)))
    }

    @Test
    fun `rejects whitespace-only reason`() {
        expect400(valid("a").put("reason", "   "))
        expect400(valid("a").put("reason", "\t\n "))
        Validation.parseRequestInput(valid("a").put("reason", " 理由 "))
    }

    @Test
    fun `rejects mismatched id on update`() {
        // No id field supplied on an update is fine; a mismatched one is not.
        Validation.parseRequestInput(valid("a"), id = "abc")
        expect400(valid("a").put("id", "wrong"), id = "abc")
        Validation.parseRequestInput(valid("a").put("id", "abc"), id = "abc")
    }

    @Test
    fun `return comment must be 1 to 1000 chars`() {
        assertEquals("理由", Validation.parseReturnComment(JSONObject().put("comment", " 理由 ")))
        assertFailsWith<ApiException> { Validation.parseReturnComment(JSONObject().put("comment", "   ")) }
        assertFailsWith<ApiException> { Validation.parseReturnComment(JSONObject().put("comment", "x".repeat(1001))) }
        Validation.parseReturnComment(JSONObject().put("comment", "x".repeat(1000)))
        assertTrue(assertFailsWith<ApiException> { Validation.parseReturnComment(JSONObject()) }.status == 400)
    }
}
