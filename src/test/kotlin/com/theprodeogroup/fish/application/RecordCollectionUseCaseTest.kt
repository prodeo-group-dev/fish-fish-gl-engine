package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.common.Money
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
private val TODAY = LocalDate.of(2026, 11, 18)

/**
 * SOP's `BillForCollection.collect()` is the trigger
 * (docs/Sales_Order_Processing_DDD_Design.md Section 0) - no owning
 * aggregate in this repo, same "calling system computes the number"
 * shape as `RecordSaleUseCase`.
 */
class RecordCollectionUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordCollectionUseCase(periodRepository, accountRepository, journalEntryRepository)

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
        settlementAccountId: AccountId,
        arAccountId: AccountId,
        amount: String = "50000.00",
        customerId: CustomerId = CustomerId.generate()
    ) = RecordCollectionUseCase.Request(
        periodId, TODAY, settlementAccountId, arAccountId, Money(BigDecimal(amount), GBP), customerId,
        "Bill for Collection settled - SOCIETE JALLOH ALPHAJOR SARLU"
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting settlement and crediting AR`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val ar = account("1200", AccountType.ASSET)
        val customerId = CustomerId.generate()

        val result = useCase.execute(request(period.id, cash.id, ar.id, customerId = customerId))

        val success = result.shouldBeInstanceOf<RecordCollectionResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val settlementLine = success.journalEntry.lines.single { it.accountId == cash.id }
        val arLine = success.journalEntry.lines.single { it.accountId == ar.id }
        settlementLine.amount shouldBe Money(BigDecimal("50000.00"), GBP)
        arLine.amount shouldBe Money(BigDecimal("50000.00"), GBP)
        arLine.dimensions[DimensionType.CUSTOMER] shouldBe customerId.value.toString()
    }

    @Test
    fun `given success, then the settlement line is tagged Operating per IAS 7 - collecting a trade receivable is always Operating`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cash.id, ar.id))

        val success = result.shouldBeInstanceOf<RecordCollectionResult.Success>()
        val settlementLine = success.journalEntry.lines.single { it.accountId == cash.id }
        settlementLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cash.id, ar.id))

        val success = result.shouldBeInstanceOf<RecordCollectionResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain cash.id
        accountRepository.saveCalls shouldContain ar.id
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cash.id, ar.id, amount = "0.00"))

        result.shouldBeInstanceOf<RecordCollectionResult.InvalidAmount>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val cash = account("1000", AccountType.ASSET)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(PeriodId.generate(), cash.id, ar.id))

        result.shouldBeInstanceOf<RecordCollectionResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val cash = account("1000", AccountType.ASSET)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cash.id, ar.id))

        result.shouldBeInstanceOf<RecordCollectionResult.PeriodNotOpen>()
    }

    @Test
    fun `given a settlement Account that does not exist, when executed, then it returns SettlementAccountNotFound`() {
        val period = openPeriod()
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, AccountId.generate(), ar.id))

        result.shouldBeInstanceOf<RecordCollectionResult.SettlementAccountNotFound>()
    }

    @Test
    fun `given an AR control Account that does not exist, when executed, then it returns ArControlAccountNotFound`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cash.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordCollectionResult.ArControlAccountNotFound>()
    }
}
