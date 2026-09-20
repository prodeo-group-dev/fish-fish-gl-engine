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
