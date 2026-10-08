package com.theprodeogroup.fish.domain

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * UAT v2.2 W-M3: the depreciation journal description read "Depreciation - Van (FixedAssetId(value=32d6a61a-...))",
 * a Kotlin toString leaked into text people read on the journal and in the bank match picker. Journal
 * descriptions are user text: the thing's name, never its identifier.
 */
class JournalDescriptionTextTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val day = LocalDate.of(2026, 1, 15)

    @Test
    fun `the depreciation journal description names the asset and carries no identifier`() {
        val asset = FixedAsset.create(CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("1000.00"), gbp), day, 4)

        val entry = requireNotNull(asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), day))

        entry.description shouldBe "Depreciation - Delivery Van"
    }

    @Test
    fun `no domain journal description interpolates an identifier into its text`() {
        // Any description of the shape "... ($id)" would print the value class's toString, e.g. FixedAssetId(value=...).
        val offenders = File("src/main/kotlin/com/theprodeogroup/fish/domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line ->
                if (Regex(""""[^"]*\(\$(id|\{id\})\)[^"]*"""").containsMatchIn(line)) "${file.name}:${i + 1}" else null
            } }
            .toList()

        offenders.shouldBeEmpty()
    }
}
