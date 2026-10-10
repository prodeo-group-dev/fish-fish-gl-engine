package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * `GET /companies/{companyId}/cash-books` (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB12): the Company's
 * cash and bank accounts with their balance today and the date of their last posting - the landing view
 * of the Cash and Bank section. Only active ASSET accounts carrying a cash/bank kind are listed, by code.
 * Read-only; every figure is the account's ordinary ledger balance.
 */
class ListCashBooksUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Item(val account: Account, val balance: Money, val lastEntryDate: LocalDate?, val isDefault: Boolean)

    sealed class Result {
        data class Success(val currency: Currency, val items: List<Item>) : Result()
        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val zero = Money(BigDecimal.ZERO, company.baseCurrency)

        val items = accountRepository.findAllByCompany(companyId)
            .filter { it.active && it.cashBookKind != null }
            .sortedBy { it.code }
            .map { account ->
                val entries = journalEntryRepository.findAllByAccount(account.id)
                    .filter { it.status.hasHistoricalEffect() }
                val balance = entries.flatMap { it.lines }.filter { it.accountId == account.id }.fold(zero) { sum, line ->
                    if (line.side == TransactionSide.DEBIT) sum + line.amount else sum - line.amount
                }
                Item(account, balance, entries.maxOfOrNull { it.date }, isDefault = account.code == DEFAULT_CASH_BOOK_CODE)
            }
        return Result.Success(company.baseCurrency, items)
    }

    companion object {
        /** The Cash Book every Company has and the services default to (SRS decision D4). */
        const val DEFAULT_CASH_BOOK_CODE = "1000"
    }
}
