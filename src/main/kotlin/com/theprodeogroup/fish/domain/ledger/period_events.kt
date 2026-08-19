package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DomainEvent
import java.time.Instant

/**
 * Domain events raised by the Period aggregate - Section 4 already named
 * both ("`PeriodClosed` / `PeriodLocked` — fired on Period lifecycle
 * transitions") well before `Period` itself had any event-emission
 * machinery at all; added alongside `ClosePeriodUseCase`
 * (docs/DDD_Design.md Section 10.9) since that's the first caller that
 * needs one. Grouped in one file, same rationale as `tenant_events.kt`/
 * `common`. Deliberately no `PeriodOpened`/`PeriodReopened` - Section 4
 * never named those, and nothing downstream needs them yet.
 */

/** Fired on the Open -> Closed transition. */
data class PeriodClosed(
    val periodId: PeriodId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/** Fired on the Closed -> Locked transition. */
data class PeriodLocked(
    val periodId: PeriodId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
