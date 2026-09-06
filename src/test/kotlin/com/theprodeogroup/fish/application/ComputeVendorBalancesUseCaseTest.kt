package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 6)

/**
 * UC-BO13 "View Cashflow Position" - the AP mirror of
 * `ComputeCustomerBalancesUseCaseTest`: a real (ledger-derived)
 * per-creditor payables balance, keyed to whatever `CreditorId`s the
 * caller supplies. Reuses `AccountsPayableAging` (already built, never
 * exposed as its own route before this).
 */
class ComputeVendorBalancesUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val periodRepository = FakePeriodRepository()
    private val useCase = ComputeVendorBalancesUseCase(companyRepository, accountRepository, journalEntryRepository)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        return company
    }

    private fun apAccount(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId) =
        Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Accounts Payable").also { accountRepository.save(it) }

    private fun postCharge(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId, apAccount: Account, creditorId: CreditorId, amount: String) {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); periodRepository.save(it) }
        val expenseAccount = Account.create(companyId, AccountType.EXPENSE, null, "5000-${creditorId.value}", "Expense").also { accountRepository.save(it) }
        val lines = listOf(
            JournalLine(expenseAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
            JournalLine(apAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT, mapOf(DimensionType.VENDOR to creditorId.value.toString()))
        )
        val entry = JournalEntry.create(period.id, TODAY, lines, JournalSource.INTEGRATION, "Purchase")
        entry.post()
        journalEntryRepository.save(entry)
    }

    @Test
    fun `given a creditor with a posted charge, when computed, then it reports the real outstanding balance`() {
        val co = company()
        val ap = apAccount(co.id)
        val creditorId = CreditorId.generate()
        postCharge(co.id, ap, creditorId, "450.00")

        val result = useCase.execute(co.id, listOf(creditorId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeVendorBalancesResult.Success>()
        success.balances.single().creditorId shouldBe creditorId
        success.balances.single().balance shouldBe Money(BigDecimal("450.00"), GBP)
    }

    @Test
    fun `given a creditor with no posted activity, when computed, then it still reports a zero balance, not omitted`() {
        val co = company()
        apAccount(co.id)
        val creditorId = CreditorId.generate()

        val result = useCase.execute(co.id, listOf(creditorId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeVendorBalancesResult.Success>()
        success.balances.single().creditorId shouldBe creditorId
        success.balances.single().balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given multiple requested creditorIds, when computed, then every one gets its own balance`() {
        val co = company()
        val ap = apAccount(co.id)
        val paidOffCreditor = CreditorId.generate()
        val owedCreditor = CreditorId.generate()
        postCharge(co.id, ap, owedCreditor, "200.00")

        val result = useCase.execute(co.id, listOf(paidOffCreditor, owedCreditor), TODAY)

        val success = result.shouldBeInstanceOf<ComputeVendorBalancesResult.Success>()
        success.balances.size shouldBe 2
        success.balances.first { it.creditorId == paidOffCreditor }.balance shouldBe Money(BigDecimal.ZERO, GBP)
        success.balances.first { it.creditorId == owedCreditor }.balance shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given a nonexistent company, when computed, then it returns CompanyNotFound`() {
        val result = useCase.execute(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), listOf(CreditorId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeVendorBalancesResult.CompanyNotFound>()
    }

    @Test
    fun `given a company with no AP control account configured, when computed, then it returns ApControlAccountNotConfigured`() {
        val co = company()

        val result = useCase.execute(co.id, listOf(CreditorId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeVendorBalancesResult.ApControlAccountNotConfigured>()
    }
}
