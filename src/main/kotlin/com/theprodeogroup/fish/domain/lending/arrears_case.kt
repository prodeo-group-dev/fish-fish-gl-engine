package com.theprodeogroup.fish.domain.lending

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.Instant
import java.time.LocalDate

/**
 * A staged record tracking a borrower's arrears against a loan
 * (docs/DDD_Design.md Section 3.3), sourced from `docs/UK/Purse Credit
 * Union - Ethical & Theological Framework.docx`'s "Arrears Management
 * Process" - the four-stage ladder and its day-overdue thresholds are
 * quoted from that document, not invented here.
 *
 * Scoped to the shared escalation ladder and generic outcome recording
 * only (confirmed 2026-08-12) - `CAPITALIZED_TO_EQUITY` (business-only,
 * hands off to a not-yet-built `EquityStake`) and the individual-only
 * "Jubilee" 7-year write-off model are both deliberately deferred,
 * neither is documented in enough detail to build correctly yet.
 *
 * [companyId] is set only when the borrower is a business `Company`
 * (Section 2.9's Party-Role design hasn't been built, so there's no
 * `Borrower` aggregate to distinguish this more richly yet) - it's what
 * lets [escalate] call [Company.flagSubstantialDoubt] when the case
 * reaches Final Resolution, closing the loop `Company`'s own KDoc has
 * been pointing at since it was built (Section 9.7).
 */
class ArrearsCase private constructor(
    val id: ArrearsCaseId,
    val borrowerId: BorrowerId,
    val companyId: CompanyId?,
    val loanAccountId: AccountId,
    val openedDate: LocalDate
) {
    var stage: ArrearsStage = ArrearsStage.EARLY_CONTACT
        private set

    var outcome: ArrearsOutcome? = null
        private set

    /** Confirmed 2026-08-12: a case can resolve at any stage, not only at Final Resolution. */
    val isResolved: Boolean
        get() = outcome != null

    private val _domainEvents = mutableListOf<DomainEvent>()

    fun pullDomainEvents(): List<DomainEvent> {
        val events = _domainEvents.toList()
        _domainEvents.clear()
        return events
    }

    /**
     * Moves to the next stage on the days-overdue ladder. [company] is
     * required only when this case's [companyId] is set *and* the next
     * stage is [ArrearsStage.FINAL_RESOLUTION] - the actual `Company`
     * object is needed (not just its ID) to call
     * [Company.flagSubstantialDoubt] on it, same pattern as
     * `PurchaseOrder.send(creditor)` taking the `Creditor` object itself.
     *
     * A `Company` already in `SUBSTANTIAL_DOUBT` (e.g. from a different,
     * earlier `ArrearsCase` against the same business) failing to
     * re-flag doesn't block this case's own escalation - that's not a
     * failure of this operation, the Company is already in the state
     * this call was trying to reach.
     */
    fun escalate(company: Company? = null, now: Instant = Instant.now()): ValidationResult {
        if (isResolved) {
            return ValidationResult.failure("Cannot escalate a resolved ArrearsCase")
        }
        val nextStage = when (stage) {
            ArrearsStage.EARLY_CONTACT -> ArrearsStage.PASTORAL_ENGAGEMENT
            ArrearsStage.PASTORAL_ENGAGEMENT -> ArrearsStage.FORMAL_REVIEW
            ArrearsStage.FORMAL_REVIEW -> ArrearsStage.FINAL_RESOLUTION
            ArrearsStage.FINAL_RESOLUTION -> null
        }
        if (nextStage == null || !stage.canTransitionTo(nextStage)) {
            return ValidationResult.failure("Cannot escalate an ArrearsCase already at $stage")
        }
        if (nextStage == ArrearsStage.FINAL_RESOLUTION && companyId != null) {
            if (company == null || company.id != companyId) {
                return ValidationResult.failure(
                    "The matching business Company must be provided when escalating to Final Resolution, to update its going-concern status"
                )
            }
            company.flagSubstantialDoubt()
        }
        stage = nextStage
        _domainEvents.add(ArrearsStageChanged(id, stage, now))
        return ValidationResult.success()
    }

    /** Records how this case was resolved - valid from any stage, not just Final Resolution. */
    fun resolve(outcome: ArrearsOutcome, now: Instant = Instant.now()): ValidationResult {
        if (isResolved) {
            return ValidationResult.failure("ArrearsCase is already resolved")
        }
        this.outcome = outcome
        _domainEvents.add(ArrearsCaseResolved(id, outcome, now))
        return ValidationResult.success()
    }

    companion object {
        fun open(
            borrowerId: BorrowerId,
            loanAccountId: AccountId,
            openedDate: LocalDate,
            companyId: CompanyId? = null,
            id: ArrearsCaseId = ArrearsCaseId.generate()
        ): ArrearsCase = ArrearsCase(id, borrowerId, companyId, loanAccountId, openedDate)
    }
}
