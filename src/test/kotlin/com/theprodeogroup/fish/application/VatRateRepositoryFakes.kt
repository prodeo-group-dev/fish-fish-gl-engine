package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatRateRepository
import com.theprodeogroup.fish.domain.tax.VatRateRow
import com.theprodeogroup.fish.domain.tax.VatRateSchedule

/**
 * In-memory [VatRateRepository], pre-seeded with the same rows
 * `V30__vat_rates.sql` seeds (IE and UK verified, SL unverified), so a test
 * starts from the production baseline. Only VERIFIED rows form a schedule,
 * as in the Exposed implementation.
 */
class FakeVatRateRepository : VatRateRepository {
    private val rows = mutableListOf<VatRateRow>()

    init {
        (TestVatRates.IRELAND_ROWS + TestVatRates.UK_ROWS + TestVatRates.SIERRA_LEONE_ROWS).forEach { save(it) }
    }

    override fun save(row: VatRateRow) {
        rows.removeAll { it.jurisdiction == row.jurisdiction && it.category == row.category && it.effectiveFrom == row.effectiveFrom }
        rows.add(row)
    }

    override fun findVerifiedScheduleFor(jurisdiction: Jurisdiction): VatRateSchedule? =
        VatRateSchedule.of(rows.filter { it.jurisdiction == jurisdiction && it.verified })
}
