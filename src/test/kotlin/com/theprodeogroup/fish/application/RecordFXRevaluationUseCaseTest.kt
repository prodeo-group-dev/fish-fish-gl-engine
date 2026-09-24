package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.FXRate
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val USD: Currency = Currency.getInstance("USD")
private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)

/**
 * IAS 21 period-end foreign-currency receivable revaluation
 * (docs/Sales_Processing_Requirements_Specification.md Section 6/7.1) -
 * the same target-and-delta shape `Customer.assessExpectedCreditLoss()`
 * already established: compute the new home-currency value at the
 * current rate, post only the delta against what's currently recorded.
 */
class RecordFXRevaluationUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordFXRevaluationUseCase(periodRepository, accountRepository, journalEntryRepository)

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
        receivableAccountId: AccountId,
        fxGainLossAccountId: AccountId,
        foreignCurrencyAmount: Money = Money(BigDecimal("50000.00"), USD),
        currentlyRecordedHomeValue: Money = Money(BigDecimal("39500.00"), GBP),
        currentRate: FXRate = FXRate(USD, GBP, BigDecimal("0.79"), TODAY)
    ) = RecordFXRevaluationUseCase.Request(
        periodId, TODAY, receivableAccountId, fxGainLossAccountId,
        foreignCurrencyAmount, currentlyRecordedHomeValue, currentRate
    )

    @Test
    fun `given the foreign currency strengthened, when executed, then it debits Receivable and credits FX Gain for the delta`() {
        val period = openPeriod()
        val receivable = account("1200", AccountType.ASSET)
        val fxGainLoss = account("7900", AccountType.REVENUE)

        val result = useCase.execute(
            request(
                period.id, receivable.id, fxGainLoss.id,
                currentlyRecordedHomeValue = Money(BigDecimal("39500.00"), GBP),
                currentRate = FXRate(USD, GBP, BigDecimal("0.82"), TODAY)
            )
        )

        val success = result.shouldBeInstanceOf<RecordFXRevaluationResult.Success>()
        success.delta shouldBe Money(BigDecimal("1500.00"), GBP)
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val receivableLine = success.journalEntry.lines.single { it.accountId == receivable.id }
        val fxLine = success.journalEntry.lines.single { it.accountId == fxGainLoss.id }
        receivableLine.side.name shouldBe "DEBIT"
        receivableLine.amount shouldBe Money(BigDecimal("1500.00"), GBP)
        fxLine.side.name shouldBe "CREDIT"
    }

    @Test
    fun `given the foreign currency weakened, when executed, then it debits FX Loss and credits Receivable for the delta`() {
        val period = openPeriod()
        val receivable = account("1200", AccountType.ASSET)
        val fxGainLoss = account("7900", AccountType.EXPENSE)

        val result = useCase.execute(
            request(
                period.id, receivable.id, fxGainLoss.id,
                currentlyRecordedHomeValue = Money(BigDecimal("39500.00"), GBP),
                currentRate = FXRate(USD, GBP, BigDecimal("0.75"), TODAY)
            )
        )

        val success = result.shouldBeInstanceOf<RecordFXRevaluationResult.Success>()
        success.delta shouldBe Money(BigDecimal("-2000.00"), GBP)
        val receivableLine = success.journalEntry.lines.single { it.accountId == receivable.id }
        val fxLine = success.journalEntry.lines.single { it.accountId == fxGainLoss.id }
        receivableLine.side.name shouldBe "CREDIT"
        fxLine.side.name shouldBe "DEBIT"
        fxLine.amount shouldBe Money(BigDecimal("2000.00"), GBP)
    }

    @Test
    fun `given the rate produces no change, when executed, then it returns NoChangeNeeded and posts nothing`() {
        val period = openPeriod()
        val receivable = account("1200", AccountType.ASSET)
        val fxGainLoss = account("7900", AccountType.REVENUE)
        val saveCallsBefore = journalEntryRepository.saveCalls.toList()

        val result = useCase.execute(
            request(
                period.id, receivable.id, fxGainLoss.id,
                currentlyRecordedHomeValue = Money(BigDecimal("39500.00"), GBP),
                currentRate = FXRate(USD, GBP, BigDecimal("0.79"), TODAY)
            )
        )

        result.shouldBeInstanceOf<RecordFXRevaluationResult.NoChangeNeeded>()
        journalEntryRepository.saveCalls shouldBe saveCallsBefore
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val receivable = account("1200", AccountType.ASSET)
        val fxGainLoss = account("7900", AccountType.REVENUE)

        val result = useCase.execute(
            request(period.id, receivable.id, fxGainLoss.id, currentRate = FXRate(USD, GBP, BigDecimal("0.82"), TODAY))
        )

        val success = result.shouldBeInstanceOf<RecordFXRevaluationResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain receivable.id
        accountRepository.saveCalls shouldContain fxGainLoss.id
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val receivable = account("1200", AccountType.ASSET)
        val fxGainLoss = account("7900", AccountType.REVENUE)

        val result = useCase.execute(request(PeriodId.generate(), receivable.id, fxGainLoss.id))

        result.shouldBeInstanceOf<RecordFXRevaluationResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val receivable = account("1200", AccountType.ASSET)
        val fxGainLoss = account("7900", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, receivable.id, fxGainLoss.id))

        result.shouldBeInstanceOf<RecordFXRevaluationResult.PeriodNotOpen>()
    }

    @Test
    fun `given a receivable Account that does not exist, when executed, then it returns ReceivableAccountNotFound`() {
        val period = openPeriod()
        val fxGainLoss = account("7900", AccountType.REVENUE)

        val result = useCase.execute(request(period.id, AccountId.generate(), fxGainLoss.id))

        result.shouldBeInstanceOf<RecordFXRevaluationResult.ReceivableAccountNotFound>()
    }

    @Test
    fun `given an FX gain-loss Account that does not exist, when executed, then it returns FxGainLossAccountNotFound`() {
        val period = openPeriod()
        val receivable = account("1200", AccountType.ASSET)

        val result = useCase.execute(request(period.id, receivable.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordFXRevaluationResult.FxGainLossAccountNotFound>()
    }

    @Test
    fun `given an FX gain-loss Account belonging to another Company, when executed, then it returns FxGainLossAccountNotFound`() {
        val period = openPeriod()
        val receivable = account("1200", AccountType.ASSET)
        val otherCompanysFxGainLoss = Account.create(CompanyId.generate(), AccountType.REVENUE, null, "7900", "Test Account")
        accountRepository.save(otherCompanysFxGainLoss)

        val result = useCase.execute(request(period.id, receivable.id, otherCompanysFxGainLoss.id))

        result.shouldBeInstanceOf<RecordFXRevaluationResult.FxGainLossAccountNotFound>()
    }
}
