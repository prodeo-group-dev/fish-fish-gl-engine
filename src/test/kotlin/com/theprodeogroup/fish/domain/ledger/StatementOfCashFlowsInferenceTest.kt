package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * UAT v2.2 W-L3: a cash expense and the cash of an acquisition reversal landed in "Uncategorized"
 * because manual journals carry no CASH_FLOW_ACTIVITY tag. For an untagged cash line, the activity is
 * now inferred from the other lines of the same entry - only when they all point the same way; a tag,
 * when present, still wins, and anything unclear stays Uncategorized (never silently guessed).
 */
class StatementOfCashFlowsInferenceTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val start = LocalDate.of(2026, 2, 1)
    private val end = LocalDate.of(2026, 2, 28)
    private val day = start.plusDays(5)
    private val company = CompanyId.generate()

    private fun account(type: AccountType, code: String, classification: AccountClassification? = null) =
        Account.create(company, type, classification ?: if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Account $code")

    private val cash = account(AccountType.ASSET, "1000")
    private val revenue = account(AccountType.REVENUE, "4000")
    private val expense = account(AccountType.EXPENSE, "5000")
    private val receivable = account(AccountType.ASSET, "1100")
    private val fixedAsset = account(AccountType.ASSET, "1200", AccountClassification.NON_CURRENT)
    private val payable = account(AccountType.LIABILITY, "2000")
    private val loan = account(AccountType.LIABILITY, "2100", AccountClassification.NON_CURRENT)
    private val shareCapital = account(AccountType.EQUITY, "3000")
    private val openingBalanceEquity = account(AccountType.EQUITY, ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE)
    private val suspense = account(AccountType.EQUITY, ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE)
    private val all = listOf(cash, revenue, expense, receivable, fixedAsset, payable, loan, shareCapital, openingBalanceEquity, suspense)

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(vararg lines: JournalLine) =
        JournalEntry.create(PeriodId.generate(), day, lines.toList(), JournalSource.MANUAL).also { it.post() }

    private fun dr(a: Account, amount: String, dims: Map<DimensionType, String> = emptyMap()) = JournalLine(a.id, money(amount), TransactionSide.DEBIT, dims)
    private fun cr(a: Account, amount: String, dims: Map<DimensionType, String> = emptyMap()) = JournalLine(a.id, money(amount), TransactionSide.CREDIT, dims)

    private fun statement(vararg entries: JournalEntry) = StatementOfCashFlows.of(cash, entries.toList(), start, end, gbp, all)

    private fun StatementOfCashFlows.amount(activity: CashFlowActivity) = activityAmounts.first { it.activity == activity }.netAmount

    @Test
    fun `an untagged cash expense is Operating`() {
        val s = statement(entry(dr(expense, "80.00"), cr(cash, "80.00")))

        s.amount(CashFlowActivity.OPERATING) shouldBe money("-80.00")
        s.uncategorizedAmount shouldBe money("0.00")
    }

    @Test
    fun `untagged cash sales and customer receipts are Operating`() {
        val s = statement(
            entry(dr(cash, "100.00"), cr(revenue, "100.00")),
            entry(dr(cash, "40.00"), cr(receivable, "40.00")),
            entry(dr(payable, "25.00"), cr(cash, "25.00"))
        )

        s.amount(CashFlowActivity.OPERATING) shouldBe money("115.00")
        s.uncategorizedAmount shouldBe money("0.00")
    }

    @Test
    fun `an untagged purchase of a non-current asset is Investing, and so is the cash of its reversal`() {
        val acquisition = entry(dr(fixedAsset, "5000.00"), cr(cash, "5000.00"))
        val reversal = entry(dr(cash, "5000.00"), cr(fixedAsset, "5000.00"))

        val s = statement(acquisition, reversal)

        s.amount(CashFlowActivity.INVESTING) shouldBe money("0.00") // -5000 then +5000
        s.uncategorizedAmount shouldBe money("0.00")
        statement(acquisition).amount(CashFlowActivity.INVESTING) shouldBe money("-5000.00")
        statement(reversal).amount(CashFlowActivity.INVESTING) shouldBe money("5000.00")
    }

    @Test
    fun `untagged cash from a long-term loan or from share capital is Financing`() {
        val s = statement(
            entry(dr(cash, "2000.00"), cr(loan, "2000.00")),
            entry(dr(cash, "1000.00"), cr(shareCapital, "1000.00"))
        )

        s.amount(CashFlowActivity.FINANCING) shouldBe money("3000.00")
        s.uncategorizedAmount shouldBe money("0.00")
    }

    @Test
    fun `an opening balance or suspense entry stays Uncategorized, never a flow`() {
        val s = statement(
            entry(dr(cash, "900.00"), cr(openingBalanceEquity, "900.00")),
            entry(dr(cash, "70.00"), cr(suspense, "70.00"))
        )

        s.uncategorizedAmount shouldBe money("970.00")
        s.activityAmounts.forEach { it.netAmount shouldBe money("0.00") }
    }

    @Test
    fun `counter lines that point different ways stay Uncategorized`() {
        val s = statement(entry(dr(cash, "300.00"), cr(revenue, "100.00"), cr(loan, "200.00")))

        s.uncategorizedAmount shouldBe money("300.00")
    }

    @Test
    fun `an explicit tag wins over inference`() {
        val tagged = dr(cash, "60.00", mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.FINANCING.name))
        val s = statement(entry(tagged, cr(revenue, "60.00")))

        s.amount(CashFlowActivity.FINANCING) shouldBe money("60.00")
        s.amount(CashFlowActivity.OPERATING) shouldBe money("0.00")
    }

    @Test
    fun `without the chart of accounts an untagged line stays Uncategorized, as before`() {
        val s = StatementOfCashFlows.of(cash, listOf(entry(dr(expense, "80.00"), cr(cash, "80.00"))), start, end, gbp)

        s.uncategorizedAmount shouldBe money("-80.00")
    }

    @Test
    fun `activities plus uncategorized always equal net cash flow`() {
        val s = statement(
            entry(dr(expense, "80.00"), cr(cash, "80.00")),
            entry(dr(cash, "900.00"), cr(openingBalanceEquity, "900.00")),
            entry(dr(fixedAsset, "300.00"), cr(cash, "300.00")),
            entry(dr(cash, "300.00"), cr(revenue, "100.00"), cr(loan, "200.00"))
        )

        val total = s.activityAmounts.fold(money("0.00")) { sum, a -> sum + a.netAmount } + s.uncategorizedAmount
        total shouldBe s.netCashFlow
    }
}
