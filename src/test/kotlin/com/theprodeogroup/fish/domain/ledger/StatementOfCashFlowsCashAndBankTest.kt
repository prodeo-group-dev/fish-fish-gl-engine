package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * IAS 7 treats cash AND bank (cash and cash equivalents) as one pool (docs/GL_Cash_And_Bank_Books_SRS.md,
 * FR-CB40). Before this, the statement read the single account coded 1000, so a bank account's activity was
 * silently missing from cash flow.
 */
class StatementOfCashFlowsCashAndBankTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val start = LocalDate.of(2026, 10, 1)
    private val end = LocalDate.of(2026, 10, 31)
    private val day = start.plusDays(5)
    private val company = CompanyId.generate()

    private fun account(type: AccountType, code: String, kind: CashBookKind? = null, classification: AccountClassification? = null) =
        Account.create(
            company, type, classification ?: if (type.requiresClassification()) AccountClassification.CURRENT else null,
            code, "Account $code", cashBookKind = kind
        )

    private val cash = account(AccountType.ASSET, "1000", CashBookKind.CASH)
    private val bank = account(AccountType.ASSET, "1010", CashBookKind.BANK)
    private val revenue = account(AccountType.REVENUE, "4000")
    private val expense = account(AccountType.EXPENSE, "5000")
    private val fixedAsset = account(AccountType.ASSET, "1200", classification = AccountClassification.NON_CURRENT)
    private val all = listOf(cash, bank, revenue, expense, fixedAsset)

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(date: LocalDate = day, vararg lines: JournalLine) =
        JournalEntry.create(PeriodId.generate(), date, lines.toList(), JournalSource.MANUAL).also { it.post() }

    private fun dr(a: Account, amount: String) = JournalLine(a.id, money(amount), TransactionSide.DEBIT)
    private fun cr(a: Account, amount: String) = JournalLine(a.id, money(amount), TransactionSide.CREDIT)

    private fun statement(entries: List<JournalEntry>, accounts: List<Account> = listOf(cash, bank)) =
        StatementOfCashFlows.of(accounts, entries, start, end, gbp, all)

    private fun StatementOfCashFlows.amount(activity: CashFlowActivity) = activityAmounts.first { it.activity == activity }.netAmount

    @Test
    fun `given receipts into cash and into a bank account, then opening, closing and activities sum across both`() {
        val s = statement(
            listOf(
                entry(start.minusDays(3), dr(cash, "100.00"), cr(revenue, "100.00")),
                entry(start.minusDays(2), dr(bank, "400.00"), cr(revenue, "400.00")),
                entry(day, dr(cash, "30.00"), cr(revenue, "30.00")),
                entry(day, dr(bank, "70.00"), cr(revenue, "70.00")),
                entry(day, dr(expense, "25.00"), cr(bank, "25.00"))
            )
        )

        s.openingBalance shouldBe money("500.00")
        s.closingBalance shouldBe money("575.00")
        s.netCashFlow shouldBe money("75.00")
        s.amount(CashFlowActivity.OPERATING) shouldBe money("75.00")
        s.uncategorizedAmount shouldBe money("0.00")
    }

    @Test
    fun `given a transfer between cash and bank, then it is not a cash flow at all`() {
        val s = statement(
            listOf(
                entry(start.minusDays(1), dr(cash, "200.00"), cr(revenue, "200.00")),
                entry(day, dr(bank, "150.00"), cr(cash, "150.00"))
            )
        )

        s.openingBalance shouldBe money("200.00")
        s.closingBalance shouldBe money("200.00")
        s.netCashFlow shouldBe money("0.00")
        CashFlowActivity.entries.forEach { s.amount(it) shouldBe money("0.00") }
        s.uncategorizedAmount shouldBe money("0.00")
    }

    @Test
    fun `given a purchase of a fixed asset paid from the bank, then it is Investing and the cash account is untouched`() {
        val s = statement(listOf(entry(day, dr(fixedAsset, "900.00"), cr(bank, "900.00"))))

        s.amount(CashFlowActivity.INVESTING) shouldBe money("-900.00")
        s.netCashFlow shouldBe money("-900.00")
    }

    @Test
    fun `given a bank account that is not in the pool, then its entries are not counted`() {
        val s = statement(listOf(entry(day, dr(bank, "70.00"), cr(revenue, "70.00"))), accounts = listOf(cash))

        s.closingBalance shouldBe money("0.00")
        s.netCashFlow shouldBe money("0.00")
    }

    @Test
    fun `given one cash account in a list, then the result equals the single-account overload`() {
        val entries = listOf(
            entry(start.minusDays(1), dr(cash, "100.00"), cr(revenue, "100.00")),
            entry(day, dr(expense, "40.00"), cr(cash, "40.00"))
        )

        val many = StatementOfCashFlows.of(listOf(cash), entries, start, end, gbp, all)
        val single = StatementOfCashFlows.of(cash, entries, start, end, gbp, all)

        many.openingBalance shouldBe single.openingBalance
        many.closingBalance shouldBe single.closingBalance
        many.activityAmounts shouldBe single.activityAmounts
        many.uncategorizedAmount shouldBe single.uncategorizedAmount
    }

    @Test
    fun `given an empty pool, then building the statement is refused`() {
        shouldThrow<IllegalArgumentException> { statement(emptyList(), accounts = emptyList()) }
    }
}
