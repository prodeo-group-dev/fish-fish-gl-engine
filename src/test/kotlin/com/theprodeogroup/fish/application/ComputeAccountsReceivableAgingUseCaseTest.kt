package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
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
 * The bucketed counterpart to [ComputeCustomerBalancesUseCaseTest] - proves
 * what that use case's own `totalOutstanding`-only shape can't: a sale old
 * enough to fall outside CURRENT actually lands in the right aging bucket,
 * not just that *some* non-zero balance exists.
 */
class ComputeAccountsReceivableAgingUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val periodRepository = FakePeriodRepository()
    private val useCase = ComputeAccountsReceivableAgingUseCase(companyRepository, accountRepository, journalEntryRepository)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Test Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        return company
    }

    private fun arAccount(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId) =
        Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable").also { accountRepository.save(it) }

    private fun postSale(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId, arAccount: Account, customerId: CustomerId, amount: String, saleDate: LocalDate) {
        val period = Period.create(companyId, PeriodType.MONTH, saleDate, saleDate.plusDays(120)).also { it.open(); periodRepository.save(it) }
        val revenueAccount = Account.create(companyId, AccountType.REVENUE, null, "4000-${customerId.value}", "Revenue").also { accountRepository.save(it) }
        val lines = listOf(
            JournalLine(arAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customerId.value.toString())),
            JournalLine(revenueAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
        )
        val entry = JournalEntry.create(period.id, saleDate, lines, JournalSource.INTEGRATION, "Sale")
        entry.post()
        journalEntryRepository.save(entry)
    }

    @Test
    fun `given a sale over 90 days old and unpaid, when computed, then it lands in the OVER_90 bucket`() {
        val co = company()
        val ar = arAccount(co.id)
        val customerId = CustomerId.generate()
        postSale(co.id, ar, customerId, "450.00", TODAY.minusDays(100))

        val result = useCase.execute(co.id, listOf(customerId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeAccountsReceivableAgingResult.Success>()
        val buckets = success.aging.single().buckets.associateBy { it.label }
        buckets.getValue(AgingBucketLabel.OVER_90).amount shouldBe Money(BigDecimal("450.00"), GBP)
        buckets.getValue(AgingBucketLabel.CURRENT).amount shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a customer with no posted activity, when computed, then every bucket is zero, not omitted`() {
        val co = company()
        arAccount(co.id)
        val customerId = CustomerId.generate()

        val result = useCase.execute(co.id, listOf(customerId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeAccountsReceivableAgingResult.Success>()
        val aging = success.aging.single()
        aging.customerId shouldBe customerId
        aging.buckets.size shouldBe AgingBucketLabel.entries.size
        aging.buckets.forEach { it.amount shouldBe Money(BigDecimal.ZERO, GBP) }
    }

    @Test
    fun `given multiple requested customerIds, when computed, then every one gets its own bucket breakdown`() {
        val co = company()
        val ar = arAccount(co.id)
        val currentCustomer = CustomerId.generate()
        val overdueCustomer = CustomerId.generate()
        postSale(co.id, ar, currentCustomer, "100.00", TODAY)
        postSale(co.id, ar, overdueCustomer, "200.00", TODAY.minusDays(45))

        val result = useCase.execute(co.id, listOf(currentCustomer, overdueCustomer), TODAY)

        val success = result.shouldBeInstanceOf<ComputeAccountsReceivableAgingResult.Success>()
        success.aging.size shouldBe 2
        val currentBuckets = success.aging.first { it.customerId == currentCustomer }.buckets.associateBy { it.label }
        val overdueBuckets = success.aging.first { it.customerId == overdueCustomer }.buckets.associateBy { it.label }
        currentBuckets.getValue(AgingBucketLabel.CURRENT).amount shouldBe Money(BigDecimal("100.00"), GBP)
        overdueBuckets.getValue(AgingBucketLabel.DAYS_31_TO_60).amount shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given a nonexistent company, when computed, then it returns CompanyNotFound`() {
        val result = useCase.execute(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), listOf(CustomerId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeAccountsReceivableAgingResult.CompanyNotFound>()
    }

    @Test
    fun `given a company with no AR control account configured, when computed, then it returns ArControlAccountNotConfigured`() {
        val co = company()

        val result = useCase.execute(co.id, listOf(CustomerId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeAccountsReceivableAgingResult.ArControlAccountNotConfigured>()
    }
}
