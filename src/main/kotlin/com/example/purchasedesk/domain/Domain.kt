package com.example.purchasedesk.domain

/** Request lifecycle states. */
enum class State { DRAFT, SUBMITTED, APPROVED, RETURNED }

/** Who can act. */
enum class Role { EMPLOYEE, APPROVER }

/** History action kinds, in the order required by the spec. */
enum class Action { CREATE, UPDATE, SUBMIT, RETURN, APPROVE }

/** A single history entry (chronological). */
data class HistoryEntry(
    val action: Action,
    val actorUsername: String,
    val at: String,
    val comment: String
)

/** A purchase request as exposed through the API / UI. */
data class PurchaseRequest(
    val id: String,
    val ownerUsername: String,
    val itemName: String,
    val quantity: Long,
    val unitPriceYen: Long,
    val totalYen: Long,
    val reason: String,
    val state: State,
    val history: List<HistoryEntry>
)

/** Authenticated user. */
data class User(val username: String, val role: Role)
