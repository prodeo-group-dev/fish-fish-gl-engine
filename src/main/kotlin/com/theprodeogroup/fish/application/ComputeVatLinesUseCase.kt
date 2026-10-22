package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.SourceReference
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate
import java.util.Base64
import java.util.Currency
import java.util.UUID

/**
 * `GET /companies/{companyId}/vat-lines?from&to` (GL-T-32, GL-FR-TAX-10; Tax T-TS-13): the posted lines on the Company's VAT Control Account
 * whose entry is dated in the window, as RAW facts (the line's own `VAT_DIRECTION` and `VAT_CATEGORY` tags, possibly absent; its side and
 * amount) so the caller applies the one rule `VatReturn.of` applies: classification is not done here. See
 * `docs/reports/Playbooks/GL/GL_VAT_Lines_Read_Design.md`.
 *
 * Only entries that count toward the account (posted, system or reversed: `hasHistoricalEffect`, the rule the account balances use) appear: a
 * reversal is a real line on the account and is listed beside the line it reverses; a draft never is. GL finds the account itself (the LIABILITY
 * account coded 2150). Read-only.
 *
 * **Paged by a cursor, in a total order**: (entry date, recorded order, entry id, line index). The order does not change as new entries arrive
 * after the window, so a caller reading page after page sees every line exactly once. The cursor is opaque to the caller; it names the position
 * of the last line returned.
 *
 * **A recorded-at ceiling, fixed by the first page (Tax's correctness requirement, 2026-10-22).** An entry back-dated into the window and recorded
 * while the caller is mid-paging would sort into the part already read and be silently missed from a figure that gets filed. So the first page
 * fixes `recordedUpTo` (the moment it was asked), the cursor carries it, and every page leaves out entries recorded after it. The answer returns it,
 * the same on every page, so the caller can say its figure was "computed from entries recorded up to X". An entry with no recorded time (an old test
 * double) is included; the database always has one.
 */
class ComputeVatLinesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    /** The clock the first page's ceiling is read from (a test fixes it). */
    private val now: () -> java.time.Instant = { java.time.Instant.now() }
) {
    data class Request(val companyId: CompanyId, val from: LocalDate, val to: LocalDate, val limit: Int, val cursor: String?)

    data class VatLine(
        val entryId: JournalEntryId,
        val date: LocalDate,
        val side: TransactionSide,
        val amount: Money,
        /** The line's raw `VAT_DIRECTION` tag (OUTPUT, INPUT, SETTLEMENT), or null when untagged. */
        val vatDirection: String?,
        /** The line's raw `VAT_CATEGORY` tag, or null. */
        val vatCategory: String?,
        val entryStatus: String,
        val sourceReference: SourceReference?,
        /** For a reversal's line, the entry it reverses; null otherwise. */
        val reversalOfEntryId: JournalEntryId?
    )

    sealed class Result {
        data class Success(
            val vatControlAccountId: AccountId,
            val currency: Currency,
            val from: LocalDate,
            val to: LocalDate,
            /** Only entries recorded at or before this instant are in any page: fixed by the first page, carried by the cursor. */
            val recordedUpTo: java.time.Instant,
            val lines: List<VatLine>,
            val nextCursor: String?
        ) : Result()

        data object CompanyNotFound : Result()
        data object VatControlAccountNotConfigured : Result()
        data object InvalidRange : Result()
        data object InvalidCursor : Result()
    }

    /** A line's place in the total order. */
    private data class Position(val date: LocalDate, val recordedSeq: Long, val entryId: String, val lineIndex: Int) : Comparable<Position> {
        override fun compareTo(other: Position): Int =
            compareValuesBy(this, other, { it.date }, { it.recordedSeq }, { it.entryId }, { it.lineIndex })

        /** The cursor: this position and the ceiling the first page fixed, opaque to the caller. */
        fun encode(ceiling: java.time.Instant): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString("$date|$recordedSeq|$entryId|$lineIndex|$ceiling".toByteArray(Charsets.UTF_8))

        companion object {
            fun decode(cursor: String): Pair<Position, java.time.Instant>? = try {
                val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split("|")
                if (parts.size != 5) null
                else Position(LocalDate.parse(parts[0]), parts[1].toLong(), UUID.fromString(parts[2]).toString(), parts[3].toInt()) to java.time.Instant.parse(parts[4])
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: java.time.format.DateTimeParseException) {
                null
            }
        }
    }

    fun execute(request: Request): Result {
        val company = companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound
        if (request.to.isBefore(request.from)) return Result.InvalidRange
        val decoded = request.cursor?.let { Position.decode(it) ?: return Result.InvalidCursor }
        val after = decoded?.first
        val ceiling = decoded?.second ?: now()
        val vat = accountRepository.findAllByCompany(request.companyId)
            .firstOrNull { it.type == AccountType.LIABILITY && it.code == ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE }
            ?: return Result.VatControlAccountNotConfigured

        val ordered = journalEntryRepository.findAllByAccount(vat.id)
            .filter { it.status.hasHistoricalEffect() && !it.date.isBefore(request.from) && !it.date.isAfter(request.to) }
            .filter { it.recordedAt == null || !it.recordedAt.isAfter(ceiling) }
            .flatMap { entry ->
                entry.lines.withIndex().filter { it.value.accountId == vat.id }.map { (index, line) ->
                    Position(entry.date, entry.recordedSeq ?: -1L, entry.id.value.toString(), index) to VatLine(
                        entry.id, entry.date, line.side, line.amount,
                        line.dimensions[DimensionType.VAT_DIRECTION], line.dimensions[DimensionType.VAT_CATEGORY],
                        entry.status.name, entry.provenance?.sourceReference, entry.reversalOfEntryId
                    )
                }
            }
            .sortedBy { it.first }
            .let { sorted -> if (after == null) sorted else sorted.filter { it.first > after } }

        val page = ordered.take(request.limit)
        val nextCursor = if (ordered.size > request.limit) page.last().first.encode(ceiling) else null
        return Result.Success(vat.id, company.baseCurrency, request.from, request.to, ceiling, page.map { it.second }, nextCursor)
    }
}
