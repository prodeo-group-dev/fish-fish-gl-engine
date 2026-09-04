package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
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
private val TODAY = LocalDate.of(2026, 9, 4)

/**
 * SOP's Returns Inwards credit note posting (FR-IF2: "Credit note: Dr
 * Sales Returns (contra revenue) / Cr Accounts Receivable") - the
 * mirror image of [RecordSaleUseCase]'s Dr AR/Cr Revenue, same "no
 * owning aggregate in this repo, calling system computes the amount"
 * shape (docs/Returns_Inwards_Requirements_Specification.md Section
 * 3.5). Deliberately posts only the credit-note/AR side, not the
 * inventory-value side of a resaleable return - that crosses into IM
 * and isn't built here (see the spec's own Section 7 build-status note).
 */
class RecordSalesReturnUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordSalesReturnUseCase(periodRepository, accountRepository, journalEntryRepository)

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
        salesReturnsAccountId: AccountId,
        arAccountId: AccountId,
        amount: String = "1200.00",
        customerId: CustomerId = CustomerId.generate()
    ) = RecordSalesReturnUseCase.Request(
        periodId, TODAY, salesReturnsAccountId, arAccountId, Money(BigDecimal(amount), GBP), customerId,
        "Credit note for RMA-0001"
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting Sales Returns and crediting AR`() {
        val period = openPeriod()
        val salesReturns = account("4900", AccountType.REVENUE)
        val ar = account("1200", AccountType.ASSET)
        val customerId = CustomerId.generate()

        val result = useCase.execute(request(period.id, salesReturns.id, ar.id, customerId = customerId))

        val success = result.shouldBeInstanceOf<RecordSalesReturnResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val salesReturnsLine = success.journalEntry.lines.single { it.accountId == salesReturns.id }
        val arLine = success.journalEntry.lines.single { it.accountId == ar.id }
        salesReturnsLine.amount shouldBe Money(BigDecimal("1200.00"), GBP)
        arLine.amount shouldBe Money(BigDecimal("1200.00"), GBP)
        arLine.dimensions[DimensionType.CUSTOMER] shouldBe customerId.value.toString()
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val salesReturns = account("4900", AccountType.REVENUE)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, salesReturns.id, ar.id))

        val success = result.shouldBeInstanceOf<RecordSalesReturnResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain salesReturns.id
        accountRepository.saveCalls shouldContain ar.id
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val salesReturns = account("4900", AccountType.REVENUE)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, salesReturns.id, ar.id, amount = "0.00"))

        result.shouldBeInstanceOf<RecordSalesReturnResult.InvalidAmount>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val salesReturns = account("4900", AccountType.REVENUE)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(PeriodId.generate(), salesReturns.id, ar.id))

        result.shouldBeInstanceOf<RecordSalesReturnResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val salesReturns = account("4900", AccountType.REVENUE)
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, salesReturns.id, ar.id))

        result.shouldBeInstanceOf<RecordSalesReturnResult.PeriodNotOpen>()
    }

    @Test
    fun `given a Sales Returns Account that does not exist, when executed, then it returns SalesReturnsAccountNotFound`() {
        val period = openPeriod()
        val ar = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, AccountId.generate(), ar.id))

        result.shouldBeInstanceOf<RecordSalesReturnResult.SalesReturnsAccountNotFound>()
    }

    @Test
    fun `given an AR control Account that does not exist, when executed, then it returns ArControlAccountNotFound`() {
        val period = openPeriod()
        val salesReturns = account("4900", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, salesReturns.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordSalesReturnResult.ArControlAccountNotFound>()
    }
}
