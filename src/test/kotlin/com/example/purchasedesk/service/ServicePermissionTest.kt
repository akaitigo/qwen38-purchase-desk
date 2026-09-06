package com.example.purchasedesk.service

import com.example.purchasedesk.TempDb
import com.example.purchasedesk.withDb
import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServicePermissionTest {

    private fun seed(t: TempDb, createSecond: Boolean = true) {
        t.add("alice", Role.EMPLOYEE)
        t.add("bob", Role.EMPLOYEE)
        t.add("boss", Role.APPROVER)
        t.service.create(t.employee("alice"), t.sampleInput("アリスの品"))
        if (createSecond) t.service.create(t.employee("bob"), t.sampleInput("ボブの品"))
    }

    @Test
    fun `employee cannot create when role is approver`() {
        withDb { t ->
            seed(t)
            val ex = assertFailsWith<ApiException> {
                t.service.create(t.approver("boss"), t.sampleInput())
            }
            assertEquals(403, ex.status)
        }
    }

    @Test
    fun `approver cannot submit or edit`() {
        withDb { t ->
            seed(t)
            val id = t.service.list(t.employee("alice")).first().id
            assertEquals(403, assertFailsWith<ApiException> { t.service.submit(t.approver("boss"), id) }.status)
            assertEquals(403, assertFailsWith<ApiException> {
                t.service.update(t.approver("boss"), id, t.sampleInput())
            }.status)
        }
    }

    @Test
    fun `employee cannot approve or return`() {
        withDb { t ->
            seed(t)
            val id = t.service.list(t.employee("alice")).first().id
            t.service.submit(t.employee("alice"), id)
            assertEquals(403, assertFailsWith<ApiException> { t.service.approve(t.employee("bob"), id) }.status)
            assertEquals(403, assertFailsWith<ApiException> { t.service.`return`(t.employee("bob"), id, "no") }.status)
        }
    }

    @Test
    fun `employee list only shows own requests`() {
        withDb { t ->
            seed(t)
            val aliceList = t.service.list(t.employee("alice"))
            assertEquals(1, aliceList.size)
            assertEquals("アリスの品", aliceList.first().itemName)
            val bobList = t.service.list(t.employee("bob"))
            assertEquals("ボブの品", bobList.first().itemName)
        }
    }

    @Test
    fun `approver list shows all requests`() {
        withDb { t ->
            seed(t)
            assertEquals(2, t.service.list(t.approver("boss")).size)
        }
    }

    @Test
    fun `employee cannot read another employee request 404`() {
        withDb { t ->
            seed(t)
            val bobReq = t.service.list(t.employee("bob")).first()
            val ex = assertFailsWith<ApiException> { t.service.get(t.employee("alice"), bobReq.id) }
            assertEquals(404, ex.status)
        }
    }

    @Test
    fun `approver can read any request`() {
        withDb { t ->
            seed(t)
            val bobReq = t.service.list(t.employee("bob")).first()
            val seen = t.service.get(t.approver("boss"), bobReq.id)
            assertEquals("ボブの品", seen.itemName)
        }
    }

    @Test
    fun `get with malformed id is 404 not exception`() {
        withDb { t ->
            seed(t)
            val ex = assertFailsWith<ApiException> { t.service.get(t.employee("alice"), "bad id/with slash") }
            assertEquals(404, ex.status)
            val ex2 = assertFailsWith<ApiException> { t.service.get(t.employee("alice"), "") }
            assertEquals(404, ex2.status)
        }
    }

    @Test
    fun `total yen is computed server side from quantity and price`() {
        withDb { t ->
            seed(t, createSecond = false)
            val r = t.service.create(t.employee("alice"), t.sampleInput("計算", qty = 7, price = 1500))
            assertEquals(10500L, r.totalYen)
        }
    }

    @Test
    fun `submit then get reflects submitted state`() {
        withDb { t ->
            seed(t, createSecond = false)
            val r = t.service.create(t.employee("alice"), t.sampleInput())
            assertEquals(State.DRAFT, r.state)
            t.service.submit(t.employee("alice"), r.id)
            assertEquals(State.SUBMITTED, t.service.get(t.employee("alice"), r.id).state)
        }
    }
}
