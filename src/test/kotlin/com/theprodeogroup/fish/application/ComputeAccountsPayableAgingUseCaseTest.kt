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
private val TODAY = LocalDate.of(2026, 9, 4)

/**
 * The AP mirror of [ComputeAccountsReceivableAgingUseCaseTest] - proves
 * the bucketed breakdown [ComputeVendorBalancesUseCase]'s own
 * `totalOutstanding`-only shape can't: a charge old enough to fall
 * outside CURRENT lands in the right aging bucket.
 */
class ComputeAccountsPayableAgingUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val periodRepository = FakePeriodRepository()
    private val useCase = ComputeAccountsPayableAgingUseCase(companyRepository, accountRepository, journalEntryRepository)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Test Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        return company
    }

    private fun apAccount(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId) =
        Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Accounts Payable").also { accountRepository.save(it) }

    private fun postCharge(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId, apAccount: Account, creditorId: CreditorId, amount: String, chargeDate: LocalDate) {
        val period = Period.create(companyId, PeriodType.MONTH, chargeDate, chargeDate.plusDays(120)).also { it.open(); periodRepository.save(it) }
        val expenseAccount = Account.create(companyId, AccountType.EXPENSE, null, "5000-${creditorId.value}", "Purchases").also { accountRepository.save(it) }
        val lines = listOf(
            JournalLine(expenseAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
            JournalLine(apAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT, mapOf(DimensionType.VENDOR to creditorId.value.toString()))
        )
        val entry = JournalEntry.create(period.id, chargeDate, lines, JournalSource.INTEGRATION, "Purchase")
        entry.post()
        journalEntryRepository.save(entry)
    }

    @Test
    fun `given a charge over 90 days old and unpaid, when computed, then it lands in the OVER_90 bucket`() {
        val co = company()
        val ap = apAccount(co.id)
        val creditorId = CreditorId.generate()
        postCharge(co.id, ap, creditorId, "450.00", TODAY.minusDays(100))

        val result = useCase.execute(co.id, listOf(creditorId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeAccountsPayableAgingResult.Success>()
        val buckets = success.aging.single().buckets.associateBy { it.label }
        buckets.getValue(AgingBucketLabel.OVER_90).amount shouldBe Money(BigDecimal("450.00"), GBP)
        buckets.getValue(AgingBucketLabel.CURRENT).amount shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a creditor with no posted activity, when computed, then every bucket is zero, not omitted`() {
        val co = company()
        apAccount(co.id)
        val creditorId = CreditorId.generate()

        val result = useCase.execute(co.id, listOf(creditorId), TODAY)

        val success = result.shouldBeInstanceOf<ComputeAccountsPayableAgingResult.Success>()
        val aging = success.aging.single()
        aging.creditorId shouldBe creditorId
        aging.buckets.size shouldBe AgingBucketLabel.entries.size
        aging.buckets.forEach { it.amount shouldBe Money(BigDecimal.ZERO, GBP) }
    }

    @Test
    fun `given multiple requested creditorIds, when computed, then every one gets its own bucket breakdown`() {
        val co = company()
        val ap = apAccount(co.id)
        val currentCreditor = CreditorId.generate()
        val overdueCreditor = CreditorId.generate()
        postCharge(co.id, ap, currentCreditor, "100.00", TODAY)
        postCharge(co.id, ap, overdueCreditor, "200.00", TODAY.minusDays(45))

        val result = useCase.execute(co.id, listOf(currentCreditor, overdueCreditor), TODAY)

        val success = result.shouldBeInstanceOf<ComputeAccountsPayableAgingResult.Success>()
        success.aging.size shouldBe 2
        val currentBuckets = success.aging.first { it.creditorId == currentCreditor }.buckets.associateBy { it.label }
        val overdueBuckets = success.aging.first { it.creditorId == overdueCreditor }.buckets.associateBy { it.label }
        currentBuckets.getValue(AgingBucketLabel.CURRENT).amount shouldBe Money(BigDecimal("100.00"), GBP)
        overdueBuckets.getValue(AgingBucketLabel.DAYS_31_TO_60).amount shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given a nonexistent company, when computed, then it returns CompanyNotFound`() {
        val result = useCase.execute(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate(), listOf(CreditorId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeAccountsPayableAgingResult.CompanyNotFound>()
    }

    @Test
    fun `given a company with no AP control account configured, when computed, then it returns ApControlAccountNotConfigured`() {
        val co = company()

        val result = useCase.execute(co.id, listOf(CreditorId.generate()), TODAY)

        result.shouldBeInstanceOf<ComputeAccountsPayableAgingResult.ApControlAccountNotConfigured>()
    }
}
