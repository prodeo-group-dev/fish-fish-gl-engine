package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.purchasing.AccountsPayableAging
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

data class SupplierBalance(val supplierId: SupplierId, val balance: Money)

sealed class ComputeSupplierBalancesResult {
    data class Success(val balances: List<SupplierBalance>) : ComputeSupplierBalancesResult()
    data object CompanyNotFound : ComputeSupplierBalancesResult()
    data object ApControlAccountNotConfigured : ComputeSupplierBalancesResult()
}

/**
 * `POST /companies/{companyId}/supplier-balances` (UC-BO13 "View Cashflow
 * Position") - the AP mirror of [ComputeCustomerBalancesUseCase], closing
 * the backlog's own "no payables-by-supplier mirror" gap. Reuses
 * [AccountsPayableAging] (already built, never exposed as its own route
 * before this), the exact same way AR's own use case reuses
 * [com.theprodeogroup.fish.domain.sales.AccountsReceivableAging].
 *
 * Takes a caller-supplied [SupplierId] list rather than discovering
 * suppliers itself - GL has no visibility into POP's Supplier master
 * data, only the ids POP happens to have tagged onto Ledger lines so
 * far, same constraint [ComputeCustomerBalancesUseCase] documents for
 * SOP's Customer data. Every requested id gets a balance back, including
 * zero for one with no posted activity yet.
 */
class ComputeSupplierBalancesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    fun execute(companyId: CompanyId, supplierIds: List<SupplierId>, asOfDate: LocalDate = LocalDate.now()): ComputeSupplierBalancesResult {
        val company = companyRepository.findById(companyId) ?: return ComputeSupplierBalancesResult.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        val apAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }
            ?: return ComputeSupplierBalancesResult.ApControlAccountNotConfigured

        val entries = journalEntryRepository.findAllByCompany(companyId)
        val balances = supplierIds.map { supplierId ->
            val aging = AccountsPayableAging.of(supplierId, apAccount.id, entries, asOfDate, company.baseCurrency)
            SupplierBalance(supplierId, aging.totalOutstanding)
        }

        return ComputeSupplierBalancesResult.Success(balances)
    }
}
