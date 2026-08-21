package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
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
 * POP's `SupplierPayment` (UC-PO6) calls this once a payment is
 * confirmed, having already resolved which account to settle from
 * (Prodeo's own Cash/Bank, or a facility/financing-liability account)
 * per its own `DealRole`/`ExecutingParty` - the mirror of
 * [RecordCollectionUseCaseTest].
 */
class RecordVendorPaymentUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordVendorPaymentUseCase(periodRepository, accountRepository, journalEntryRepository)

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
        apControlAccountId: AccountId,
        settlementAccountId: AccountId,
        amount: String = "12500.00",
        vendorId: CreditorId = CreditorId.generate()
    ) = RecordVendorPaymentUseCase.Request(
        periodId, TODAY, apControlAccountId, settlementAccountId, Money(BigDecimal(amount), GBP), vendorId,
        "Payment to Brazilian sugar exporter via Access Bank UK"
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting AP Control and crediting the settlement account`() {
        val period = openPeriod()
        val apControl = account("2100", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val vendorId = CreditorId.generate()

        val result = useCase.execute(request(period.id, apControl.id, cash.id, vendorId = vendorId))

        val success = result.shouldBeInstanceOf<RecordVendorPaymentResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val apLine = success.journalEntry.lines.single { it.accountId == apControl.id }
        val settlementLine = success.journalEntry.lines.single { it.accountId == cash.id }
        apLine.amount shouldBe Money(BigDecimal("12500.00"), GBP)
        settlementLine.amount shouldBe Money(BigDecimal("12500.00"), GBP)
        apLine.dimensions[DimensionType.VENDOR] shouldBe vendorId.value.toString()
        settlementLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val apControl = account("2100", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, apControl.id, cash.id))

        val success = result.shouldBeInstanceOf<RecordVendorPaymentResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain apControl.id
        accountRepository.saveCalls shouldContain cash.id
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val apControl = account("2100", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, apControl.id, cash.id, amount = "0.00"))

        result.shouldBeInstanceOf<RecordVendorPaymentResult.InvalidAmount>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val apControl = account("2100", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(PeriodId.generate(), apControl.id, cash.id))

        result.shouldBeInstanceOf<RecordVendorPaymentResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val apControl = account("2100", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, apControl.id, cash.id))

        result.shouldBeInstanceOf<RecordVendorPaymentResult.PeriodNotOpen>()
    }

    @Test
    fun `given an AP control Account that does not exist, when executed, then it returns ApControlAccountNotFound`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, AccountId.generate(), cash.id))

        result.shouldBeInstanceOf<RecordVendorPaymentResult.ApControlAccountNotFound>()
    }

    @Test
    fun `given a settlement Account that does not exist, when executed, then it returns SettlementAccountNotFound`() {
        val period = openPeriod()
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(request(period.id, apControl.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordVendorPaymentResult.SettlementAccountNotFound>()
    }
}
