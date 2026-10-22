package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import com.theprodeogroup.common.VatCalculator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * GL's own per-line VAT arithmetic ([VatRateSchedule.vatAmountFor], used today by record-sale, record-obligation and the supplier
 * credit note) and the shared [VatCalculator] in `fish-common` (GL-T-29, GL-FR-TAX-7) must give the same figure for every net
 * and every rate, so that SOP, POP and CBB computing VAT with the calculator and GL computing it itself cannot drift while both
 * exist (design `GL_VAT_On_Finished_Journals_Design.md` section 8.2). When GL's own path is retired (GL-T-26b) this test goes with it.
 */
class VatCalculatorParityTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val date = LocalDate.of(2026, 10, 12)

    @Test
    fun `GL's schedule and the shared calculator give the same VAT for every net from one penny to forty pounds, at every rate in the UK and Irish tables`() {
        for (schedule in listOf(TestVatRates.UK, TestVatRates.IRELAND)) {
            for (category in VatCategory.entries) {
                val ratePercent = if (category == VatCategory.EXEMPT) null
                else runCatching { schedule.rateFor(category, date) }.getOrNull()?.movePointRight(2)
                if (category != VatCategory.EXEMPT && ratePercent == null) continue // this jurisdiction has no such band
                for (pence in 1..4000) {
                    val net = Money(BigDecimal(pence).movePointLeft(2), gbp)
                    val own = schedule.vatAmountFor(category, net, date)
                    val shared = VatCalculator.vatOnNet(net, ratePercent?.toPlainString())
                    own shouldBe shared
                }
            }
        }
    }

    @Test
    fun `GL's schedule and the shared calculator agree on larger and round amounts`() {
        for (amount in listOf("1000.00", "99999.99", "123456.78", "0.50", "0.25", "0.35")) {
            val net = Money(BigDecimal(amount), gbp)
            for (category in listOf(VatCategory.STANDARD, VatCategory.REDUCED, VatCategory.ZERO_RATED)) {
                val rate = TestVatRates.UK.rateFor(category, date).movePointRight(2)
                TestVatRates.UK.vatAmountFor(category, net, date) shouldBe VatCalculator.vatOnNet(net, rate)
            }
        }
    }
}
