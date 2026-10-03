package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

sealed class MatchBankReconciliationLineResult {
    data class Success(val reconciliation: com.theprodeogroup.fish.domain.ledger.BankReconciliation) : MatchBankReconciliationLineResult()
    data object CompanyNotFound : MatchBankReconciliationLineResult()
    data object ReconciliationNotFound : MatchBankReconciliationLineResult()
    data class InvalidMatch(val message: String) : MatchBankReconciliationLineResult()
}

/**
 * `POST /companies/{companyId}/bank-reconciliations/{id}/match`
 * (UC-BANKREC-02) - wraps [com.theprodeogroup.fish.domain.ledger.BankReconciliation.match]'s
 * existing validation against the now-persisted state.
 */
class MatchBankReconciliationLineUseCase(
    private val companyRepository: CompanyRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    fun execute(companyId: CompanyId, reconciliationId: BankReconciliationId, statementLineId: BankStatementLineId, journalEntryId: JournalEntryId): MatchBankReconciliationLineResult {
        companyRepository.findById(companyId) ?: return MatchBankReconciliationLineResult.CompanyNotFound
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)
        val reconciliation = bankReconciliationRepository.findById(reconciliationId, companyId, postedEntries)
            ?: return MatchBankReconciliationLineResult.ReconciliationNotFound

        val result = reconciliation.match(statementLineId, journalEntryId)
        if (!result.isValid) return MatchBankReconciliationLineResult.InvalidMatch(result.errors.joinToString("; "))

        bankReconciliationRepository.save(reconciliation, companyId)
        return MatchBankReconciliationLineResult.Success(reconciliation)
    }
}
