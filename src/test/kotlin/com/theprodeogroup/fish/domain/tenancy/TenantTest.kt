package com.theprodeogroup.fish.domain.tenancy

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

class TenantTest {

    @Test
    fun `given a valid name, segment and currency, when onboarded, then the tenant is Draft with no Companies or Memberships yet`() {
        val tenant = Tenant.onboard(
            name = "Purse",
            segment = TenantSegment.INTERNAL_VENTURE,
            baseCurrency = GBP
        )

        tenant.status shouldBe TenantStatus.DRAFT
        tenant.companyIds shouldHaveSize 0
        tenant.adminMembershipIds shouldHaveSize 0
        tenant.kybStatus shouldBe KybStatus.PENDING
        tenant.kybVerificationDeadline shouldBe null
    }

    @Test
    fun `given a Draft tenant with no Company or admin Membership, when activation is attempted, then it fails with both reasons`() {
        val tenant = Tenant.onboard("Acme SMB", TenantSegment.EXTERNAL_B2B, GBP)

        val result = tenant.activate()

        result.isValid shouldBe false
        result.errors.size shouldBe 2
        tenant.status shouldBe TenantStatus.DRAFT
    }

    @Test
    fun `given a Draft tenant with a Company but no admin Membership, when activation is attempted, then it fails`() {
        val tenant = Tenant.onboard("Acme SMB", TenantSegment.EXTERNAL_B2B, GBP)
        tenant.addCompany(CompanyId.generate())

        val result = tenant.activate()

        result.isValid shouldBe false
        result.errors shouldHaveSize 1
    }

    @Test
    fun `given a Draft tenant with a Company and admin Membership, when activated, then it succeeds even though KYB is still Pending`() {
        val tenant = Tenant.onboard("Acme SMB", TenantSegment.EXTERNAL_B2B, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())

        val result = tenant.activate()

        result.isValid shouldBe true
        tenant.status shouldBe TenantStatus.ACTIVE
        tenant.kybStatus shouldBe KybStatus.PENDING
    }

    @Test
    fun `given a Draft tenant whose KYB is Flagged, when activation is attempted, then it is rejected`() {
        val tenant = Tenant.onboard("Suspicious Co", TenantSegment.EXTERNAL_B2B, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        tenant.recordKybOutcome(KybStatus.FLAGGED)

        val result = tenant.activate()

        result.isValid shouldBe false
        tenant.status shouldBe TenantStatus.DRAFT
    }

    @Test
    fun `given a successful activation, when the deadline is checked, then it is 180 days from activation`() {
        val tenant = readyToActivate()
        val now = Instant.parse("2026-08-11T00:00:00Z")

        tenant.activate(now)

        tenant.kybVerificationDeadline shouldBe now.plus(180, ChronoUnit.DAYS)
    }

    @Test
    fun `given a successful activation, when domain events are pulled, then TenantActivated and TenantOnboarded are both present`() {
        val tenant = readyToActivate()

        tenant.activate()
        val events = tenant.pullDomainEvents()

        events shouldHaveSize 2
        events.any { it is TenantActivated } shouldBe true
        events.any { it is TenantOnboarded } shouldBe true
    }

    @Test
    fun `given domain events already pulled, when pulled again, then the list is empty - draining, not peeking`() {
        val tenant = readyToActivate()
        tenant.activate()

        tenant.pullDomainEvents()
        val secondPull = tenant.pullDomainEvents()

        secondPull shouldHaveSize 0
    }

    @Test
    fun `given an Active tenant, when suspended, then status becomes Suspended and a reason is recorded`() {
        val tenant = readyToActivate()
        tenant.activate()

        val result = tenant.suspend("non-payment")

        result.isValid shouldBe true
        tenant.status shouldBe TenantStatus.SUSPENDED
        val event = tenant.pullDomainEvents().last()
        event.shouldBeInstanceOf<TenantSuspended>()
        (event as TenantSuspended).reason shouldBe "non-payment"
    }

    @Test
    fun `given a Suspended tenant, when reactivated, then status returns to Active`() {
        val tenant = readyToActivate()
        tenant.activate()
        tenant.suspend("non-payment")

        val result = tenant.reactivate()

        result.isValid shouldBe true
        tenant.status shouldBe TenantStatus.ACTIVE
    }

    @Test
    fun `given a Draft tenant, when reactivate is attempted directly, then it is rejected - only a Suspended tenant can reactivate`() {
        val tenant = Tenant.onboard("Acme SMB", TenantSegment.EXTERNAL_B2B, GBP)

        val result = tenant.reactivate()

        result.isValid shouldBe false
        tenant.status shouldBe TenantStatus.DRAFT
    }

    @Test
    fun `given a Closed tenant, when any transition is attempted, then it is rejected - Closed is terminal`() {
        val tenant = readyToActivate()
        tenant.activate()
        tenant.close()

        tenant.status shouldBe TenantStatus.CLOSED
        tenant.suspend("any reason").isValid shouldBe false
        tenant.reactivate().isValid shouldBe false
        tenant.close().isValid shouldBe false
    }

    @Test
    fun `given a Closed tenant, when a Company is added, then it is rejected`() {
        val tenant = readyToActivate()
        tenant.activate()
        tenant.close()

        val result = tenant.addCompany(CompanyId.generate())

        result.isValid shouldBe false
    }

    @Test
    fun `given an Active tenant past its KYB deadline and still Pending, when checked, then the grace period is expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        val justAfterDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(justAfterDeadline) shouldBe true
    }

    @Test
    fun `given an Active tenant within its KYB deadline, when checked, then the grace period is not expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        val wellBeforeDeadline = activatedAt.plus(90, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(wellBeforeDeadline) shouldBe false
    }

    @Test
    fun `given an Active tenant whose KYB became Verified, when checked past the original deadline, then the grace period is not expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordKybOutcome(KybStatus.VERIFIED)

        val afterOriginalDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(afterOriginalDeadline) shouldBe false
    }

    @Test
    fun `given an expired KYB grace period, when the automated sweep suspends it, then a KybGracePeriodExpired event is raised`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.pullDomainEvents()
        val pastDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        val result = tenant.suspendForExpiredKyb(pastDeadline)

        result.isValid shouldBe true
        tenant.status shouldBe TenantStatus.SUSPENDED
        tenant.pullDomainEvents() shouldContain KybGracePeriodExpired(tenant.id, pastDeadline)
    }

    @Test
    fun `given a grace period that has not expired, when the automated sweep is attempted, then it is rejected`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        val result = tenant.suspendForExpiredKyb(activatedAt.plus(30, ChronoUnit.DAYS))

        result.isValid shouldBe false
        tenant.status shouldBe TenantStatus.ACTIVE
    }

    private fun readyToActivate(): Tenant {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        return tenant
    }
}
