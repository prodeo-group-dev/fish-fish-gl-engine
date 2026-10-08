package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val DAY = LocalDate.of(2026, 1, 15)

/**
 * The Trial Balance as the Ledger's test of correctness (Femi, 2026-10-08):
 * it must show a debit column and a credit column that agree, and its
 * figures must tie to the Balance Sheet and Profit and Loss built from the
 * same postings.
 */
class TrialBalanceProofTest {

    private val companyId = CompanyId.generate()
    private val period = PeriodId.generate()

    private fun account(type: AccountType, code: String) = Account.create(
        companyId, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Account $code"
    )

    private fun money(amount: String) = Money(BigDecimal(amount), GBP)

    private fun posted(vararg lines: JournalLine): JournalEntry =
        JournalEntry.create(period, DAY, lines.toList(), JournalSource.MANUAL).also { it.post() }

    private fun dr(a: Account, amount: String) = JournalLine(a.id, money(amount), TransactionSide.DEBIT)
    private fun cr(a: Account, amount: String) = JournalLine(a.id, money(amount), TransactionSide.CREDIT)

    private val cash = account(AccountType.ASSET, "1000")
    private val receivable = account(AccountType.ASSET, "1100")
    private val payable = account(AccountType.LIABILITY, "2000")
    private val capital = account(AccountType.EQUITY, "3000")
    private val sales = account(AccountType.REVENUE, "4000")
    private val rent = account(AccountType.EXPENSE, "6000")
    private val accounts = listOf(cash, receivable, payable, capital, sales, rent)

    private val entries = listOf(
        posted(dr(cash, "5000.00"), cr(capital, "5000.00")),
        posted(dr(receivable, "1200.00"), cr(sales, "1200.00")),
        posted(dr(cash, "700.00"), cr(receivable, "700.00")),
        posted(dr(rent, "300.00"), cr(payable, "300.00")),
        posted(dr(payable, "100.00"), cr(cash, "100.00"))
    )

    @Test
    fun `total debits equal total credits across a spread of postings`() {
        val tb = TrialBalance.of(accounts, entries, GBP)

        tb.totalDebits shouldBe tb.totalCredits
        tb.totalDebits shouldBe money("6400.00") // cash 5600 + receivable 500 + rent 300
        tb.isBalanced shouldBe true
    }

    @Test
    fun `each account sits in the column of its normal side, and in the other column when it runs the wrong way`() {
        val overdrawn = posted(cr(cash, "9000.00"), dr(capital, "9000.00"))
        val tb = TrialBalance.of(accounts, entries + overdrawn, GBP)

        val cashLine = tb.lines.first { it.accountId == cash.id }
        cashLine.balance shouldBe money("-3400.00")
        cashLine.debit shouldBe money("0.00")
        cashLine.credit shouldBe money("3400.00")

        val salesLine = tb.lines.first { it.accountId == sales.id }
        salesLine.debit shouldBe money("0.00")
        salesLine.credit shouldBe money("1200.00")

        tb.totalDebits shouldBe tb.totalCredits
    }

    @Test
    fun `a posted entry and its reversal leave the trial balance unchanged`() {
        val original = posted(dr(rent, "50.00"), cr(cash, "50.00"))
        val reversal = requireNotNull(original.reverse())

        val without = TrialBalance.of(accounts, entries, GBP)
        val with = TrialBalance.of(accounts, entries + original + reversal, GBP)

        with.lines.map { it.balance } shouldBe without.lines.map { it.balance }
        with.totalDebits shouldBe with.totalCredits
    }

    @Test
    fun `its lines tie to the balance sheet and the profit and loss for the same postings`() {
        val tb = TrialBalance.of(accounts, entries, GBP)
        val bs = BalanceSheet.of(accounts, entries, GBP)
        val pl = ProfitAndLoss.of(accounts, entries, period, GBP)

        fun total(type: AccountType) = tb.lines.filter { it.accountType == type }
            .fold(money("0.00")) { sum, line -> sum + line.balance }

        total(AccountType.ASSET) shouldBe bs.totalAssets
        total(AccountType.LIABILITY) shouldBe bs.totalLiabilities
        total(AccountType.EQUITY) + bs.retainedEarnings shouldBe bs.totalEquity
        total(AccountType.REVENUE) shouldBe pl.totalRevenue
        total(AccountType.EXPENSE) shouldBe pl.totalExpense
        total(AccountType.REVENUE) - total(AccountType.EXPENSE) shouldBe pl.netIncome
        // Assets = Liabilities + Equity (the balance sheet's own identity), reached from the trial balance's lines.
        total(AccountType.ASSET) shouldBe total(AccountType.LIABILITY) + total(AccountType.EQUITY) + pl.netIncome
    }
}
