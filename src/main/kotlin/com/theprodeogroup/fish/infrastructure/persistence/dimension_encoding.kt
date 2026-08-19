package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.DimensionType

/**
 * Encodes/decodes `JournalLine.dimensions` (`Map<DimensionType, String>`)
 * to/from a small hand-written JSON-like string for the `journal_lines.dimensions`
 * TEXT column (docs/DDD_Design.md Section 10.1). Deliberately not a real
 * JSON library (kotlinx.serialization/Jackson) - every dimension value in
 * this codebase today is a plain UUID or enum `.name` string (never
 * containing quotes, backslashes, or newlines), so a minimal
 * hand-written encoder covers every real case without a new dependency.
 * Revisit with a proper JSON library if `dimensions` ever needs
 * SQL-level querying (which is also why the column is plain `TEXT`, not
 * `jsonb`).
 */
internal fun encodeDimensions(dimensions: Map<DimensionType, String>): String {
    if (dimensions.isEmpty()) return "{}"
    return dimensions.entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.name}\":\"${jsonEscape(value)}\""
    }
}

internal fun decodeDimensions(encoded: String): Map<DimensionType, String> {
    val inner = encoded.trim().removePrefix("{").removeSuffix("}")
    if (inner.isBlank()) return emptyMap()
    return PAIR_REGEX.findAll(inner).associate { match ->
        DimensionType.valueOf(match.groupValues[1]) to jsonUnescape(match.groupValues[2])
    }
}

private val PAIR_REGEX = Regex("\"([A-Z_0-9]+)\":\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun jsonEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

private fun jsonUnescape(value: String): String = value
    .replace("\\\"", "\"")
    .replace("\\n", "\n")
    .replace("\\r", "\r")
    .replace("\\t", "\t")
    .replace("\\\\", "\\")
