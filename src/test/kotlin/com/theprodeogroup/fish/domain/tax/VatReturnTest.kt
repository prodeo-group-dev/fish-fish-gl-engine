package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val EUR: Currency = Currency.getInstance("EUR")
private val JAN_FEB_2026 = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28))

/**
 * TDD proof of the actual MVP deliverable
 * (docs/IE/IE_VAT_MVP_Design.md #5): output VAT minus input VAT over a
 * filing-period date range, including the refund position (input >
 * output) the user explicitly asked to be considered - not an error
 * case, a real, expected outcome for a VAT-registered business.
 */
class VatReturnTest {

    @Test
    fun `given only output VAT postings, when computed, then netVatDue equals the output total and it is payable`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            saleEntry(vatAccountId, Money(BigDecimal("230.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            saleEntry(vatAccountId, Money(BigDecimal("135.00"), EUR), VatCategory.REDUCED, LocalDate.of(2026, 2, 1))
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal("365.00"), EUR)
        vatReturn.inputVat shouldBe Money(BigDecimal.ZERO, EUR)
        vatReturn.netVatDue shouldBe Money(BigDecimal("365.00"), EUR)
        vatReturn.direction shouldBe VatReturnDirection.PAYABLE
    }

    @Test
    fun `given only input VAT postings, when computed, then netVatDue is negative and it is reclaimable - a refund position`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            purchaseEntry(vatAccountId, Money(BigDecimal("500.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 10))
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal.ZERO, EUR)
        vatReturn.inputVat shouldBe Money(BigDecimal("500.00"), EUR)
        vatReturn.netVatDue shouldBe Money(BigDecimal("-500.00"), EUR)
        vatReturn.direction shouldBe VatReturnDirection.RECLAIMABLE
    }

    @Test
    fun `given both output and input VAT, when computed, then netVatDue is output minus input`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            saleEntry(vatAccountId, Money(BigDecimal("230.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            purchaseEntry(vatAccountId, Money(BigDecimal("90.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 2, 1))
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.netVatDue shouldBe Money(BigDecimal("140.00"), EUR)
        vatReturn.direction shouldBe VatReturnDirection.PAYABLE
    }

    @Test
    fun `given a posting dated outside the filing period, when computed, then it is excluded`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            saleEntry(vatAccountId, Money(BigDecimal("230.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            saleEntry(vatAccountId, Money(BigDecimal("999.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 3, 1))
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal("230.00"), EUR)
    }

    @Test
    fun `given a posting on an account other than the VAT Control Account, when computed, then it is excluded`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val otherAccountId = AccountId.generate()
        val entries = listOf(
            saleEntry(vatAccountId, Money(BigDecimal("230.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            saleEntry(otherAccountId, Money(BigDecimal("999.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 20))
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal("230.00"), EUR)
    }

    @Test
    fun `given an unposted entry, when computed, then it is excluded - not yet reflected in account balances`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val draft = JournalEntry.create(
            PeriodId.generate(), LocalDate.of(2026, 1, 15),
            listOf(
                JournalLine(AccountId.generate(), Money(BigDecimal("230.00"), EUR), TransactionSide.DEBIT),
                JournalLine(
                    vatAccountId, Money(BigDecimal("230.00"), EUR), TransactionSide.CREDIT,
                    mapOf(DimensionType.VAT_CATEGORY to VatCategory.STANDARD.name)
                )
            ),
            JournalSource.INTEGRATION
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, listOf(draft), EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal.ZERO, EUR)
    }

    @Test
    fun `given lines tagged with different VAT categories, when computed, then the breakdown nets each category separately`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            saleEntry(vatAccountId, Money(BigDecimal("230.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            saleEntry(vatAccountId, Money(BigDecimal("135.00"), EUR), VatCategory.REDUCED, LocalDate.of(2026, 1, 20)),
            purchaseEntry(vatAccountId, Money(BigDecimal("23.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 2, 1))
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.categoryBreakdown[VatCategory.STANDARD] shouldBe Money(BigDecimal("207.00"), EUR)
        vatReturn.categoryBreakdown[VatCategory.REDUCED] shouldBe Money(BigDecimal("135.00"), EUR)
    }

    @Test
    fun `given an untagged line on the VAT Control Account, when computed, then it still counts toward the net total`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val untagged = postedEntry(
            LocalDate.of(2026, 1, 15),
            JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), EUR), TransactionSide.DEBIT),
            JournalLine(vatAccountId, Money(BigDecimal("50.00"), EUR), TransactionSide.CREDIT)
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, listOf(untagged), EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal("50.00"), EUR)
        vatReturn.categoryBreakdown.values.fold(Money(BigDecimal.ZERO, EUR)) { a, b -> a + b } shouldBe Money(BigDecimal.ZERO, EUR)
    }

    @Test
    fun `given the computation succeeds, then it carries the given companyId and filingPeriod`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, emptyList(), EUR)

        vatReturn.companyId shouldBe companyId
        vatReturn.filingPeriod shouldBe JAN_FEB_2026
        vatReturn.vatControlAccountId shouldBe vatAccountId
    }

    // ---- VAT_DIRECTION (2026-10-15): a tag says which side of the return a line belongs to, whatever its debit/credit side.

    private fun money(amount: String) = Money(BigDecimal(amount), EUR)

    private fun tagged(accountId: AccountId, amount: String, side: TransactionSide, direction: String?, category: VatCategory = VatCategory.STANDARD): JournalLine =
        JournalLine(
            accountId, money(amount), side,
            buildMap {
                put(DimensionType.VAT_CATEGORY, category.name)
                if (direction != null) put(DimensionType.VAT_DIRECTION, direction)
            }
        )

    private fun entryOf(date: LocalDate, vatLine: JournalLine): JournalEntry =
        postedEntry(
            date,
            vatLine,
            JournalLine(
                AccountId.generate(), vatLine.amount,
                if (vatLine.side == TransactionSide.DEBIT) TransactionSide.CREDIT else TransactionSide.DEBIT
            )
        )

    @Test
    fun `given a legacy mix of tagged-by-category and untagged lines with no direction tag, then every figure is exactly as before (golden)`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val untagged = postedEntry(
            LocalDate.of(2026, 1, 15),
            JournalLine(AccountId.generate(), money("50.00"), TransactionSide.DEBIT),
            JournalLine(vatAccountId, money("50.00"), TransactionSide.CREDIT)
        )
        val entries = listOf(
            saleEntry(vatAccountId, money("230.00"), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            saleEntry(vatAccountId, money("135.00"), VatCategory.REDUCED, LocalDate.of(2026, 1, 20)),
            purchaseEntry(vatAccountId, money("23.00"), VatCategory.STANDARD, LocalDate.of(2026, 2, 1)),
            untagged
        )

        val vatReturn = VatReturn.of(companyId, JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe money("415.00")
        vatReturn.inputVat shouldBe money("23.00")
        vatReturn.netVatDue shouldBe money("392.00")
        vatReturn.direction shouldBe VatReturnDirection.PAYABLE
        vatReturn.categoryBreakdown shouldBe mapOf(VatCategory.STANDARD to money("207.00"), VatCategory.REDUCED to money("135.00"))
    }

    @Test
    fun `given a credit tagged INPUT, then it reduces input VAT and does not count as output VAT`() {
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            purchaseEntry(vatAccountId, money("200.00"), VatCategory.STANDARD, LocalDate.of(2026, 1, 10)),
            entryOf(LocalDate.of(2026, 1, 20), tagged(vatAccountId, "4.00", TransactionSide.CREDIT, "INPUT"))
        )

        val vatReturn = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.inputVat shouldBe money("196.00")
        vatReturn.outputVat shouldBe money("0.00")
        vatReturn.netVatDue shouldBe money("-196.00")
        vatReturn.categoryBreakdown[VatCategory.STANDARD] shouldBe money("-196.00")
    }

    @Test
    fun `given a debit tagged OUTPUT, then it reduces output VAT and does not count as input VAT`() {
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            saleEntry(vatAccountId, money("230.00"), VatCategory.STANDARD, LocalDate.of(2026, 1, 15)),
            entryOf(LocalDate.of(2026, 1, 25), tagged(vatAccountId, "23.00", TransactionSide.DEBIT, "OUTPUT"))
        )

        val vatReturn = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe money("207.00")
        vatReturn.inputVat shouldBe money("0.00")
        vatReturn.netVatDue shouldBe money("207.00")
    }

    @Test
    fun `given lines tagged with the direction matching their side, then they count exactly like untagged lines`() {
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            entryOf(LocalDate.of(2026, 1, 5), tagged(vatAccountId, "100.00", TransactionSide.CREDIT, "OUTPUT")),
            entryOf(LocalDate.of(2026, 1, 6), tagged(vatAccountId, "30.00", TransactionSide.DEBIT, "INPUT"))
        )

        val vatReturn = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe money("100.00")
        vatReturn.inputVat shouldBe money("30.00")
        vatReturn.netVatDue shouldBe money("70.00")
    }

    @Test
    fun `given the same postings with and without direction tags, then net VAT due and the category breakdown never change`() {
        val vatAccountId = AccountId.generate()
        fun entries(withTags: Boolean) = listOf(
            entryOf(LocalDate.of(2026, 1, 5), tagged(vatAccountId, "100.00", TransactionSide.CREDIT, if (withTags) "OUTPUT" else null)),
            entryOf(LocalDate.of(2026, 1, 6), tagged(vatAccountId, "30.00", TransactionSide.DEBIT, if (withTags) "INPUT" else null)),
            entryOf(LocalDate.of(2026, 1, 7), tagged(vatAccountId, "4.00", TransactionSide.CREDIT, if (withTags) "INPUT" else null, VatCategory.REDUCED)),
            entryOf(LocalDate.of(2026, 1, 8), tagged(vatAccountId, "9.00", TransactionSide.DEBIT, if (withTags) "OUTPUT" else null, VatCategory.REDUCED))
        )

        val plain = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries(false), EUR)
        val withTags = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries(true), EUR)

        withTags.netVatDue shouldBe plain.netVatDue
        withTags.categoryBreakdown shouldBe plain.categoryBreakdown
        withTags.direction shouldBe plain.direction
    }

    @Test
    fun `given a direction tag that is not OUTPUT or INPUT, then the line is classified by its side like an untagged one`() {
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            entryOf(LocalDate.of(2026, 1, 5), tagged(vatAccountId, "10.00", TransactionSide.CREDIT, "banana")),
            entryOf(LocalDate.of(2026, 1, 6), tagged(vatAccountId, "3.00", TransactionSide.DEBIT, "banana"))
        )

        val vatReturn = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe money("10.00")
        vatReturn.inputVat shouldBe money("3.00")
    }

    // ---- a reversed entry and its reversal net to nothing (found building GL-T-32, 2026-10-22) ----

    @Test
    fun `given a sale that was reversed, when the return is computed, then the original and its reversal cancel and the return is nil`() {
        val vatAccountId = AccountId.generate()
        val original = saleEntry(vatAccountId, Money(BigDecimal("200.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 15))
        // The reversal is dated the day it is made, so make it inside the window.
        val reversal = original.reverse(java.time.Instant.parse("2026-02-01T09:00:00Z"))!!

        val vatReturn = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, listOf(original, reversal), EUR)

        vatReturn.netVatDue shouldBe Money(BigDecimal.ZERO, EUR)
    }

    // ---- the remittance is not VAT (GL-T-30, GL-FR-TAX-9; Femi 2026-10-22) ----

    @Test
    fun `given a quarter's VAT paid to the authority in the next quarter, when the next return is computed, then the payment is not counted as input VAT`() {
        val companyId = CompanyId.generate()
        val vatAccountId = AccountId.generate()
        val q1 = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        val q2 = VatFilingPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))
        val entries = listOf(
            saleEntry(vatAccountId, Money(BigDecimal("1000.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 2, 10)),
            saleEntry(vatAccountId, Money(BigDecimal("600.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 5, 10)),
            // Q1's 1000.00 remitted on 20 April: Dr VAT control, Cr Bank, tagged SETTLEMENT.
            postedEntry(
                LocalDate.of(2026, 4, 20),
                JournalLine(vatAccountId, Money(BigDecimal("1000.00"), EUR), TransactionSide.DEBIT, mapOf(DimensionType.VAT_DIRECTION to "SETTLEMENT")),
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), EUR), TransactionSide.CREDIT)
            )
        )

        val first = VatReturn.of(companyId, q1, vatAccountId, entries, EUR)
        val second = VatReturn.of(companyId, q2, vatAccountId, entries, EUR)

        first.netVatDue shouldBe Money(BigDecimal("1000.00"), EUR)
        second.outputVat shouldBe Money(BigDecimal("600.00"), EUR)
        second.inputVat shouldBe Money(BigDecimal.ZERO, EUR)
        second.netVatDue shouldBe Money(BigDecimal("600.00"), EUR)
        second.direction shouldBe VatReturnDirection.PAYABLE
    }

    @Test
    fun `given a refund received from the authority, when the return is computed, then the refund is not counted as output VAT`() {
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            purchaseEntry(vatAccountId, Money(BigDecimal("500.00"), EUR), VatCategory.STANDARD, LocalDate.of(2026, 1, 10)),
            postedEntry(
                LocalDate.of(2026, 2, 20),
                JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), EUR), TransactionSide.DEBIT),
                JournalLine(vatAccountId, Money(BigDecimal("500.00"), EUR), TransactionSide.CREDIT, mapOf(DimensionType.VAT_DIRECTION to "SETTLEMENT"))
            )
        )

        val vatReturn = VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries, EUR)

        vatReturn.outputVat shouldBe Money(BigDecimal.ZERO, EUR)
        vatReturn.netVatDue shouldBe Money(BigDecimal("-500.00"), EUR)
    }

    @Test
    fun `given an untagged debit on the VAT control account, when the return is computed, then it is still read by its side as before`() {
        val vatAccountId = AccountId.generate()
        val entries = listOf(
            postedEntry(
                LocalDate.of(2026, 1, 20),
                JournalLine(vatAccountId, Money(BigDecimal("40.00"), EUR), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("40.00"), EUR), TransactionSide.CREDIT)
            )
        )

        VatReturn.of(CompanyId.generate(), JAN_FEB_2026, vatAccountId, entries, EUR).inputVat shouldBe Money(BigDecimal("40.00"), EUR)
    }

    private fun saleEntry(vatAccountId: AccountId, vatAmount: Money, category: VatCategory, date: LocalDate): JournalEntry =
        postedEntry(
            date,
            JournalLine(AccountId.generate(), vatAmount, TransactionSide.DEBIT),
            JournalLine(
                vatAccountId, vatAmount, TransactionSide.CREDIT,
                mapOf(DimensionType.VAT_CATEGORY to category.name)
            )
        )

    private fun purchaseEntry(vatAccountId: AccountId, vatAmount: Money, category: VatCategory, date: LocalDate): JournalEntry =
        postedEntry(
            date,
            JournalLine(
                vatAccountId, vatAmount, TransactionSide.DEBIT,
                mapOf(DimensionType.VAT_CATEGORY to category.name)
            ),
            JournalLine(AccountId.generate(), vatAmount, TransactionSide.CREDIT)
        )

    private fun postedEntry(date: LocalDate, vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(PeriodId.generate(), date, lines.toList(), JournalSource.INTEGRATION)
        entry.post()
        return entry
    }
}
