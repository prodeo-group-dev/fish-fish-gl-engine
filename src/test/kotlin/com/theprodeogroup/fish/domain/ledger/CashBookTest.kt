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

/** The book of one cash or bank account (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB10). */
class CashBookTest {

    private val gbp = Currency.getInstance("GBP")
    private val companyId = CompanyId.generate()
    private val periodId = PeriodId.generate()

    private val bank = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Bank", cashBookKind = CashBookKind.BANK)
    private val sales = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
    private val rent = Account.create(companyId, AccountType.EXPENSE, null, "5000", "Rent")
    private val accounts = listOf(bank, sales, rent).associateBy { it.id }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(date: String, description: String, vararg lines: Pair<Account, Pair<TransactionSide, String>>, post: Boolean = true): JournalEntry {
        val journalLines = lines.map { (account, sideAmount) -> JournalLine(account.id, money(sideAmount.second), sideAmount.first) }
        return JournalEntry.create(periodId, LocalDate.parse(date), journalLines, JournalSource.MANUAL, description)
            .also { if (post) it.post() }
    }

    private fun received(date: String, amount: String, description: String = "Money in") =
        entry(date, description, bank to (TransactionSide.DEBIT to amount), sales to (TransactionSide.CREDIT to amount))

    private fun paid(date: String, amount: String, description: String = "Money out") =
        entry(date, description, rent to (TransactionSide.DEBIT to amount), bank to (TransactionSide.CREDIT to amount))

    private fun book(entries: List<JournalEntry>, from: String = "2026-10-01", to: String = "2026-10-31") =
        CashBook.of(bank, entries, LocalDate.parse(from), LocalDate.parse(to), gbp, accounts)

    @Test
    fun `given receipts and payments in range, then the rows follow date order with a running balance and closing equals opening plus in minus out`() {
        val book = book(listOf(paid("2026-10-12", "40.00"), received("2026-10-03", "100.00"), received("2026-10-20", "25.50")))

        book.rows.map { it.date.toString() } shouldBe listOf("2026-10-03", "2026-10-12", "2026-10-20")
        book.rows.map { it.balance } shouldBe listOf(money("100.00"), money("60.00"), money("85.50"))
        book.totalMoneyIn shouldBe money("125.50")
        book.totalMoneyOut shouldBe money("40.00")
        book.openingBalance shouldBe money("0.00")
        book.closingBalance shouldBe money("85.50")
    }

    @Test
    fun `given entries before the range, then they form the opening balance and are not listed`() {
        val book = book(listOf(received("2026-09-15", "300.00"), paid("2026-09-20", "50.00"), received("2026-10-05", "10.00")))

        book.openingBalance shouldBe money("250.00")
        book.rows.size shouldBe 1
        book.rows.single().balance shouldBe money("260.00")
        book.closingBalance shouldBe money("260.00")
    }

    @Test
    fun `given entries after the range, then they are neither listed nor in the closing balance`() {
        val book = book(listOf(received("2026-10-05", "10.00"), received("2026-11-02", "999.00")))

        book.rows.size shouldBe 1
        book.closingBalance shouldBe money("10.00")
    }

    @Test
    fun `given a draft entry, then it does not appear and does not move the balance`() {
        val draft = entry("2026-10-04", "Not posted", bank to (TransactionSide.DEBIT to "500.00"), sales to (TransactionSide.CREDIT to "500.00"), post = false)

        val book = book(listOf(draft, received("2026-10-05", "10.00")))

        book.rows.size shouldBe 1
        book.closingBalance shouldBe money("10.00")
    }

    @Test
    fun `given an entry that does not touch the account, then it is ignored`() {
        val unrelated = entry("2026-10-06", "Accrual", rent to (TransactionSide.DEBIT to "20.00"), sales to (TransactionSide.CREDIT to "20.00"))

        book(listOf(unrelated, received("2026-10-05", "10.00"))).rows.size shouldBe 1
    }

    @Test
    fun `given a receipt, then its row names the other account and carries description and source`() {
        val row = book(listOf(received("2026-10-05", "10.00", "Cash sale"))).rows.single()

        row.moneyIn shouldBe money("10.00")
        row.moneyOut shouldBe money("0.00")
        row.description shouldBe "Cash sale"
        row.source shouldBe JournalSource.MANUAL
        row.counterAccounts.map { it.code } shouldBe listOf("4000")
    }

    @Test
    fun `given an entry and its reversal, then both rows show and the balance nets to zero`() {
        val original = received("2026-10-05", "80.00")
        val reversal = original.reverse(java.time.Instant.parse("2026-10-06T10:00:00Z"))!!

        val book = book(listOf(original, reversal))

        book.rows.size shouldBe 2
        book.rows.last().reversalOfEntryId shouldBe original.id
        book.rows.first().reversedByEntryId shouldBe reversal.id
        book.rows.last().reversedByEntryId shouldBe null
        book.rows.last().moneyOut shouldBe money("80.00")
        book.closingBalance shouldBe money("0.00")
    }

    @Test
    fun `given an account with no book, then building one is refused`() {
        val plain = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Receivables")

        shouldThrow<IllegalArgumentException> {
            CashBook.of(plain, emptyList(), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), gbp, accounts)
        }
    }

    @Test
    fun `given a range that ends before it starts, then it is refused`() {
        shouldThrow<IllegalArgumentException> { book(emptyList(), from = "2026-10-31", to = "2026-10-01") }
    }
}
