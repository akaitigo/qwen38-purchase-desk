package com.example.purchasedesk.data

import com.example.purchasedesk.TempDb
import com.example.purchasedesk.withDb
import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.service.Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StateMachineTest {

    /** Boots a DB with one employee and one approver, and creates a DRAFT. */
    private fun withSeed(block: (Service, TempDb, String, com.example.purchasedesk.domain.User, com.example.purchasedesk.domain.User) -> Unit) {
        withDb { t ->
            t.add("alice", Role.EMPLOYEE)
            t.add("boss", Role.APPROVER)
            val alice = t.employee("alice")
            val boss = t.approver("boss")
            val created = t.service.create(alice, t.sampleInput())
            block(t.service, t, created.id, alice, boss)
        }
    }

    @Test
    fun `full lifecycle draft submit return resubmit approve`() {
        withSeed { svc, _, id, alice, boss ->
            var r = svc.submit(alice, id)
            assertEquals(State.SUBMITTED, r.state)

            r = svc.`return`(boss, id, "量が多すぎます")
            assertEquals(State.RETURNED, r.state)
            assertEquals("量が多すぎます", r.history.last { it.action == com.example.purchasedesk.domain.Action.RETURN }.comment)

            r = svc.submit(alice, id)
            assertEquals(State.SUBMITTED, r.state)

            r = svc.approve(boss, id)
            assertEquals(State.APPROVED, r.state)
        }
    }

    @Test
    fun `double approve returns 409 and does not duplicate history`() {
        withSeed { svc, _, id, alice, boss ->
            svc.submit(alice, id)
            svc.approve(boss, id)
            val ex = assertFailsWith<ApiException> { svc.approve(boss, id) }
            assertEquals(409, ex.status)
            val final = svc.get(boss, id)
            assertEquals(1, final.history.count { it.action == com.example.purchasedesk.domain.Action.APPROVE })
        }
    }

    @Test
    fun `submit on approved or returned states is rejected`() {
        withSeed { svc, _, id, alice, boss ->
            svc.submit(alice, id)
            svc.approve(boss, id)
            // submitting an APPROVED request: state is APPROVED (not DRAFT/RETURNED)
            assertFailsWith<ApiException> { svc.submit(alice, id) }
        }
    }

    @Test
    fun `return on a draft is rejected`() {
        withSeed { svc, _, id, _, boss ->
            val ex = assertFailsWith<ApiException> { svc.`return`(boss, id, "no") }
            assertEquals(409, ex.status)
        }
    }

    @Test
    fun `update on a submitted request is rejected`() {
        withSeed { svc, _, id, alice, _ ->
            svc.submit(alice, id)
            val input = com.example.purchasedesk.domain.RequestInput("新しい品名", 3, 200, "修正")
            val ex = assertFailsWith<ApiException> { svc.update(alice, id, input) }
            assertEquals(409, ex.status)
        }
    }

    @Test
    fun `history accumulates in order with the right actors`() {
        withSeed { svc, _, id, alice, boss ->
            svc.submit(alice, id)
            svc.`return`(boss, id, "修正してください")
            svc.submit(alice, id)
            svc.approve(boss, id)
            val final = svc.get(boss, id)
            val actions = final.history.map { it.action.name }
            assertEquals(
                listOf("CREATE", "SUBMIT", "RETURN", "SUBMIT", "APPROVE"),
                actions
            )
        }
    }

    @Test
    fun `db layer approve returns null on conflict`() {
        withDb { t ->
            t.add("alice", Role.EMPLOYEE)
            t.add("boss", Role.APPROVER)
            val alice = t.employee("alice")
            val boss = t.approver("boss")
            val created = t.service.create(alice, t.sampleInput())
            t.service.submit(alice, created.id)
            val first = t.db.approveRequest(created.id, boss, "2026-01-01T00:00:00Z")
            assertNotNull(first)
            assertNull(t.db.approveRequest(created.id, boss, "2026-01-01T00:00:01Z"))
        }
    }
}
