package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.util.Currency

/**
 * Outcome of [ComputeInventoryPostingContextUseCase.execute] - mirrors
 * [PurchasePostingContextResult]'s shape, leaner: IM already stores
 * [com.theprodeogroup.fish.domain.inventory.StockItemId]'s own
 * inventory-asset/COGS account ids on its own `Item` aggregate (per
 * `docs/Ecosystem_Extraction_DDD_Design.md` Section 1.3's Option B
 * resolution), so this only ever needs to resolve what IM has no way
 * to know itself: the open Period and the contra account a goods
 * receipt credits before it's matched to a vendor invoice (the same
 * Accounts Payable control account [ComputePurchasePostingContextUseCase]
 * already resolves for POP - a receipt is still, ultimately, a
 * liability to a vendor until settled).
 */
sealed class InventoryPostingContextResult {
    data class Success(
        val periodId: PeriodId,
        val apControlAccountId: AccountId,
        val currency: Currency
    ) : InventoryPostingContextResult()
    data object CompanyNotFound : InventoryPostingContextResult()
    data object NoOpenPeriod : InventoryPostingContextResult()
    data object ApControlAccountNotConfigured : InventoryPostingContextResult()
}

class ComputeInventoryPostingContextUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository
) {
    fun execute(companyId: CompanyId): InventoryPostingContextResult {
        val company = companyRepository.findById(companyId) ?: return InventoryPostingContextResult.CompanyNotFound
        val period = periodRepository.findAllByCompany(companyId).firstOrNull { it.allowsPosting() }
            ?: return InventoryPostingContextResult.NoOpenPeriod
        val accounts = accountRepository.findAllByCompany(companyId)
        val apAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }
            ?: return InventoryPostingContextResult.ApControlAccountNotConfigured
        return InventoryPostingContextResult.Success(period.id, apAccount.id, company.baseCurrency)
    }
}
