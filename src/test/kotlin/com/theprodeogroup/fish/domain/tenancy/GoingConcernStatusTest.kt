package com.theprodeogroup.fish.domain.tenancy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GoingConcernStatusTest {

    @Test
    fun `given an Assumed status, when flagged, then transition to SubstantialDoubt is valid`() {
        GoingConcernStatus.ASSUMED.canTransitionTo(GoingConcernStatus.SUBSTANTIAL_DOUBT) shouldBe true
    }

    @Test
    fun `given a SubstantialDoubt status, when reverted, then transition to Assumed is invalid - not yet decided how this should work`() {
        GoingConcernStatus.SUBSTANTIAL_DOUBT.canTransitionTo(GoingConcernStatus.ASSUMED) shouldBe false
    }

    @Test
    fun `given a SubstantialDoubt status, when flagged again, then transition to itself is invalid`() {
        GoingConcernStatus.SUBSTANTIAL_DOUBT.canTransitionTo(GoingConcernStatus.SUBSTANTIAL_DOUBT) shouldBe false
    }
}
