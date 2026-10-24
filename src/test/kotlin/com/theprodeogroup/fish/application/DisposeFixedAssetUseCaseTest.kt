package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 3)

class DisposeFixedAssetUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val useCase = DisposeFixedAssetUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)

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

    private fun asset(): FixedAsset {
        val asset = FixedAsset.create(
            companyId, "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("10000.00"), GBP), TODAY.minusYears(2), 5
        )
        fixedAssetRepository.save(asset)
        return asset
    }

    @Test
    fun `given a valid request in an Open Period, when executed, then it disposes the asset and posts a JournalEntry`() {
        val period = openPeriod()
        val asset = asset()
        val cashAccount = account("1000", AccountType.ASSET)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)
        val lossAccount = account("5510", AccountType.EXPENSE)

        val result = useCase.execute(
            DisposeFixedAssetUseCase.Request(
                asset.id, Money(BigDecimal("6000.00"), GBP), cashAccount.id, fixedAssetAccount.id,
                accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY,
                lossOnDisposalAccountId = lossAccount.id
            )
        )

        val success = result.shouldBeInstanceOf<DisposeFixedAssetResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.fixedAsset.isDisposed shouldBe true
        success.journalEntry.lines.single { it.accountId == fixedAssetAccount.id }.amount shouldBe Money(BigDecimal("10000.00"), GBP)
        success.journalEntry.lines.single { it.accountId == cashAccount.id }.amount shouldBe Money(BigDecimal("6000.00"), GBP)
    }

    @Test
    fun `given an already-disposed asset, when executed, then it returns AlreadyDisposed`() {
        val period = openPeriod()
        val asset = asset()
        val cashAccount = account("1000", AccountType.ASSET)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)
        account("3910", AccountType.EQUITY)
        val request = DisposeFixedAssetUseCase.Request(
            asset.id, Money(BigDecimal("6000.00"), GBP), cashAccount.id, fixedAssetAccount.id,
            accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
        )
        useCase.execute(request)

        val result = useCase.execute(request)

        result.shouldBeInstanceOf<DisposeFixedAssetResult.AlreadyDisposed>()
    }

    @Test
    fun `given a positive accumulated impairment loss and no accumulated impairment account supplied, when executed, then it returns AccumulatedImpairmentAccountRequired`() {
        val period = openPeriod()
        val asset = asset()
        val cashAccount = account("1000", AccountType.ASSET)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)
        val impairmentExpenseAccount = account("6200", AccountType.EXPENSE)
        val accumulatedImpairmentAccount = account("1220", AccountType.ASSET)
        asset.assessImpairment(Money(BigDecimal("7000.00"), GBP), impairmentExpenseAccount.id, accumulatedImpairmentAccount.id, period.id, TODAY)
        fixedAssetRepository.save(asset)

        val result = useCase.execute(
            DisposeFixedAssetUseCase.Request(
                asset.id, Money(BigDecimal("6000.00"), GBP), cashAccount.id, fixedAssetAccount.id,
                accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<DisposeFixedAssetResult.AccumulatedImpairmentAccountRequired>()
    }

    @Test
    fun `given a Cash Account belonging to another Company, when executed, then it returns CashAccountNotFound`() {
        val period = openPeriod()
        val asset = asset()
        val otherCompanysCash = Account.create(CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT, "1000", "Test Account")
        accountRepository.save(otherCompanysCash)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)

        val result = useCase.execute(
            DisposeFixedAssetUseCase.Request(
                asset.id, Money(BigDecimal("6000.00"), GBP), otherCompanysCash.id, fixedAssetAccount.id,
                accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<DisposeFixedAssetResult.CashAccountNotFound>()
    }

    // --- gain or loss on disposal is its own transaction type with its own chart account; none set means Suspense
    // (Femi 2026-10-18; tester finding: an 800 loss had been booked as negative Sales Revenue) ---

    private class Accounts(val cash: Account, val cost: Account, val accDep: Account)

    private fun standardAccounts() = Accounts(account("1000", AccountType.ASSET), account("1200", AccountType.ASSET), account("1210", AccountType.ASSET))

    private fun request(asset: FixedAsset, period: Period, a: Accounts, proceeds: String, gain: Account? = null, loss: Account? = null, legacySale: Account? = null) =
        DisposeFixedAssetUseCase.Request(
            asset.id, Money(BigDecimal(proceeds), GBP), a.cash.id, a.cost.id, a.accDep.id, legacySale?.id, period.id, TODAY,
            gainOnDisposalAccountId = gain?.id, lossOnDisposalAccountId = loss?.id
        )

    @Test
    fun `given a loss and a loss account, when executed, then the loss is debited to it and the legacy sale account is never touched`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val loss = account("5510", AccountType.EXPENSE)
        val revenue = account("4000", AccountType.REVENUE)

        val success = useCase.execute(request(asset, period, a, "6000.00", loss = loss, legacySale = revenue))
            .shouldBeInstanceOf<DisposeFixedAssetResult.Success>()

        success.journalEntry.lines.single { it.accountId == loss.id }.let {
            it.side shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.DEBIT
            it.amount shouldBe Money(BigDecimal("4000.00"), GBP)
        }
        success.journalEntry.lines.none { it.accountId == revenue.id } shouldBe true
    }

    @Test
    fun `given a gain and a gain account, when executed, then the gain is credited to it`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val gain = account("4930", AccountType.REVENUE)

        val success = useCase.execute(request(asset, period, a, "12000.00", gain = gain)).shouldBeInstanceOf<DisposeFixedAssetResult.Success>()

        success.journalEntry.lines.single { it.accountId == gain.id }.let {
            it.side shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.CREDIT
            it.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        }
    }

    @Test
    fun `given a loss and no account anywhere, when executed, then the loss goes to Suspense`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val suspense = account("3910", AccountType.EQUITY)

        val success = useCase.execute(request(asset, period, a, "6000.00")).shouldBeInstanceOf<DisposeFixedAssetResult.Success>()

        success.journalEntry.lines.single { it.accountId == suspense.id }.amount shouldBe Money(BigDecimal("4000.00"), GBP)
    }

    @Test
    fun `given a loss, no account and no Suspense account, when executed, then it is refused and the asset is not disposed`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()

        useCase.execute(request(asset, period, a, "6000.00")).shouldBeInstanceOf<DisposeFixedAssetResult.SuspenseAccountNotConfigured>()

        fixedAssetRepository.findById(asset.id)!!.isDisposed shouldBe false
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given proceeds equal to the carrying amount, when executed, then no gain, loss or Suspense account is needed`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()

        useCase.execute(request(asset, period, a, "10000.00")).shouldBeInstanceOf<DisposeFixedAssetResult.Success>()
    }

    @Test
    fun `given a revenue account named as the loss account, or an expense account as the gain account, when executed, then it is refused`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val revenue = account("4000", AccountType.REVENUE)
        val expense = account("5000", AccountType.EXPENSE)

        useCase.execute(request(asset, period, a, "6000.00", loss = revenue))
            .shouldBeInstanceOf<DisposeFixedAssetResult.GainLossAccountNotAllowed>().accountId shouldBe revenue.id
        useCase.execute(request(asset, period, a, "12000.00", gain = expense))
            .shouldBeInstanceOf<DisposeFixedAssetResult.GainLossAccountNotAllowed>().accountId shouldBe expense.id
        fixedAssetRepository.findById(asset.id)!!.isDisposed shouldBe false
    }

    @Test
    fun `given a loss account of another Company, when executed, then it is not found`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val foreign = Account.create(CompanyId.generate(), AccountType.EXPENSE, null, "5510", "Loss on Disposal").also { accountRepository.save(it) }

        useCase.execute(request(asset, period, a, "6000.00", loss = foreign))
            .shouldBeInstanceOf<DisposeFixedAssetResult.GainLossAccountNotFound>().accountId shouldBe foreign.id
    }

    @Test
    fun `given an impaired asset sold above its carrying amount, when executed, then the gain is measured against the carrying amount, not the net book value`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val impairmentExpense = account("5800", AccountType.EXPENSE)
        val accImpairment = account("1220", AccountType.ASSET)
        asset.assessImpairment(Money(BigDecimal("7000.00"), GBP), impairmentExpense.id, accImpairment.id, period.id, TODAY)
        val gain = account("4930", AccountType.REVENUE)

        val success = useCase.execute(request(asset, period, a, "8000.00", gain = gain).copy(accumulatedImpairmentAccountId = accImpairment.id))
            .shouldBeInstanceOf<DisposeFixedAssetResult.Success>()

        success.journalEntry.lines.single { it.accountId == gain.id }.amount shouldBe Money(BigDecimal("1000.00"), GBP)
    }

    @Test
    fun `given an impaired asset sold below its carrying amount, when executed, then the loss is measured against the carrying amount`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val impairmentExpense = account("5800", AccountType.EXPENSE)
        val accImpairment = account("1220", AccountType.ASSET)
        asset.assessImpairment(Money(BigDecimal("7000.00"), GBP), impairmentExpense.id, accImpairment.id, period.id, TODAY)
        val loss = account("5510", AccountType.EXPENSE)

        val success = useCase.execute(request(asset, period, a, "6000.00", loss = loss).copy(accumulatedImpairmentAccountId = accImpairment.id))
            .shouldBeInstanceOf<DisposeFixedAssetResult.Success>()

        success.journalEntry.lines.single { it.accountId == loss.id }.amount shouldBe Money(BigDecimal("1000.00"), GBP)
    }

    @Test
    fun `given negative proceeds, when executed, then it is refused and nothing is posted`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        account("3910", AccountType.EQUITY)

        useCase.execute(request(asset, period, a, "-1.00")).shouldBeInstanceOf<DisposeFixedAssetResult.ProceedsNegative>()

        fixedAssetRepository.findById(asset.id)!!.isDisposed shouldBe false
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given proceeds in a currency other than the asset one, when executed, then it is refused and nothing is posted`() {
        val period = openPeriod()
        val asset = asset()
        val a = standardAccounts()
        val usd = DisposeFixedAssetUseCase.Request(
            asset.id, Money(BigDecimal("6000.00"), Currency.getInstance("USD")), a.cash.id, a.cost.id, a.accDep.id, null, period.id, TODAY
        )

        useCase.execute(usd).shouldBeInstanceOf<DisposeFixedAssetResult.ProceedsCurrencyMismatch>()

        fixedAssetRepository.findById(asset.id)!!.isDisposed shouldBe false
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    // ---- FR-FA26 / FS-24 (finding F22-20, FA, 2026-10-24): the TYPE of the accounts a disposal names ----

    private fun disposal(
        asset: FixedAsset, period: Period, cash: Account, fixedAssetAccount: Account, accumulated: Account,
        gain: Account? = null, loss: Account? = null, proceeds: String = "6000.00"
    ) = DisposeFixedAssetUseCase.Request(
        asset.id, Money(BigDecimal(proceeds), GBP), cash.id, fixedAssetAccount.id, accumulated.id, null, period.id, TODAY,
        gainOnDisposalAccountId = gain?.id, lossOnDisposalAccountId = loss?.id
    )

    private fun untouched(asset: FixedAsset) {
        fixedAssetRepository.findById(asset.id)!!.isDisposed shouldBe false
        journalEntryRepository.findAllByCompany(companyId).size shouldBe 0
    }

    @Test
    fun `given the Sales revenue account named as the cash account, when executed, then it is refused as the wrong type and nothing posts`() {
        val period = openPeriod(); val asset = asset()
        val sales = account("4000", AccountType.REVENUE)

        val result = useCase.execute(disposal(asset, period, sales, account("1200", AccountType.ASSET), account("1210", AccountType.ASSET), loss = account("5510", AccountType.EXPENSE)))

        result.shouldBeInstanceOf<DisposeFixedAssetResult.CashAccountNotAllowed>().let {
            it.accountId shouldBe sales.id
            it.reason shouldBe LineAccountResolver.Reason.WRONG_TYPE
        }
        untouched(asset)
    }

    @Test
    fun `given an inactive cash account, when executed, then it is refused as inactive, and a cash book or an ordinary asset account is accepted`() {
        val period = openPeriod(); val asset = asset()
        val fixedAssetAccount = account("1200", AccountType.ASSET); val accumulated = account("1210", AccountType.ASSET)
        val loss = account("5510", AccountType.EXPENSE)
        val inactive = account("1015", AccountType.ASSET).also { it.deactivate(); accountRepository.save(it) }

        useCase.execute(disposal(asset, period, inactive, fixedAssetAccount, accumulated, loss = loss))
            .shouldBeInstanceOf<DisposeFixedAssetResult.CashAccountNotAllowed>().reason shouldBe LineAccountResolver.Reason.INACTIVE
        untouched(asset)

        val book = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = com.theprodeogroup.fish.domain.ledger.CashBookKind.BANK)
            .also { accountRepository.save(it) }
        useCase.execute(disposal(asset, period, book, fixedAssetAccount, accumulated, loss = loss)).shouldBeInstanceOf<DisposeFixedAssetResult.Success>()
    }

    @Test
    fun `given a wrong, inactive, cash-book or control account named as the fixed-asset account, when executed, then it is refused and nothing posts`() {
        val period = openPeriod(); val asset = asset()
        val cash = account("1000", AccountType.ASSET); val accumulated = account("1210", AccountType.ASSET); val loss = account("5510", AccountType.EXPENSE)
        val revenue = account("4100", AccountType.REVENUE)
        val inactive = account("1220", AccountType.ASSET).also { it.deactivate(); accountRepository.save(it) }
        val book = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = com.theprodeogroup.fish.domain.ledger.CashBookKind.BANK)
            .also { accountRepository.save(it) }
        val receivables = account("1100", AccountType.ASSET)

        val expected = listOf(
            revenue to LineAccountResolver.Reason.WRONG_TYPE, inactive to LineAccountResolver.Reason.INACTIVE,
            book to LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED, receivables to LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        )
        for ((named, reason) in expected) {
            val refused = useCase.execute(disposal(asset, period, cash, named, accumulated, loss = loss)).shouldBeInstanceOf<DisposeFixedAssetResult.FixedAssetAccountNotAllowed>()
            refused.accountId shouldBe named.id
            refused.reason shouldBe reason
        }
        untouched(asset)
    }

    @Test
    fun `given a wrong, inactive, cash-book or control account named as the accumulated depreciation account, when executed, then it is refused and nothing posts`() {
        val period = openPeriod(); val asset = asset()
        val cash = account("1000", AccountType.ASSET); val fixedAssetAccount = account("1200", AccountType.ASSET); val loss = account("5510", AccountType.EXPENSE)
        val revenue = account("4100", AccountType.REVENUE)
        val inactive = account("1220", AccountType.ASSET).also { it.deactivate(); accountRepository.save(it) }
        val inventory = account("1300", AccountType.ASSET)

        for ((named, reason) in listOf(
            revenue to LineAccountResolver.Reason.WRONG_TYPE, inactive to LineAccountResolver.Reason.INACTIVE,
            inventory to LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        )) {
            val refused = useCase.execute(disposal(asset, period, cash, fixedAssetAccount, named, loss = loss)).shouldBeInstanceOf<DisposeFixedAssetResult.AccumulatedDepreciationAccountNotAllowed>()
            refused.accountId shouldBe named.id
            refused.reason shouldBe reason
        }
        untouched(asset)
    }

    @Test
    fun `given the owner's own fixed-asset accounts, when executed, then they are accepted`() {
        val period = openPeriod(); val asset = asset()
        val cash = account("1000", AccountType.ASSET)
        val vehicles = account("1230", AccountType.ASSET); val vehiclesDepreciation = account("1231", AccountType.ASSET)

        useCase.execute(disposal(asset, period, cash, vehicles, vehiclesDepreciation, loss = account("5510", AccountType.EXPENSE))).shouldBeInstanceOf<DisposeFixedAssetResult.Success>()
    }

    @Test
    fun `given a gain, when the gain account is the Sales revenue account, then it is refused, and any other income account is accepted`() {
        val period = openPeriod(); val asset = asset()
        val cash = account("1000", AccountType.ASSET); val fixedAssetAccount = account("1200", AccountType.ASSET); val accumulated = account("1210", AccountType.ASSET)
        val sales = account("4000", AccountType.REVENUE)
        val otherIncome = account("4100", AccountType.REVENUE)

        val refused = useCase.execute(disposal(asset, period, cash, fixedAssetAccount, accumulated, gain = sales, proceeds = "12000.00"))
            .shouldBeInstanceOf<DisposeFixedAssetResult.GainLossAccountNotAllowed>()
        refused.accountId shouldBe sales.id
        refused.reason shouldBe LineAccountResolver.Reason.SALES_REVENUE_ACCOUNT
        untouched(asset)

        useCase.execute(disposal(asset, period, cash, fixedAssetAccount, accumulated, gain = otherIncome, proceeds = "12000.00")).shouldBeInstanceOf<DisposeFixedAssetResult.Success>()
    }
}
