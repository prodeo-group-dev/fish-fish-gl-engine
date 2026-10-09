package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.BalanceTieOut
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationCompletion
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

sealed class CompleteBankReconciliationResult {
    data class Success(val reconciliation: BankReconciliation) : CompleteBankReconciliationResult()
    data object CompanyNotFound : CompleteBankReconciliationResult()
    data object ReconciliationNotFound : CompleteBankReconciliationResult()
    /** Already COMPLETED or CANCELLED. */
    data object NotOpen : CompleteBankReconciliationResult()
    data class NotFullyMatched(val unmatchedStatementLineIds: List<BankStatementLineId>) : CompleteBankReconciliationResult()
    data class BalanceDifference(val tieOut: BalanceTieOut) : CompleteBankReconciliationResult()
}

/**
 * Finishes a Bank Reconciliation (UAT v2.2 W-M2): every statement line must be matched and, when
 * [enforceBalanceTieOut] is on, the statement balance must tie out to the ledger (see
 * [BankReconciliation.complete]). The tie-out is a switch rather than a rule while Femi decides it:
 * the contract (409 `balance_difference` with the figures) is the same either way, only whether it
 * is enforced changes.
 */
class CompleteBankReconciliationUseCase(
    private val companyRepository: CompanyRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository,
    private val enforceBalanceTieOut: Boolean = false
) {
    fun execute(companyId: CompanyId, reconciliationId: BankReconciliationId): CompleteBankReconciliationResult {
        companyRepository.findById(companyId) ?: return CompleteBankReconciliationResult.CompanyNotFound
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)
        val reconciliation = bankReconciliationRepository.findById(reconciliationId, companyId, postedEntries)
            ?: return CompleteBankReconciliationResult.ReconciliationNotFound

        return when (val outcome = reconciliation.complete(enforceBalanceTieOut)) {
            BankReconciliationCompletion.Completed -> {
                bankReconciliationRepository.save(reconciliation, companyId)
                CompleteBankReconciliationResult.Success(reconciliation)
            }
            BankReconciliationCompletion.NotOpen -> CompleteBankReconciliationResult.NotOpen
            is BankReconciliationCompletion.NotFullyMatched ->
                CompleteBankReconciliationResult.NotFullyMatched(outcome.unmatchedStatementLineIds)
            is BankReconciliationCompletion.BalanceDifference -> CompleteBankReconciliationResult.BalanceDifference(outcome.tieOut)
        }
    }
}
