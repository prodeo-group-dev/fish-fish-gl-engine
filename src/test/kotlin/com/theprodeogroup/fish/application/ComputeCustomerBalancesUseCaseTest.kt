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
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 4)

/**
 * A real (ledger-derived) per-customer AR balance, keyed to whatever
 * `CustomerId`s the caller supplies - "some customers can have a
 * balance of zero" (2026-09-04) means this must return every requested
 * id, not just the ones with posted activity. Reuses `AccountsReceivableAging`
 * (already built, never exposed as its own route before this) rather
 * than inventing new aging math.
 */
class ComputeCustomerBalancesUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val periodRepository = FakePeriodRepository()
    private val useCase = ComputeCustomerBalancesUseCase(companyRepository, accountRepository, journalEntryRepository)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        return company
    }

    private fun arAccount(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId) =
        Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }

    private fun postSale(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId, arAccount: Account, customerId: CustomerId, amount: String) {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30)).also { it.open(); periodRepository.save(it) }
        val revenueAccount = Account.create(companyId, AccountType.REVENUE, null, "4000-${customerId.value}", "Revenue").also { accountRepository.save(it) }
        val lines = listOf(
            JournalLine(arAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customerId.value.toString())),
            JournalLine(revenueAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
        )
        val entry = JournalEntry.create(period.id, TODAY, lines, JournalSource.INTEGRATION, "Sale")
        entry.post()
        journalEntryRepository.save(entry)
    }

    @Test
    fun `given a customer with a posted sale, when computed, then it reports the real outstanding balance`() {
        val co = company()
        val ar = arAccount(co.id)
        val customerId = CustomerId.generate()
        postSale(co.id, ar, customerId, "450.00")

        val result = useCase.execute(co.id, listOf(customerId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeCustomerBalancesResult.Success>()
        success.balances.single().customerId shouldBe customerId
        success.balances.single().balance shouldBe Money(BigDecimal("450.00"), GBP)
    }

    @Test
    fun `given a customer with no posted activity, when computed, then it still reports a zero balance, not omitted`() {
        val co = company()
        arAccount(co.id)
        val customerId = CustomerId.generate()

        val result = useCase.execute(co.id, listOf(customerId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeCustomerBalancesResult.Success>()
        success.balances.single().customerId shouldBe customerId
        success.balances.single().balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given multiple requested customerIds, when computed, then every one gets its own balance`() {
        val co = company()
        val ar = arAccount(co.id)
        val paidCustomer = CustomerId.generate()
        val unpaidCustomer = CustomerId.generate()
        postSale(co.id, ar, unpaidCustomer, "200.00")

        val result = useCase.execute(co.id, listOf(paidCustomer, unpaidCustomer), TODAY)

        val success = result.shouldBeInstanceOf<ComputeCustomerBalancesResult.Success>()
        success.balances.size shouldBe 2
        success.balances.first { it.customerId == paidCustomer }.balance shouldBe Money(BigDecimal.ZERO, GBP)
        success.balances.first { it.customerId == unpaidCustomer }.balance shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given a nonexistent company, when computed, then it returns CompanyNotFound`() {
        val result = useCase.execute(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), listOf(CustomerId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeCustomerBalancesResult.CompanyNotFound>()
    }

    @Test
    fun `given a company with no AR control account configured, when computed, then it returns ArControlAccountNotConfigured`() {
        val co = company()

        val result = useCase.execute(co.id, listOf(CustomerId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeCustomerBalancesResult.ArControlAccountNotConfigured>()
    }
}
