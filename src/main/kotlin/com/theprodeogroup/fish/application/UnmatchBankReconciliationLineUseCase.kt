package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliationStatus
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

sealed class UnmatchBankReconciliationLineResult {
    data class Success(val reconciliation: com.theprodeogroup.fish.domain.ledger.BankReconciliation) : UnmatchBankReconciliationLineResult()
    data object CompanyNotFound : UnmatchBankReconciliationLineResult()
    data object ReconciliationNotFound : UnmatchBankReconciliationLineResult()
    /** The reconciliation is COMPLETED or CANCELLED, so it no longer accepts changes. */
    data object NotOpen : UnmatchBankReconciliationLineResult()
    data class InvalidUnmatch(val message: String) : UnmatchBankReconciliationLineResult()
}

/**
 * `POST /companies/{companyId}/bank-reconciliations/{id}/unmatch`
 * (UC-BANKREC-04, FR-BANKREC-04, decided 2026-10-03) - deletes the
 * persisted pairing outright via
 * [com.theprodeogroup.fish.domain.ledger.BankReconciliation.unmatch],
 * not a correcting event appended alongside it.
 */
class UnmatchBankReconciliationLineUseCase(
    private val companyRepository: CompanyRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    fun execute(companyId: CompanyId, reconciliationId: BankReconciliationId, statementLineId: BankStatementLineId, journalEntryId: JournalEntryId): UnmatchBankReconciliationLineResult {
        companyRepository.findById(companyId) ?: return UnmatchBankReconciliationLineResult.CompanyNotFound
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)
        val reconciliation = bankReconciliationRepository.findById(reconciliationId, companyId, postedEntries)
            ?: return UnmatchBankReconciliationLineResult.ReconciliationNotFound

        if (reconciliation.status != BankReconciliationStatus.OPEN) return UnmatchBankReconciliationLineResult.NotOpen
        val result = reconciliation.unmatch(statementLineId, journalEntryId)
        if (!result.isValid) return UnmatchBankReconciliationLineResult.InvalidUnmatch(result.errors.joinToString("; "))

        bankReconciliationRepository.save(reconciliation, companyId)
        return UnmatchBankReconciliationLineResult.Success(reconciliation)
    }
}
