package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.fish.domain.common.Jurisdiction
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The test-side copy of the seeded VAT rates (2026-10-06, Option B: rates
 * are DATA in `vat_rates` now, no longer `VatRateSchedule.IRELAND`/`UK`
 * constants in production code). Mirrors `V30__vat_rates.sql`'s seed
 * exactly; `VatRateRepositoryIntegrationTest` asserts the real seeded table
 * resolves identically, so this copy cannot silently drift from it.
 *
 * Real Irish rates (docs/IE/IE_Tax_And_Currency_Settings.md): five bands, and
 * the already-legislated 1 Jul 2026 change takes SECOND_REDUCED from 13.5% to
 * 9%. Real UK rates (docs/UK/UK_Tax_And_Currency_Settings.md): three bands,
 * no SECOND_REDUCED/SUPER_REDUCED. Sierra Leone: GST 15% flat, seeded UNVERIFIED.
 */
object TestVatRates {
    private val always = LocalDate.of(2000, 1, 1)

    val IRELAND_ROWS: List<VatRateRow> = listOf(
        VatRateRow(Jurisdiction.IE, VatCategory.STANDARD, BigDecimal("0.230000"), always, true),
        VatRateRow(Jurisdiction.IE, VatCategory.REDUCED, BigDecimal("0.135000"), always, true),
        VatRateRow(Jurisdiction.IE, VatCategory.SECOND_REDUCED, BigDecimal("0.135000"), always, true),
        VatRateRow(Jurisdiction.IE, VatCategory.SECOND_REDUCED, BigDecimal("0.090000"), LocalDate.of(2026, 7, 1), true),
        VatRateRow(Jurisdiction.IE, VatCategory.SUPER_REDUCED, BigDecimal("0.048000"), always, true),
        VatRateRow(Jurisdiction.IE, VatCategory.ZERO_RATED, BigDecimal("0.000000"), always, true)
    )

    val UK_ROWS: List<VatRateRow> = listOf(
        VatRateRow(Jurisdiction.UK, VatCategory.STANDARD, BigDecimal("0.200000"), always, true),
        VatRateRow(Jurisdiction.UK, VatCategory.REDUCED, BigDecimal("0.050000"), always, true),
        VatRateRow(Jurisdiction.UK, VatCategory.ZERO_RATED, BigDecimal("0.000000"), always, true)
    )

    val SIERRA_LEONE_ROWS: List<VatRateRow> = listOf(
        VatRateRow(Jurisdiction.SL, VatCategory.STANDARD, BigDecimal("0.150000"), always, false)
    )

    val IRELAND: VatRateSchedule = VatRateSchedule.of(IRELAND_ROWS)!!
    val UK: VatRateSchedule = VatRateSchedule.of(UK_ROWS)!!
}
