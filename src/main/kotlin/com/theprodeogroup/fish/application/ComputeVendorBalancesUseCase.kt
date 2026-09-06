package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.purchasing.AccountsPayableAging
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

data class VendorBalance(val creditorId: CreditorId, val balance: Money)

sealed class ComputeVendorBalancesResult {
    data class Success(val balances: List<VendorBalance>) : ComputeVendorBalancesResult()
    data object CompanyNotFound : ComputeVendorBalancesResult()
    data object ApControlAccountNotConfigured : ComputeVendorBalancesResult()
}

/**
 * `POST /companies/{companyId}/vendor-balances` (UC-BO13 "View Cashflow
 * Position") - the AP mirror of [ComputeCustomerBalancesUseCase], closing
 * the backlog's own "no payables-by-vendor mirror" gap. Reuses
 * [AccountsPayableAging] (already built, never exposed as its own route
 * before this), the exact same way AR's own use case reuses
 * [com.theprodeogroup.fish.domain.sales.AccountsReceivableAging].
 *
 * Takes a caller-supplied [CreditorId] list rather than discovering
 * creditors itself - GL has no visibility into POP's Creditor master
 * data, only the ids POP happens to have tagged onto Ledger lines so
 * far, same constraint [ComputeCustomerBalancesUseCase] documents for
 * SOP's Customer data. Every requested id gets a balance back, including
 * zero for one with no posted activity yet.
 */
class ComputeVendorBalancesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    fun execute(companyId: CompanyId, creditorIds: List<CreditorId>, asOfDate: LocalDate = LocalDate.now()): ComputeVendorBalancesResult {
        val company = companyRepository.findById(companyId) ?: return ComputeVendorBalancesResult.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        val apAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }
            ?: return ComputeVendorBalancesResult.ApControlAccountNotConfigured

        val entries = journalEntryRepository.findAllByCompany(companyId)
        val balances = creditorIds.map { creditorId ->
            val aging = AccountsPayableAging.of(creditorId, apAccount.id, entries, asOfDate, company.baseCurrency)
            VendorBalance(creditorId, aging.totalOutstanding)
        }

        return ComputeVendorBalancesResult.Success(balances)
    }
}
