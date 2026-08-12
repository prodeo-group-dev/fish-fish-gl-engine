package com.theprodeogroup.fish.domain.lending

import com.theprodeogroup.fish.domain.common.DomainEvent
import java.time.Instant

/** Fired on every ArrearsCase stage escalation (docs/DDD_Design.md Section 4). */
data class ArrearsStageChanged(
    val arrearsCaseId: ArrearsCaseId,
    val newStage: ArrearsStage,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/**
 * Fired when an ArrearsCase resolves, at any stage (docs/DDD_Design.md
 * Section 4) - not previously named there; added alongside this
 * increment since resolution is a distinct, consumer-relevant moment
 * from a stage escalation (e.g. reporting, member communications).
 */
data class ArrearsCaseResolved(
    val arrearsCaseId: ArrearsCaseId,
    val outcome: ArrearsOutcome,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
