package com.theprodeogroup.fish.infrastructure.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CsvParsingTest {

    @Test
    fun `given a simple CSV with LF line endings, when parsed, then each row maps header to value`() {
        val result = CsvParser.parseCsv("account_code,amount\n1000,5000.00\n2000,800.00\n")

        result shouldBe listOf(
            mapOf("account_code" to "1000", "amount" to "5000.00"),
            mapOf("account_code" to "2000", "amount" to "800.00")
        )
    }

    @Test
    fun `given CRLF line endings, when parsed, then it parses identically to LF`() {
        val result = CsvParser.parseCsv("account_code,amount\r\n1000,5000.00\r\n")

        result shouldBe listOf(mapOf("account_code" to "1000", "amount" to "5000.00"))
    }

    @Test
    fun `given a UTF-8 BOM at the start of the file, when parsed, then it's stripped and doesn't corrupt the first header`() {
        val result = CsvParser.parseCsv("﻿account_code,amount\n1000,5000.00\n")

        result.single()["account_code"] shouldBe "1000"
    }

    @Test
    fun `given a quoted field containing a comma, when parsed, then the comma doesn't split the field`() {
        val result = CsvParser.parseCsv("account_code,description\n1000,\"Cash, at bank\"\n")

        result.single()["description"] shouldBe "Cash, at bank"
    }

    @Test
    fun `given a quoted field containing an escaped quote, when parsed, then it decodes to a single quote`() {
        val result = CsvParser.parseCsv("account_code,description\n1000,\"Say \"\"hello\"\"\"\n")

        result.single()["description"] shouldBe "Say \"hello\""
    }

    @Test
    fun `given a quoted field containing an embedded newline, when parsed, then the field stays whole`() {
        val result = CsvParser.parseCsv("account_code,description\n1000,\"Line one\nLine two\"\n")

        result.single()["description"] shouldBe "Line one\nLine two"
    }

    @Test
    fun `given an empty file, when parsed, then it throws`() {
        shouldThrow<CsvParseException> { CsvParser.parseCsv("") }
    }

    @Test
    fun `given a data row with fewer fields than the header, when parsed, then it throws naming the row number`() {
        val exception = shouldThrow<CsvParseException> {
            CsvParser.parseCsv("account_code,amount,contra_account_code\n1000,5000.00\n")
        }

        exception.message shouldBe "row 2 has 2 fields, expected 3 (matching the header row)"
    }

    @Test
    fun `given only a header row and no data rows, when parsed, then it returns an empty list`() {
        val result = CsvParser.parseCsv("account_code,amount\n")

        result shouldBe emptyList()
    }
}
