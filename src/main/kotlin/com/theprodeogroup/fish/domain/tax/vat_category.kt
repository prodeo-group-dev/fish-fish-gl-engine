package com.theprodeogroup.fish.domain.tax

/**
 * A line-level VAT classification (docs/IE/IE_VAT_MVP_Design.md, this
 * session's own correction to it) - genuinely different granularity from
 * [RateStructure]'s taxpayer-level Corporate Income Tax shapes, since the
 * rate applied depends on *what's being sold*, not who the taxpayer is.
 *
 * Six values, not the five the original design doc proposed - [ZERO_RATED]
 * and [EXEMPT] are legally different, not two names for the same thing:
 * - [ZERO_RATED] is still *within* VAT scope at a 0% rate - the sale counts
 *   toward taxable turnover, and input VAT on related costs stays
 *   reclaimable.
 * - [EXEMPT] is *outside* VAT scope entirely - no output VAT charged, and
 *   input VAT on directly-attributable costs is not reclaimable. Has no
 *   rate-table entry in [VatRateSchedule] at all - computing VAT for an
 *   exempt line is always zero by construction, not by a 0%-rate lookup.
 *
 * Full partial-exemption input-VAT apportionment (the real calculation a
 * trader with *both* taxable and exempt outputs must do) is deliberately
 * out of scope - MVP assumes a fully-taxable trader for input recovery
 * purposes. [EXEMPT] is modeled here only for output-side accuracy.
 */
enum class VatCategory {
    STANDARD,
    REDUCED,
    SECOND_REDUCED,
    SUPER_REDUCED,
    ZERO_RATED,
    EXEMPT
}
