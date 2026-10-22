package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.EntryProvenance
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.SourceReference
import com.theprodeogroup.fish.domain.ledger.SourceReferenceType
import com.theprodeogroup.fish.domain.tax.VatFilingPeriod
import com.theprodeogroup.fish.domain.tax.VatReturn
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * The VAT lines read (GL-T-32, GL-FR-TAX-10; Tax T-TS-13): the posted lines on the VAT Control Account in a window, as RAW facts (the line's own
 * direction and category tags, possibly absent; side; amount) so Tax classifies them by the one rule `VatReturn.of` applies. Posted or reversed
 * entries only, cursor-paged in a total order.
 */
class ComputeVatLinesUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private var now: java.time.Instant = java.time.Instant.parse("2026-12-31T23:00:00Z")
    private val useCase = ComputeVatLinesUseCase(companies, accounts, entries) { now }

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)).also { it.open(); periods.save(it) }
    private val vat = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2150", "VAT Control Account").also { accounts.save(it) }
    private val bank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank").also { accounts.save(it) }
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)
    private fun id(n: Int) = JournalEntryId(java.util.UUID.fromString("00000000-0000-4000-8000-%012d".format(n)))

    private fun tagged(direction: String?, category: String?): Map<DimensionType, String> =
        listOfNotNull(direction?.let { DimensionType.VAT_DIRECTION to it }, category?.let { DimensionType.VAT_CATEGORY to it }).toMap()

    /** An entry with ONE line on the VAT account (the VAT [side]) and its balancing line on the bank; stored posted, with a recorded order. */
    private fun entry(
        n: Int, date: String, side: TransactionSide, amount: String, direction: String? = null, category: String? = null,
        reversed: Boolean = false, post: Boolean = true, provenance: EntryProvenance? = null, seq: Long = n.toLong(),
        recordedAt: java.time.Instant? = null
    ): JournalEntry {
        val other = if (side == TransactionSide.CREDIT) TransactionSide.DEBIT else TransactionSide.CREDIT
        val created = JournalEntry.create(
            period.id, LocalDate.parse(date),
            listOf(
                JournalLine(vat.id, money(amount), side, tagged(direction, category)),
                JournalLine(bank.id, money(amount), other)
            ),
            JournalSource.INTEGRATION, "entry $n", id(n)
        )
        if (post) created.post()
        if (reversed) created.reverse()
        val stored = JournalEntry.reconstitute(
            created.id, created.periodId, created.date, created.lines, created.source, created.description, created.status,
            created.reversalOfEntryId, recordedSeq = seq, recordedAt = recordedAt, provenance = provenance
        )
        entries.save(stored)
        return stored
    }

    private fun request(from: String = "2026-10-01", to: String = "2026-12-31", limit: Int = 500, cursor: String? = null) =
        ComputeVatLinesUseCase.Request(company.id, LocalDate.parse(from), LocalDate.parse(to), limit, cursor)

    private fun success(result: ComputeVatLinesUseCase.Result) = result.shouldBeInstanceOf<ComputeVatLinesUseCase.Result.Success>()

    @Test
    fun `given a sale, a purchase, a settlement and an untagged manual debit, then each line is returned with its raw tags and side`() {
        entry(1, "2026-10-12", TransactionSide.CREDIT, "200.00", "OUTPUT", "STANDARD", provenance = EntryProvenance(null, null, null, SourceReference(SourceReferenceType.SALES_INVOICE, "sale-1", "INV-1"), null))
        entry(2, "2026-10-13", TransactionSide.DEBIT, "50.00", "INPUT", "REDUCED")
        entry(3, "2026-11-20", TransactionSide.DEBIT, "150.00", "SETTLEMENT", null)
        entry(4, "2026-11-21", TransactionSide.DEBIT, "10.00", null, null)

        val result = success(useCase.execute(request()))

        result.lines.map { listOf(it.entryId, it.date.toString(), it.side.name, it.amount.amount.toPlainString(), it.vatDirection, it.vatCategory) } shouldBe listOf(
            listOf(id(1), "2026-10-12", "CREDIT", "200.00", "OUTPUT", "STANDARD"),
            listOf(id(2), "2026-10-13", "DEBIT", "50.00", "INPUT", "REDUCED"),
            listOf(id(3), "2026-11-20", "DEBIT", "150.00", "SETTLEMENT", null),
            listOf(id(4), "2026-11-21", "DEBIT", "10.00", null, null)
        )
        result.lines.first().sourceReference shouldBe SourceReference(SourceReferenceType.SALES_INVOICE, "sale-1", "INV-1")
        result.lines[1].sourceReference shouldBe null
        result.vatControlAccountId shouldBe vat.id
        result.currency shouldBe gbp
        result.nextCursor shouldBe null
    }

    @Test
    fun `given entries on and either side of the window, then the window is inclusive of both dates`() {
        entry(1, "2026-09-30", TransactionSide.CREDIT, "1.00", "OUTPUT", "STANDARD")
        entry(2, "2026-10-01", TransactionSide.CREDIT, "2.00", "OUTPUT", "STANDARD")
        entry(3, "2026-12-31", TransactionSide.CREDIT, "3.00", "OUTPUT", "STANDARD")
        entry(4, "2027-01-01", TransactionSide.CREDIT, "4.00", "OUTPUT", "STANDARD")

        success(useCase.execute(request("2026-10-01", "2026-12-31"))).lines.map { it.amount.amount.toPlainString() } shouldBe listOf("2.00", "3.00")
    }

    @Test
    fun `given a reversed entry and its reversal, then both are returned, and a draft is not`() {
        val original = entry(1, "2026-10-05", TransactionSide.CREDIT, "100.00", "OUTPUT", "STANDARD", reversed = true)
        entry(2, "2026-10-06", TransactionSide.DEBIT, "100.00", "OUTPUT", "STANDARD")
        entry(3, "2026-10-07", TransactionSide.CREDIT, "999.00", "OUTPUT", "STANDARD", post = false)

        val result = success(useCase.execute(request()))

        original.status.name shouldBe "REVERSED"
        result.lines.map { it.entryId to it.entryStatus } shouldBe listOf(id(1) to "REVERSED", id(2) to "POSTED")
    }

    @Test
    fun `given an entry with lines on other accounts too, then only the lines on the VAT control account are returned`() {
        entry(1, "2026-10-05", TransactionSide.CREDIT, "20.00", "OUTPUT", "STANDARD")

        val result = success(useCase.execute(request()))

        result.lines.size shouldBe 1
        result.lines.single().amount shouldBe money("20.00")
    }

    @Test
    fun `given a Company with no VAT control account, an unknown Company or a backwards window, then each is refused`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }

        useCase.execute(request().copy(companyId = other.id)) shouldBe ComputeVatLinesUseCase.Result.VatControlAccountNotConfigured
        useCase.execute(request().copy(companyId = CompanyId.generate())) shouldBe ComputeVatLinesUseCase.Result.CompanyNotFound
        useCase.execute(request("2026-12-31", "2026-10-01")) shouldBe ComputeVatLinesUseCase.Result.InvalidRange
    }

    @Test
    fun `given seven lines and a limit of three, then three pages of 3, 3 and 1 are returned in one total order with no line twice or missed`() {
        // Two lines share a date and one shares date and recorded order's neighbour; the order is (date, recorded order, entry id, line index).
        entry(5, "2026-10-10", TransactionSide.CREDIT, "5.00", "OUTPUT", "STANDARD", seq = 5)
        entry(1, "2026-10-01", TransactionSide.CREDIT, "1.00", "OUTPUT", "STANDARD", seq = 1)
        entry(3, "2026-10-05", TransactionSide.CREDIT, "3.00", "OUTPUT", "STANDARD", seq = 3)
        entry(2, "2026-10-05", TransactionSide.CREDIT, "2.00", "OUTPUT", "STANDARD", seq = 2)
        entry(7, "2026-12-01", TransactionSide.CREDIT, "7.00", "OUTPUT", "STANDARD", seq = 7)
        entry(4, "2026-10-09", TransactionSide.CREDIT, "4.00", "OUTPUT", "STANDARD", seq = 4)
        entry(6, "2026-11-01", TransactionSide.CREDIT, "6.00", "OUTPUT", "STANDARD", seq = 6)

        val first = success(useCase.execute(request(limit = 3)))
        val second = success(useCase.execute(request(limit = 3, cursor = first.nextCursor)))
        val third = success(useCase.execute(request(limit = 3, cursor = second.nextCursor)))

        first.lines.size shouldBe 3
        second.lines.size shouldBe 3
        third.lines.size shouldBe 1
        (first.nextCursor != null) shouldBe true
        (second.nextCursor != null) shouldBe true
        third.nextCursor shouldBe null
        (first.lines + second.lines + third.lines).map { it.amount.amount.toPlainString() } shouldBe listOf("1.00", "2.00", "3.00", "4.00", "5.00", "6.00", "7.00")
    }

    @Test
    fun `given a new entry arriving after the first page, then the second page is not disturbed`() {
        for (n in 1..4) entry(n, "2026-10-0$n", TransactionSide.CREDIT, "$n.00", "OUTPUT", "STANDARD")
        val first = success(useCase.execute(request(limit = 2)))

        entry(9, "2026-12-30", TransactionSide.CREDIT, "9.00", "OUTPUT", "STANDARD")
        val second = success(useCase.execute(request(limit = 2, cursor = first.nextCursor)))

        second.lines.map { it.amount.amount.toPlainString() } shouldBe listOf("3.00", "4.00")
        (second.nextCursor != null) shouldBe true
    }

    @Test
    fun `given a malformed cursor, then it is refused`() {
        entry(1, "2026-10-05", TransactionSide.CREDIT, "1.00", "OUTPUT", "STANDARD")

        useCase.execute(request(cursor = "not-a-cursor")) shouldBe ComputeVatLinesUseCase.Result.InvalidCursor
        useCase.execute(request(cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("a|b|c".toByteArray()))) shouldBe ComputeVatLinesUseCase.Result.InvalidCursor
    }

    // ---- the recorded-up-to ceiling and the reversal link (Tx's correctness requirement, 2026-10-22) ----

    @Test
    fun `given the first page, then it fixes a recorded-at ceiling, returns it, and every later page carries the same one`() {
        for (n in 1..4) entry(n, "2026-10-0$n", TransactionSide.CREDIT, "$n.00", "OUTPUT", "STANDARD", recordedAt = java.time.Instant.parse("2026-10-0${n}T10:00:00Z"))
        now = java.time.Instant.parse("2026-12-31T23:00:00Z")
        val first = success(useCase.execute(request(limit = 2)))

        now = java.time.Instant.parse("2027-01-05T08:00:00Z")   // time passes before the next page is asked for
        val second = success(useCase.execute(request(limit = 2, cursor = first.nextCursor)))

        first.recordedUpTo shouldBe java.time.Instant.parse("2026-12-31T23:00:00Z")
        second.recordedUpTo shouldBe first.recordedUpTo
    }

    @Test
    fun `given a back-dated entry recorded after the first page, then no later page shows it, and a fresh read does`() {
        for (n in 1..4) entry(n, "2026-10-1$n", TransactionSide.CREDIT, "$n.00", "OUTPUT", "STANDARD", recordedAt = java.time.Instant.parse("2026-11-01T10:00:00Z"))
        now = java.time.Instant.parse("2026-12-01T00:00:00Z")
        val first = success(useCase.execute(request(limit = 2)))

        // Both recorded after the ceiling: one dated BEFORE the cursor (it would sort into the part already read) and one dated AFTER it
        // (without the ceiling it would turn up on a later page, so one figure would be built from two moments).
        entry(8, "2026-10-02", TransactionSide.CREDIT, "8.00", "OUTPUT", "STANDARD", recordedAt = java.time.Instant.parse("2026-12-02T09:00:00Z"))
        entry(9, "2026-10-20", TransactionSide.CREDIT, "9.00", "OUTPUT", "STANDARD", recordedAt = java.time.Instant.parse("2026-12-02T09:05:00Z"))
        val second = success(useCase.execute(request(limit = 10, cursor = first.nextCursor)))
        now = java.time.Instant.parse("2026-12-03T00:00:00Z")
        val fresh = success(useCase.execute(request(limit = 10)))

        (first.lines + second.lines).map { it.amount.amount.toPlainString() } shouldBe listOf("1.00", "2.00", "3.00", "4.00")
        fresh.lines.map { it.amount.amount.toPlainString() } shouldBe listOf("8.00", "1.00", "2.00", "3.00", "4.00", "9.00")
        fresh.recordedUpTo shouldBe java.time.Instant.parse("2026-12-03T00:00:00Z")
    }

    @Test
    fun `given a reversal, then its line carries the id of the entry it reverses`() {
        val original = entry(1, "2026-10-05", TransactionSide.CREDIT, "100.00", "OUTPUT", "STANDARD", reversed = true)
        val reversalEntry = original.let {
            JournalEntry.reconstitute(
                id(2), it.periodId, LocalDate.parse("2026-10-06"), it.lines.map { l -> l.copy(side = if (l.side == TransactionSide.CREDIT) TransactionSide.DEBIT else TransactionSide.CREDIT) },
                JournalSource.REVERSAL, "Undo", com.theprodeogroup.fish.domain.common.PostingStatus.POSTED, original.id, recordedSeq = 2
            )
        }
        entries.save(reversalEntry)

        val result = success(useCase.execute(request()))

        result.lines.map { it.entryId to it.reversalOfEntryId } shouldBe listOf(id(1) to null, id(2) to id(1))
    }

    @Test
    fun `given the raw lines, then applying Tax's one rule to them reproduces the VAT return's output, input and net for the same window`() {
        entry(1, "2026-10-12", TransactionSide.CREDIT, "200.00", "OUTPUT", "STANDARD")
        entry(2, "2026-10-13", TransactionSide.DEBIT, "50.00", "INPUT", "REDUCED")
        entry(3, "2026-10-14", TransactionSide.DEBIT, "15.00", "OUTPUT", "STANDARD")      // a credit note: a debit tagged OUTPUT reduces output VAT
        entry(4, "2026-10-15", TransactionSide.CREDIT, "5.00", "INPUT", "REDUCED")        // a discount received: a credit tagged INPUT reduces input VAT
        entry(5, "2026-11-20", TransactionSide.DEBIT, "150.00", "SETTLEMENT", null)       // the remittance: excluded
        entry(6, "2026-11-21", TransactionSide.DEBIT, "10.00", null, null)                // untagged debit: read as input by its side
        entry(7, "2026-11-22", TransactionSide.CREDIT, "3.00", null, null)                // untagged credit: read as output by its side
        val window = VatFilingPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31))
        val expected = VatReturn.of(company.id, window, vat.id, entries.findAllByCompany(company.id), gbp)

        var output = money("0.00")
        var input = money("0.00")
        for (line in success(useCase.execute(request())).lines) {
            if (line.vatDirection == "SETTLEMENT") continue
            val direction = line.vatDirection ?: if (line.side == TransactionSide.CREDIT) "OUTPUT" else "INPUT"
            // Credit raises output and debit lowers it; for input the other way round (the return's own rule).
            val signed = if ((direction == "OUTPUT") == (line.side == TransactionSide.CREDIT)) line.amount else money("0.00") - line.amount
            if (direction == "OUTPUT") output += signed else input += signed
        }

        output shouldBe expected.outputVat
        input shouldBe expected.inputVat
        (output - input) shouldBe expected.netVatDue
    }
}
