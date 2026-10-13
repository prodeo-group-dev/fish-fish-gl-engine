package contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Loads and checks golden fixtures from the pinned fish-contracts submodule. Copy into the repo's test sources (keep the
 * `package contracts` line so tests in any package can import it). Needs kotlinx-serialization-json, which every FiSH
 * service already has.
 *
 * The submodule path is passed as the system property `contracts.dir` (build.gradle.kts:
 * `tasks.test { systemProperty("contracts.dir", "$projectDir/contracts") }`). There is deliberately NO default: a missing
 * setting must fail loudly rather than read some other folder.
 *
 * Source repos' golden tests call [verifyOrUpdate]; consumers' gateway tests call [read] and run the text through their
 * REAL strict client. Fixtures are rewritten only with `UPDATE_CONTRACTS=1`, never by hand.
 */
@OptIn(ExperimentalSerializationApi::class)
object ContractFixtures {
    private val dir: File by lazy {
        val path = requireNotNull(System.getProperty("contracts.dir")) {
            "System property contracts.dir is not set: add tasks.test { systemProperty(\"contracts.dir\", \"\$projectDir/contracts\") } " +
                "to build.gradle.kts and init the fish-contracts submodule"
        }
        File(path)
    }

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    val updating: Boolean get() = System.getenv("UPDATE_CONTRACTS") == "1"

    /** The fixture's text, e.g. `read("contracts/sop/sales.json")`. */
    fun read(relativePath: String): String =
        File(dir, relativePath).also { require(it.exists()) { "Missing contract fixture $relativePath under $dir" } }.readText()

    /**
     * Fails unless [actualJson] and the fixture are the same JSON: object key order and whitespace are ignored, array order
     * and the text of numbers are not. The message shows both, pretty-printed.
     */
    fun assertSameJson(relativePath: String, actualJson: String) {
        val expected = parse(read(relativePath))
        val actual = parse(actualJson)
        if (expected != actual) {
            throw AssertionError(
                "Response differs from contract fixture $relativePath (UPDATE_CONTRACTS=1 rewrites it, then tell the consumers).\n" +
                    "--- fixture ---\n${pretty.encodeToString(JsonElement.serializer(), expected)}\n" +
                    "--- actual ---\n${pretty.encodeToString(JsonElement.serializer(), actual)}"
            )
        }
    }

    /** For a source's golden test: rewrite the fixture when `UPDATE_CONTRACTS=1`, otherwise check against it. */
    fun verifyOrUpdate(relativePath: String, actualJson: String) {
        if (updating) write(relativePath, actualJson) else assertSameJson(relativePath, actualJson)
    }

    /** Rewrites a fixture, pretty-printed with a fixed two-space indent so a PR diff is readable. Only with `UPDATE_CONTRACTS=1`. */
    fun write(relativePath: String, json: String) {
        check(updating) { "Fixtures are rewritten only with UPDATE_CONTRACTS=1" }
        val text = pretty.encodeToString(JsonElement.serializer(), parse(json)) + "\n"
        File(dir, relativePath).also { it.parentFile.mkdirs() }.writeText(text)
    }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)
}
