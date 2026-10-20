package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val RUN_DATE = LocalDate.of(2026, 10, 19)

/**
 * `POST /payroll/record-pay-run-journal` (Femi 2026-10-19: Payroll becomes its own service; docs/Payroll_Subledger_And_Posting_Accounts_Move2_Note.md
 * section 9 in the HR repo): the liability-first pay run journal, the disbursement and the remittance, every line to an account
 * from the Company's own chart, an unmapped line to Suspense, the pay element an opaque audit tag.
 */
class RecordPayRunJournalUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordPayRunJournalUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()
    private val period = Period.create(companyId, PeriodType.MONTH, RUN_DATE, RUN_DATE.plusDays(30)).also {
        it.open()
        periodRepository.save(it)
    }

    private fun account(code: String, type: AccountType, name: String, kind: CashBookKind? = null, owner: CompanyId = companyId): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(owner, type, classification, code, name, cashBookKind = kind).also { accountRepository.save(it) }
    }

    private val wages = account("5200", AccountType.EXPENSE, "Wages Expense")
    private val salaries = account("5300", AccountType.EXPENSE, "Salaries Expense")
    private val netPay = account("2210", AccountType.LIABILITY, "Net Pay Payable")
    private val paye = account("2220", AccountType.LIABILITY, "PAYE Payable")
    private val bank = account("1010", AccountType.ASSET, "Current Account", CashBookKind.BANK)

    private fun gbp(amount: String) = Money(BigDecimal(amount), GBP)

    private fun line(element: String, kind: PayRunJournalKind, side: TransactionSide, amount: String, accountId: AccountId? = null) =
        RecordPayRunJournalUseCase.Line(element, kind, side, gbp(amount), accountId)

    private fun request(lines: List<RecordPayRunJournalUseCase.Line>, periodId: com.theprodeogroup.fish.domain.ledger.PeriodId = period.id) =
        RecordPayRunJournalUseCase.Request(companyId, periodId, RUN_DATE, lines, "Pay run October", "RUN-2026-10")

    private fun RecordPayRunJournalResult.lines() = shouldBeInstanceOf<RecordPayRunJournalResult.Success>().journalEntry.lines

    @Test
    fun `given a liability-first pay run, when executed, then expense debits and liability credits post as one balanced entry with the element on each line`() {
        val lines = useCase.execute(
            request(
                listOf(
                    line("WAGES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "1000.00", wages.id),
                    line("SALARIES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "4000.00", salaries.id),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "4200.00", netPay.id),
                    line("PAYE_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "800.00", paye.id)
                )
            )
        ).lines()

        lines.size shouldBe 4
        lines.single { it.accountId == wages.id }.let { it.side shouldBe TransactionSide.DEBIT; it.dimensions[DimensionType.PAYROLL_ELEMENT] shouldBe "WAGES" }
        lines.single { it.accountId == paye.id }.let { it.side shouldBe TransactionSide.CREDIT; it.amount shouldBe gbp("800.00"); it.dimensions[DimensionType.PAYROLL_ELEMENT] shouldBe "PAYE_PAYABLE" }
        lines.filter { it.side == TransactionSide.DEBIT }.sumOf { it.amount.amount } shouldBe lines.filter { it.side == TransactionSide.CREDIT }.sumOf { it.amount.amount }
    }

    @Test
    fun `given the answer, then it carries the total of the debits`() {
        val result = useCase.execute(
            request(
                listOf(
                    line("SALARIES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "500.00", salaries.id),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "500.00", netPay.id)
                )
            )
        )

        result.shouldBeInstanceOf<RecordPayRunJournalResult.Success>().total shouldBe gbp("500.00")
    }

    @Test
    fun `given lines with no account, when executed, then they go to Suspense - never to a default account`() {
        val suspense = account("3910", AccountType.EQUITY, "Suspense Account")

        val lines = useCase.execute(
            request(
                listOf(
                    line("BASIC_PAY", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "300.00"),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "300.00")
                )
            )
        ).lines()

        lines.map { it.accountId } shouldBe listOf(suspense.id, suspense.id)
    }

    @Test
    fun `given an unmapped line and no Suspense account, when executed, then it is refused and nothing is posted`() {
        useCase.execute(
            request(
                listOf(
                    line("BASIC_PAY", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "300.00"),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "300.00", netPay.id)
                )
            )
        ).shouldBeInstanceOf<RecordPayRunJournalResult.SuspenseAccountNotConfigured>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given debits that do not equal credits, when executed, then it is refused as unbalanced and nothing is posted`() {
        useCase.execute(
            request(
                listOf(
                    line("WAGES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "1000.00", wages.id),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "999.00", netPay.id)
                )
            )
        ).shouldBeInstanceOf<RecordPayRunJournalResult.Unbalanced>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given no lines, a non-positive amount, a blank or over-long element, or mixed currencies, when executed, then it is invalid`() {
        useCase.execute(request(emptyList())).shouldBeInstanceOf<RecordPayRunJournalResult.InvalidLines>()
        useCase.execute(request(listOf(line("WAGES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "0.00", wages.id))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.InvalidLines>()
        useCase.execute(request(listOf(line("  ", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", wages.id), line("X", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.InvalidLines>()
        useCase.execute(request(listOf(line("E".repeat(65), PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", wages.id), line("X", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.InvalidLines>()
        useCase.execute(
            request(
                listOf(
                    line("WAGES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", wages.id),
                    RecordPayRunJournalUseCase.Line("X", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, Money(BigDecimal("10.00"), Currency.getInstance("EUR")), netPay.id)
                )
            )
        ).shouldBeInstanceOf<RecordPayRunJournalResult.InvalidLines>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a period that is not open or does not exist, when executed, then PeriodNotOpen or PeriodNotFound and nothing is posted`() {
        val closed = Period.create(companyId, PeriodType.MONTH, RUN_DATE.minusDays(60), RUN_DATE.minusDays(31)).also { periodRepository.save(it) }
        val lines = listOf(
            line("WAGES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", wages.id),
            line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id)
        )

        useCase.execute(request(lines, closed.id)).shouldBeInstanceOf<RecordPayRunJournalResult.PeriodNotOpen>()
        useCase.execute(request(lines, com.theprodeogroup.fish.domain.ledger.PeriodId.generate())).shouldBeInstanceOf<RecordPayRunJournalResult.PeriodNotFound>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given the wrong kind of account for a line, when executed, then it is refused with the reason`() {
        val ar = account("1100", AccountType.ASSET, "Accounts Receivable")
        val ap = account("2000", AccountType.LIABILITY, "Accounts Payable")
        val vat = account("2150", AccountType.LIABILITY, "VAT Control Account")
        val facility = account("2300", AccountType.LIABILITY, "Trade Finance Facility Payable")
        val revenue = account("4000", AccountType.REVENUE, "Sales Revenue")

        fun refusal(kind: PayRunJournalKind, side: TransactionSide, accountId: AccountId) =
            useCase.execute(request(listOf(line("X", kind, side, "10.00", accountId), line("Y", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id))))
                .shouldBeInstanceOf<RecordPayRunJournalResult.LineAccountNotAllowed>()

        refusal(PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, revenue.id).reason shouldBe LineAccountResolver.Reason.WRONG_TYPE
        refusal(PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, ar.id).reason shouldBe LineAccountResolver.Reason.WRONG_TYPE
        for (blocked in listOf(ap, vat, facility)) {
            useCase.execute(request(listOf(line("X", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", blocked.id), line("Y", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", wages.id))))
                .shouldBeInstanceOf<RecordPayRunJournalResult.LineAccountNotAllowed>().reason shouldBe LineAccountResolver.Reason.CONTROL_OR_MODULE_OWNED
        }
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given the accrued leave liability, when used on a liability line, then it is accepted - payroll owns it`() {
        val accruedLeave = account("2200", AccountType.LIABILITY, "Accrued Leave Liability")

        val lines = useCase.execute(
            request(
                listOf(
                    line("LEAVE_FUNDED", PayRunJournalKind.LIABILITY, TransactionSide.DEBIT, "100.00", accruedLeave.id),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "100.00", netPay.id)
                )
            )
        ).lines()

        lines.single { it.accountId == accruedLeave.id }.side shouldBe TransactionSide.DEBIT
    }

    @Test
    fun `given an account of another Company, or one that does not exist, when executed, then it is not found`() {
        val foreign = account("5200", AccountType.EXPENSE, "Wages", owner = CompanyId.generate())

        useCase.execute(request(listOf(line("W", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", foreign.id), line("Y", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.LineAccountNotFound>().accountId shouldBe foreign.id
        useCase.execute(request(listOf(line("W", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", AccountId.generate()), line("Y", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.LineAccountNotFound>()
    }

    @Test
    fun `given a disbursement, when executed, then the liability is debited and the bank book credited with the Operating cash flow tag`() {
        val lines = useCase.execute(
            request(
                listOf(
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.DEBIT, "4200.00", netPay.id),
                    line("DISBURSEMENT", PayRunJournalKind.SETTLEMENT, TransactionSide.CREDIT, "4200.00", bank.id)
                )
            )
        ).lines()

        lines.single { it.accountId == bank.id }.let {
            it.side shouldBe TransactionSide.CREDIT
            it.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe "OPERATING"
        }
    }

    @Test
    fun `given a settlement line with no account, or an account that is not a cash or bank book, when executed, then it is refused - never Suspense`() {
        account("3910", AccountType.EQUITY, "Suspense Account")
        val plainAsset = account("1150", AccountType.ASSET, "Prepayments")
        val debit = line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.DEBIT, "10.00", netPay.id)

        useCase.execute(request(listOf(debit, line("DISBURSEMENT", PayRunJournalKind.SETTLEMENT, TransactionSide.CREDIT, "10.00"))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.SettlementAccountRequired>()
        useCase.execute(request(listOf(debit, line("DISBURSEMENT", PayRunJournalKind.SETTLEMENT, TransactionSide.CREDIT, "10.00", plainAsset.id))))
            .shouldBeInstanceOf<RecordPayRunJournalResult.LineAccountNotAllowed>().reason shouldBe LineAccountResolver.Reason.NOT_A_CASH_BOOK
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given the account touched, then each is marked as having activity`() {
        useCase.execute(
            request(
                listOf(
                    line("WAGES", PayRunJournalKind.EXPENSE, TransactionSide.DEBIT, "10.00", wages.id),
                    line("NET_PAY_PAYABLE", PayRunJournalKind.LIABILITY, TransactionSide.CREDIT, "10.00", netPay.id)
                )
            )
        ).shouldBeInstanceOf<RecordPayRunJournalResult.Success>()

        accountRepository.findById(wages.id)!!.hasPostedActivity shouldBe true
        accountRepository.findById(netPay.id)!!.hasPostedActivity shouldBe true
        accountRepository.findById(salaries.id)!!.hasPostedActivity shouldBe false
    }
}
