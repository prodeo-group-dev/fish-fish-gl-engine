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
import com.theprodeogroup.fish.domain.purchasing.CreditorId
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
 * POP (fish-purchase-order-processing) computes the amount and calls
 * this once its own `PurchaseOrder` records a successful UC-PO5
 * three-way match - no owning aggregate in this repo anymore, the
 * mirror of [RecordSaleUseCaseTest].
 */
class RecordVendorObligationUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordVendorObligationUseCase(periodRepository, accountRepository, journalEntryRepository)

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
        expenseOrAssetAccountId: AccountId,
        apControlAccountId: AccountId,
        amount: String = "12500.00",
        vendorId: CreditorId = CreditorId.generate()
    ) = RecordVendorObligationUseCase.Request(
        periodId, TODAY, expenseOrAssetAccountId, apControlAccountId, Money(BigDecimal(amount), GBP), vendorId,
        "Sugar import - three-way match confirmed"
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting Expense and crediting AP Control`() {
        val period = openPeriod()
        val inventory = account("1300", AccountType.ASSET)
        val apControl = account("2100", AccountType.LIABILITY)
        val vendorId = CreditorId.generate()

        val result = useCase.execute(request(period.id, inventory.id, apControl.id, vendorId = vendorId))

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val expenseLine = success.journalEntry.lines.single { it.accountId == inventory.id }
        val apLine = success.journalEntry.lines.single { it.accountId == apControl.id }
        expenseLine.amount shouldBe Money(BigDecimal("12500.00"), GBP)
        apLine.amount shouldBe Money(BigDecimal("12500.00"), GBP)
        apLine.dimensions[DimensionType.VENDOR] shouldBe vendorId.value.toString()
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val inventory = account("1300", AccountType.ASSET)
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, inventory.id, apControl.id))

        val success = result.shouldBeInstanceOf<RecordVendorObligationResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain inventory.id
        accountRepository.saveCalls shouldContain apControl.id
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val inventory = account("1300", AccountType.ASSET)
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, inventory.id, apControl.id, amount = "0.00"))

        result.shouldBeInstanceOf<RecordVendorObligationResult.InvalidAmount>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val inventory = account("1300", AccountType.ASSET)
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(request(PeriodId.generate(), inventory.id, apControl.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val inventory = account("1300", AccountType.ASSET)
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, inventory.id, apControl.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.PeriodNotOpen>()
    }

    @Test
    fun `given an Expense or Asset Account that does not exist, when executed, then it returns ExpenseOrAssetAccountNotFound`() {
        val period = openPeriod()
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, AccountId.generate(), apControl.id))

        result.shouldBeInstanceOf<RecordVendorObligationResult.ExpenseOrAssetAccountNotFound>()
    }

    @Test
    fun `given an AP control Account that does not exist, when executed, then it returns ApControlAccountNotFound`() {
        val period = openPeriod()
        val inventory = account("1300", AccountType.ASSET)

        val result = useCase.execute(request(period.id, inventory.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordVendorObligationResult.ApControlAccountNotFound>()
    }
}
