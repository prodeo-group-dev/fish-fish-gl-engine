package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankStatementLine
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate
import java.util.Currency

sealed class StartBankReconciliationResult {
    data class Success(val reconciliation: BankReconciliation) : StartBankReconciliationResult()
    data object CompanyNotFound : StartBankReconciliationResult()
    data object AccountNotFound : StartBankReconciliationResult()
    data object CurrencyMismatch : StartBankReconciliationResult()
}

/**
 * `POST /companies/{companyId}/bank-reconciliations` (UC-BANKREC-01,
 * docs/GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md
 * Section 2.2, decided 2026-10-03) - one request carries the whole
 * statement's lines, created synchronously in this one call. No
 * batch-status tracking, unlike `ImportGlBalancesUseCase`'s
 * `OpeningImportBatch` - a monthly bank statement is smaller and
 * single-purpose than a cross-domain opening-figures import, so that
 * full state machine would be more than this needs (the decision's own
 * reasoning).
 */
class StartBankReconciliationUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    /** One line of the statement as supplied by the caller - [BankStatementLine] generates its own id. */
    data class StatementLineInput(val date: LocalDate, val amount: Money, val direction: CashDirection, val description: String)

    data class Request(
        val accountId: AccountId,
        val statementDate: LocalDate,
        val statementEndingBalance: Money,
        val currency: Currency,
        val lines: List<StatementLineInput>
    )

    fun execute(companyId: CompanyId, request: Request): StartBankReconciliationResult {
        val company = companyRepository.findById(companyId) ?: return StartBankReconciliationResult.CompanyNotFound
        val account = accountRepository.findById(request.accountId)
            ?.takeIf { it.companyId == companyId }
            ?: return StartBankReconciliationResult.AccountNotFound

        if (request.currency != company.baseCurrency) return StartBankReconciliationResult.CurrencyMismatch

        val statementLines = request.lines.map { BankStatementLine(date = it.date, amount = it.amount, direction = it.direction, description = it.description) }
        val postedEntries = journalEntryRepository.findAllByCompany(companyId)

        val reconciliation = BankReconciliation.create(
            accountId = account.id,
            statementDate = request.statementDate,
            statementEndingBalance = request.statementEndingBalance,
            statementLines = statementLines,
            postedEntries = postedEntries,
            currency = request.currency
        )

        bankReconciliationRepository.save(reconciliation, companyId)
        return StartBankReconciliationResult.Success(reconciliation)
    }
}
