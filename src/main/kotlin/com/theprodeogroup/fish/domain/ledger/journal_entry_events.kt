package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DomainEvent
import java.time.Instant

/**
 * Fired on the Draft/Pending -> Posted transition (docs/DDD_Design.md
 * Section 4). Consumed by: reporting, Scrip's business-health monitoring,
 * Tax context (recompute TaxComputation incrementally) - none of those
 * consumers exist yet, this just fires the event per the design.
 */
data class JournalEntryPosted(
    val journalEntryId: JournalEntryId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
