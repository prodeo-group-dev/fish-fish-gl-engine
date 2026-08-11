package com.theprodeogroup.fish.domain.tenancy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TenantStatusTest {

    @Test
    fun `given a Draft tenant, when activated, then transition to Active is valid`() {
        TenantStatus.DRAFT.canTransitionTo(TenantStatus.ACTIVE) shouldBe true
    }

    @Test
    fun `given a Draft tenant, when suspended directly, then transition is invalid`() {
        TenantStatus.DRAFT.canTransitionTo(TenantStatus.SUSPENDED) shouldBe false
    }

    @Test
    fun `given an Active tenant, when suspended, then transition to Suspended is valid`() {
        TenantStatus.ACTIVE.canTransitionTo(TenantStatus.SUSPENDED) shouldBe true
    }

    @Test
    fun `given a Suspended tenant, when reactivated, then transition to Active is valid`() {
        TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.ACTIVE) shouldBe true
    }

    @Test
    fun `given an Active tenant, when closed, then transition to Closed is valid`() {
        TenantStatus.ACTIVE.canTransitionTo(TenantStatus.CLOSED) shouldBe true
    }

    @Test
    fun `given a Suspended tenant, when closed, then transition to Closed is valid`() {
        TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.CLOSED) shouldBe true
    }

    @Test
    fun `given a Closed tenant, when any transition is attempted, then it fails - Closed is terminal`() {
        TenantStatus.entries.forEach { target ->
            TenantStatus.CLOSED.canTransitionTo(target) shouldBe false
        }
    }

    @Test
    fun `given Active status, when checked, then it is operational`() {
        TenantStatus.ACTIVE.isOperational() shouldBe true
        TenantStatus.DRAFT.isOperational() shouldBe false
        TenantStatus.SUSPENDED.isOperational() shouldBe false
        TenantStatus.CLOSED.isOperational() shouldBe false
    }

    @Test
    fun `given Closed status, when checked, then it is final`() {
        TenantStatus.CLOSED.isFinal() shouldBe true
        TenantStatus.ACTIVE.isFinal() shouldBe false
    }
}
