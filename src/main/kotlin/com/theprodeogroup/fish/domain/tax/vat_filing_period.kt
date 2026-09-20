package com.theprodeogroup.fish.domain.tax

import java.time.LocalDate

/**
 * A VAT filing window - a plain date range, deliberately independent of
 * GL's own `Period`/`PeriodType` (docs/IE/IE_VAT_MVP_Design.md; no
 * `BI_MONTHLY` value exists on `PeriodType`, only MONTH/QUARTER/YEAR/
 * CUSTOM). A Company may close its accounting Periods monthly while
 * filing VAT bi-monthly - these are two independent cadences, and forcing
 * the VAT filing window through the accounting `Period` aggregate would
 * conflate them. Scoped to bi-monthly for this MVP (Ireland's default
 * VAT3 cadence) - the shape generalizes to other cadences later without a
 * structural change, since it's already just a start/end date, not a
 * fixed-length enum.
 */
data class VatFilingPeriod(val startDate: LocalDate, val endDate: LocalDate) {
    init {
        require(!startDate.isAfter(endDate)) { "VatFilingPeriod startDate must not be after endDate" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)
}
