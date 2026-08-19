package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.LocalDate

/**
 * A fiscal period belonging to a Company (docs/DDD_Design.md Section 3.1),
 * with its own open/closed/locked lifecycle via [PeriodStatus].
 *
 * Deliberately does not know about `JournalEntry` or check it - per the
 * Section 3.1 design note, "no posting into a closed period" is an
 * application-layer/domain-service check (`PostingService`, not yet
 * built) that loads both `Period` and `JournalEntry` and validates,
 * rather than merging them into one aggregate. [allowsPosting] is the
 * primitive that service will call.
 */
class Period private constructor(
    val id: PeriodId,
    val companyId: CompanyId,
    val periodType: PeriodType,
    val startDate: LocalDate,
    val endDate: LocalDate
) {
    var status: PeriodStatus = PeriodStatus.DRAFT
        private set

    fun allowsPosting(): Boolean = status.allowsPosting()

    fun open(): ValidationResult = transitionTo(PeriodStatus.OPEN)

    fun close(): ValidationResult = transitionTo(PeriodStatus.CLOSED)

    fun reopen(): ValidationResult = transitionTo(PeriodStatus.OPEN)

    fun lock(): ValidationResult = transitionTo(PeriodStatus.LOCKED)

    private fun transitionTo(newStatus: PeriodStatus): ValidationResult {
        if (!status.canTransitionTo(newStatus)) {
            return ValidationResult.failure("Cannot transition Period from $status to $newStatus")
        }
        status = newStatus
        return ValidationResult.success()
    }

    companion object {
        fun create(
            companyId: CompanyId,
            periodType: PeriodType,
            startDate: LocalDate,
            endDate: LocalDate,
            id: PeriodId = PeriodId.generate()
        ): Period {
            require(!endDate.isBefore(startDate)) {
                "Period endDate ($endDate) cannot be before startDate ($startDate)"
            }
            return Period(id, companyId, periodType, startDate, endDate)
        }

        /**
         * Rebuilds a `Period` from persisted data, bypassing [create]'s
         * validation - same reasoning as `Account.reconstitute()`.
         * `internal`, for `PeriodRepository` implementations only.
         */
        internal fun reconstitute(
            id: PeriodId,
            companyId: CompanyId,
            periodType: PeriodType,
            startDate: LocalDate,
            endDate: LocalDate,
            status: PeriodStatus
        ): Period {
            val period = Period(id, companyId, periodType, startDate, endDate)
            period.status = status
            return period
        }
    }
}
