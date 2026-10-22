package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `docs/GL_Error_Tokens.md` is the table of every error token GL answers (GL Playbook GL-FR-CTR-3, task GL-T-22). This test keeps it
 * honest in both directions: every token the code can answer is a row, and every row's token still exists in the code. A token is a
 * lowercase literal that is the first argument of an `...Dto(` or `errorResponseJson(` call, or one of the few passed another way
 * ([OTHER_WAYS]).
 */
class ErrorTokenTableTest {

    private val source = File("src/main/kotlin")
    private val table = File("docs/GL_Error_Tokens.md")

    private fun codeTokens(): Set<String> {
        val call = Regex("""(?:\w*Dto|errorResponseJson)\(\s*"([a-z][a-z_0-9]+)"""")
        return source.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { call.findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet() + OTHER_WAYS
    }

    private fun tableTokens(): Set<String> =
        Regex("""^\| `([a-z][a-z_0-9]+)` \|""", RegexOption.MULTILINE).findAll(table.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `every error token the code answers is a row of the table`() {
        (codeTokens() - tableTokens()) shouldBe emptySet()
    }

    @Test
    fun `every token in the table still exists in the code`() {
        val allSource = source.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        val gone = tableTokens().filter { "\"$it\"" !in allSource }
        gone shouldBe emptyList()
    }

    @Test
    fun `the scan really found the tokens`() {
        (codeTokens().size > 90) shouldBe true
    }

    private companion object {
        /** Tokens built by a helper that takes them as a later argument (the settlement and reconciliation policies). */
        val OTHER_WAYS = setOf("settlement_account_not_a_cash_book", "reconciliation_requires_bank_account")
    }
}
