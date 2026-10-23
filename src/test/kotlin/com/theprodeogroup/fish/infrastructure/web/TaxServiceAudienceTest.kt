package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Tax credential's audience comes from exactly one environment variable, `FISH_JWT_SERVICE_AUDIENCE_TAX`, and from no
 * other service's: a verifier pointed at another service's audience, or one that accepts a missing audience, would let the wrong
 * token (or any token) in. Unset or blank means NO credential (inert), never an empty-audience verifier.
 */
class TaxServiceAudienceTest {

    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? = { name -> pairs.toMap()[name] }

    @Test
    fun `the tax audience is read from FISH_JWT_SERVICE_AUDIENCE_TAX and nothing else`() {
        taxServiceAudience(env("FISH_JWT_SERVICE_AUDIENCE_TAX" to "tax-client-id")) shouldBe "tax-client-id"
    }

    @Test
    fun `another service's audience variable is never used for Tax`() {
        taxServiceAudience(
            env(
                "FISH_JWT_SERVICE_AUDIENCE_PAYROLL" to "payroll-client", "FISH_JWT_SERVICE_AUDIENCE_CBB" to "cbb-client",
                "FISH_JWT_SERVICE_AUDIENCE_POP" to "pop-client", "FISH_JWT_SERVICE_AUDIENCE" to "sop-client"
            )
        ) shouldBe null
    }

    @Test
    fun `an unset or blank tax audience means no credential, never an empty audience`() {
        taxServiceAudience(env()) shouldBe null
        taxServiceAudience(env("FISH_JWT_SERVICE_AUDIENCE_TAX" to "")) shouldBe null
        taxServiceAudience(env("FISH_JWT_SERVICE_AUDIENCE_TAX" to "   ")) shouldBe null
    }
}
