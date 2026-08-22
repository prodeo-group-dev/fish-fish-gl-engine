package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tax.ExemptionTest
import com.theprodeogroup.fish.domain.tax.MarginalRelief
import com.theprodeogroup.fish.domain.tax.RateStructure
import com.theprodeogroup.fish.domain.tax.Tier
import java.math.BigDecimal

/**
 * Encodes/decodes `TaxRule.rateStructure` to/from a small hand-written
 * tagged string for the `tax_rules.rate_structure` TEXT column
 * (`V9__tax_rule_rate_structure.sql`) - same convention as
 * `dimension_encoding.kt` (deliberately not a real JSON library; see
 * that file's own KDoc for the reasoning, which applies identically
 * here). [RateStructure.ThresholdExemption] nests another
 * [RateStructure], which a naive delimiter split can't handle
 * unambiguously (a nested [RateStructure.Tiered]'s own `;RELIEF:`
 * segment would collide with a flat split) - handled with a
 * length-prefix (`<len>:<encoded>`) instead, the standard fix for
 * recursive text encodings without a real parser.
 */
internal fun encodeRateStructure(structure: RateStructure): String = when (structure) {
    is RateStructure.Flat -> "FLAT:${structure.rate.toPlainString()}"

    is RateStructure.Tiered -> {
        val tiersPart = structure.tiers.joinToString(",") { tier ->
            "${tier.upperBound?.toPlainString() ?: "-"}|${tier.rate.toPlainString()}"
        }
        val reliefPart = structure.marginalRelief?.let { relief ->
            ";RELIEF:${relief.lowerLimit.toPlainString()}|${relief.upperLimit.toPlainString()}|${relief.fraction.toPlainString()}"
        } ?: ""
        "TIERED:$tiersPart$reliefPart"
    }

    is RateStructure.CategorySplit -> {
        val body = structure.ratesByCategory.entries.joinToString(",") { (key, rate) -> "$key=${rate.toPlainString()}" }
        "CATEGORY:$body"
    }

    is RateStructure.ThresholdExemption -> {
        val maxTurnoverPart = structure.exemptionTest.maxTurnover?.toPlainString() ?: "-"
        val maxAssetsPart = structure.exemptionTest.maxFixedAssets?.toPlainString() ?: "-"
        val otherwiseEncoded = encodeRateStructure(structure.otherwise)
        "THRESHOLD:$maxTurnoverPart|$maxAssetsPart|${otherwiseEncoded.length}:$otherwiseEncoded"
    }
}

internal fun decodeRateStructure(encoded: String): RateStructure {
    val tag = encoded.substringBefore(":")
    val body = encoded.substringAfter(":")
    return when (tag) {
        "FLAT" -> RateStructure.Flat(BigDecimal(body))

        "TIERED" -> {
            val hasRelief = body.contains(";RELIEF:")
            val tiersStr = if (hasRelief) body.substringBefore(";RELIEF:") else body
            val tiers = tiersStr.split(",").map { part ->
                val (upperBound, rate) = part.split("|")
                Tier(if (upperBound == "-") null else BigDecimal(upperBound), BigDecimal(rate))
            }
            val relief = if (hasRelief) {
                val (lower, upper, fraction) = body.substringAfter(";RELIEF:").split("|")
                MarginalRelief(BigDecimal(lower), BigDecimal(upper), BigDecimal(fraction))
            } else null
            RateStructure.Tiered(tiers, relief)
        }

        "CATEGORY" -> {
            val rates = body.split(",").associate { part ->
                val (key, rate) = part.split("=")
                key to BigDecimal(rate)
            }
            RateStructure.CategorySplit(rates)
        }

        "THRESHOLD" -> {
            val maxTurnoverPart = body.substringBefore("|")
            val afterTurnover = body.substringAfter("|")
            val maxAssetsPart = afterTurnover.substringBefore("|")
            val lengthAndOtherwise = afterTurnover.substringAfter("|")
            val lengthStr = lengthAndOtherwise.substringBefore(":")
            val otherwiseLength = lengthStr.toInt()
            val otherwiseEncoded = lengthAndOtherwise.substring(lengthStr.length + 1, lengthStr.length + 1 + otherwiseLength)

            val maxTurnover = if (maxTurnoverPart == "-") null else BigDecimal(maxTurnoverPart)
            val maxAssets = if (maxAssetsPart == "-") null else BigDecimal(maxAssetsPart)
            RateStructure.ThresholdExemption(ExemptionTest(maxTurnover, maxAssets), decodeRateStructure(otherwiseEncoded))
        }

        else -> error("Unknown RateStructure tag '$tag' in encoded value '$encoded'")
    }
}
