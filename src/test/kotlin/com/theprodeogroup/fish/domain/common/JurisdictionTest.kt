package com.theprodeogroup.fish.domain.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JurisdictionTest {

    @Test
    fun `given a two-letter uppercase code, when constructed, then it is accepted - including one that is not in the original seven`() {
        Jurisdiction("ZA").code shouldBe "ZA"
        Jurisdiction.UK.code shouldBe "UK"
    }

    @Test
    fun `given a malformed code, when constructed, then it is rejected`() {
        listOf("", "U", "UKK", "uk", "U1", "GB ", " UK").forEach { bad ->
            assertThrows<IllegalArgumentException> { Jurisdiction(bad) }
        }
    }

    @Test
    fun `given a jurisdiction, when rendered as a string, then it is its bare code - error messages and logs read the same as before`() {
        Jurisdiction.SL.toString() shouldBe "SL"
    }

    @Test
    fun `given a JurisdictionEntry with a blank name, when constructed, then it is rejected`() {
        assertThrows<IllegalArgumentException> { JurisdictionEntry(Jurisdiction("ZA"), "  ", true) }
    }
}
