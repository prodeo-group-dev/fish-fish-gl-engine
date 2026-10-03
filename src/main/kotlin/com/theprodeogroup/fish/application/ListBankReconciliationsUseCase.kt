package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

sealed class ListBankReconciliationsResult {
    data class Success(val reconciliations: List<BankReconciliation>) : ListBankReconciliationsResult()
    data object CompanyNotFound : ListBankReconciliationsResult()
}

/**
 * `GET /companies/{companyId}/bank-reconciliations` - added 2026-10-03
 * after WEB flagged a real discovery gap: without a list, a caller could
 * only ever find a reconciliation again by already holding its id from
 * the create response, the same "no way back" problem WEB.3 already
 * fixed for School ids. Optionally narrowed to one [AccountId] - a bank
 * reconciliation is naturally scoped to one Cash/Bank Account.
 */
class ListBankReconciliationsUseCase(
    private val companyRepository: CompanyRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    fun execute(companyId: CompanyId, accountId: AccountId? = null): ListBankReconciliationsResult {
        companyRepository.findById(companyId) ?: return ListBankReconciliationsResult.CompanyNotFound
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)
        val reconciliations = bankReconciliationRepository.findAllByCompany(companyId, postedEntries, accountId)
        return ListBankReconciliationsResult.Success(reconciliations)
    }
}
