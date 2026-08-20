package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
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
private val TODAY = LocalDate.of(2026, 8, 20)

/**
 * SOP (fish-sales-order-processing) computes the amount and calls this -
 * no owning aggregate in this repo anymore, matching PostPayRunUseCase's
 * "calling system computes the number" shape but with no domain object
 * at all to load (docs/Sales_Order_Processing_DDD_Design.md Section 0).
 */
class RecordSaleUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordSaleUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()

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

    private fun request(
        periodId: PeriodId,
        arAccountId: AccountId,
        revenueAccountId: AccountId,
        amount: String = "45000.00",
        customerId: CustomerId = CustomerId.generate()
    ) = RecordSaleUseCase.Request(
        periodId, TODAY, arAccountId, revenueAccountId, Money(BigDecimal(amount), GBP), customerId,
        "Sale to SOCIETE JALLOH ALPHAJOR SARLU"
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting AR and crediting Revenue`() {
        val period = openPeriod()
        val ar = account("1200", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val customerId = CustomerId.generate()

        val result = useCase.execute(request(period.id, ar.id, revenue.id, customerId = customerId))

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val arLine = success.journalEntry.lines.single { it.accountId == ar.id }
        val revenueLine = success.journalEntry.lines.single { it.accountId == revenue.id }
        arLine.amount shouldBe Money(BigDecimal("45000.00"), GBP)
        revenueLine.amount shouldBe Money(BigDecimal("45000.00"), GBP)
        arLine.dimensions[DimensionType.CUSTOMER] shouldBe customerId.value.toString()
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val ar = account("1200", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, ar.id, revenue.id))

        val success = result.shouldBeInstanceOf<RecordSaleResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain ar.id
        accountRepository.saveCalls shouldContain revenue.id
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val ar = account("1200", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, ar.id, revenue.id, amount = "0.00"))

        result.shouldBeInstanceOf<RecordSaleResult.InvalidAmount>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val ar = account("1200", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(PeriodId.generate(), ar.id, revenue.id))

        result.shouldBeInstanceOf<RecordSaleResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val ar = account("1200", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, ar.id, revenue.id))

        result.shouldBeInstanceOf<RecordSaleResult.PeriodNotOpen>()
    }

    @Test
    fun `given an AR control Account that does not exist, when executed, then it returns ArControlAccountNotFound`() {
        val period = openPeriod()
        val revenue = account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, AccountId.generate(), revenue.id))

        result.shouldBeInstanceOf<RecordSaleResult.ArControlAccountNotFound>()
    }

    @Test
    fun `given a Revenue Account that does not exist, when executed, then it returns RevenueAccountNotFound`() {
        val period = openPeriod()
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, ar.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordSaleResult.RevenueAccountNotFound>()
    }
}
