package com.theprodeogroup.fish.infrastructure.web

/**
 * A small, hand-rolled CSV parser for the opening-figures importers
 * (`docs/Opening_Figures_CSV_Upload_Requirements_Specification.md`
 * FR-UP1: "Excel-compatible: commas, quoted fields, CRLF/LF. Optional
 * BOM tolerated.") - no CSV library is already a dependency of this
 * project, and the actual grammar needed (quoted fields, embedded
 * commas/newlines inside quotes, `""` as an escaped quote, CRLF-or-LF)
 * is small enough that pulling in a new third-party dependency for it
 * isn't worth doing, matching this codebase's own "minimal builds"
 * convention.
 *
 * [parseCsv] returns one [Map] per data row, keyed by the header row's
 * column names - callers (e.g. `OpeningImportRoutes.kt`) read named
 * columns out of each map rather than positional indices, so column
 * order in the uploaded file never matters.
 */
object CsvParser {
    /** @throws CsvParseException if the file is empty or a data row doesn't have the same number of fields as the header row. */
    fun parseCsv(text: String): List<Map<String, String>> {
        val withoutBom = text.removePrefix("﻿")
        val rows = splitIntoRows(withoutBom)
        if (rows.isEmpty()) throw CsvParseException("CSV file is empty")

        val header = rows.first()
        return rows.drop(1).mapIndexed { index, row ->
            if (row.size != header.size) {
                throw CsvParseException("row ${index + 2} has ${row.size} fields, expected ${header.size} (matching the header row)")
            }
            header.zip(row).toMap()
        }
    }

    /** Splits raw CSV text into rows of raw field values, honoring quoted fields that may themselves contain commas or newlines. */
    private fun splitIntoRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        var sawAnyContent = false

        fun endField() {
            currentRow.add(currentField.toString())
            currentField.clear()
        }

        fun endRow() {
            endField()
            rows.add(currentRow)
            currentRow = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            sawAnyContent = true
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    currentField.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> endField()
                !inQuotes && c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    endRow()
                    i++
                }
                !inQuotes && (c == '\n' || c == '\r') -> endRow()
                else -> currentField.append(c)
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) endRow()

        // A trailing newline at end-of-file produces one extra, genuinely empty row - drop it rather than
        // treat it as a short/malformed data row.
        return if (sawAnyContent) rows.filterNot { it.size == 1 && it.singleOrNull() == "" } else emptyList()
    }
}

class CsvParseException(message: String) : Exception(message)
