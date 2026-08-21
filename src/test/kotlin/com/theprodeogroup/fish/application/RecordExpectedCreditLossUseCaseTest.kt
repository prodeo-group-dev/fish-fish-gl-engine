package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)

/**
 * IFRS 9's simplified ECL approach, reconnected to the new "no owning
 * aggregate" architecture (docs/Sales_Processing_Requirements_Specification.md
 * Section 5.2) - `Customer.assessExpectedCreditLoss()` caps its target
 * against `Customer.balance` and reads/writes `Customer.allowanceForExpectedCreditLoss`,
 * both of which only the *old* `SalesOrder.deliverLine()`/
 * `Customer.receivePayment()` pathway keeps current. Sales/collections
 * posted via the new `RecordSaleUseCase`/`RecordCollectionUseCase`
 * never touch `Customer` at all, so that capping/tracking would be
 * silently wrong for any customer using the new pathway.
 *
 * This use case caps against [AccountsReceivableAging.totalOutstanding]
 * instead - already Ledger-derived from posted `JournalLine`s tagged
 * `DimensionType.CUSTOMER`, correct regardless of which pathway posted
 * them - and takes the current allowance as a caller-supplied value,
 * the same "no persisted position, caller carries it forward" shape
 * `RecordFXRevaluationUseCase` already established for an analogous
 * problem.
 */
class RecordExpectedCreditLossUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordExpectedCreditLossUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()
    private val customerId = CustomerId.generate()
    private val arAccountId = AccountId.generate()

    private fun openPeriod(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(code: String, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, code, "Test Account")
        accountRepository.save(account)
        return account
    }

    /** A single unpaid sale [daysOverdue] before [TODAY], tagged for [customerId] - mirrors AccountsReceivableAgingTest's saleEntry(). */
    private fun saleEntry(amount: Money, daysOverdue: Long): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), TODAY.minusDays(daysOverdue),
            listOf(
                JournalLine(arAccountId, amount, TransactionSide.DEBIT, mapOf(DimensionType.CUSTOMER to customerId.value.toString())),
                JournalLine(AccountId.generate(), amount, TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }

    private fun aging(vararg sales: JournalEntry) = AccountsReceivableAging.of(customerId, arAccountId, sales.toList(), TODAY, GBP)

    private fun request(
        periodId: PeriodId,
        expenseAccountId: AccountId,
        allowanceAccountId: AccountId,
        aging: AccountsReceivableAging,
        lossRates: Map<AgingBucketLabel, BigDecimal>,
        currentAllowance: Money = Money(BigDecimal.ZERO, GBP)
    ) = RecordExpectedCreditLossUseCase.Request(
        periodId, TODAY, aging, lossRates, currentAllowance, expenseAccountId, allowanceAccountId
    )

    @Test
    fun `given a target allowance above the current one, when executed, then it debits Expense and credits Allowance for the delta`() {
        val period = openPeriod()
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(
            saleEntry(Money(BigDecimal("10000.00"), GBP), daysOverdue = 10),
            saleEntry(Money(BigDecimal("5000.00"), GBP), daysOverdue = 45)
        )
        val lossRates = mapOf(
            AgingBucketLabel.CURRENT to BigDecimal("0.01"),
            AgingBucketLabel.DAYS_31_TO_60 to BigDecimal("0.10")
        )

        val result = useCase.execute(request(period.id, expense.id, allowance.id, agingSnapshot, lossRates))

        val success = result.shouldBeInstanceOf<RecordExpectedCreditLossResult.Success>()
        success.newAllowance shouldBe Money(BigDecimal("600.00"), GBP)
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val expenseLine = success.journalEntry.lines.single { it.accountId == expense.id }
        val allowanceLine = success.journalEntry.lines.single { it.accountId == allowance.id }
        expenseLine.side.name shouldBe "DEBIT"
        expenseLine.amount shouldBe Money(BigDecimal("600.00"), GBP)
        allowanceLine.side.name shouldBe "CREDIT"
    }

    @Test
    fun `given a high loss rate, when executed, then the target is capped at total outstanding - not Customer balance`() {
        val period = openPeriod()
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        // A 125% loss rate would target 10,000.00, but total outstanding is only 8,000.00.
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("8000.00"), GBP), daysOverdue = 95))
        val lossRates = mapOf(AgingBucketLabel.OVER_90 to BigDecimal("1.25"))

        val result = useCase.execute(request(period.id, expense.id, allowance.id, agingSnapshot, lossRates))

        val success = result.shouldBeInstanceOf<RecordExpectedCreditLossResult.Success>()
        success.newAllowance shouldBe Money(BigDecimal("8000.00"), GBP)
    }

    @Test
    fun `given a target allowance below the current one, when executed, then it debits Allowance and credits Expense for the delta`() {
        val period = openPeriod()
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))
        val lossRates = mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.01"))

        val result = useCase.execute(
            request(period.id, expense.id, allowance.id, agingSnapshot, lossRates, currentAllowance = Money(BigDecimal("500.00"), GBP))
        )

        val success = result.shouldBeInstanceOf<RecordExpectedCreditLossResult.Success>()
        success.newAllowance shouldBe Money(BigDecimal("10.00"), GBP)
        val expenseLine = success.journalEntry.lines.single { it.accountId == expense.id }
        val allowanceLine = success.journalEntry.lines.single { it.accountId == allowance.id }
        allowanceLine.side.name shouldBe "DEBIT"
        allowanceLine.amount shouldBe Money(BigDecimal("490.00"), GBP)
        expenseLine.side.name shouldBe "CREDIT"
    }

    @Test
    fun `given the target equals the current allowance, when executed, then it returns NoChangeNeeded and posts nothing`() {
        val period = openPeriod()
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))
        val lossRates = mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.01"))
        val saveCallsBefore = journalEntryRepository.saveCalls.toList()

        val result = useCase.execute(
            request(period.id, expense.id, allowance.id, agingSnapshot, lossRates, currentAllowance = Money(BigDecimal("10.00"), GBP))
        )

        result.shouldBeInstanceOf<RecordExpectedCreditLossResult.NoChangeNeeded>()
        journalEntryRepository.saveCalls shouldBe saveCallsBefore
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))
        val lossRates = mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.10"))

        val result = useCase.execute(request(period.id, expense.id, allowance.id, agingSnapshot, lossRates))

        val success = result.shouldBeInstanceOf<RecordExpectedCreditLossResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain expense.id
        accountRepository.saveCalls shouldContain allowance.id
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))

        val result = useCase.execute(
            request(PeriodId.generate(), expense.id, allowance.id, agingSnapshot, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.10")))
        )

        result.shouldBeInstanceOf<RecordExpectedCreditLossResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val expense = account("7500", AccountType.EXPENSE)
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))

        val result = useCase.execute(
            request(period.id, expense.id, allowance.id, agingSnapshot, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.10")))
        )

        result.shouldBeInstanceOf<RecordExpectedCreditLossResult.PeriodNotOpen>()
    }

    @Test
    fun `given an expense Account that does not exist, when executed, then it returns ExpenseAccountNotFound`() {
        val period = openPeriod()
        val allowance = account("1250", AccountType.ASSET)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))

        val result = useCase.execute(
            request(period.id, AccountId.generate(), allowance.id, agingSnapshot, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.10")))
        )

        result.shouldBeInstanceOf<RecordExpectedCreditLossResult.ExpenseAccountNotFound>()
    }

    @Test
    fun `given an allowance Account that does not exist, when executed, then it returns AllowanceAccountNotFound`() {
        val period = openPeriod()
        val expense = account("7500", AccountType.EXPENSE)
        val agingSnapshot = aging(saleEntry(Money(BigDecimal("1000.00"), GBP), daysOverdue = 10))

        val result = useCase.execute(
            request(period.id, expense.id, AccountId.generate(), agingSnapshot, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.10")))
        )

        result.shouldBeInstanceOf<RecordExpectedCreditLossResult.AllowanceAccountNotFound>()
    }
}
