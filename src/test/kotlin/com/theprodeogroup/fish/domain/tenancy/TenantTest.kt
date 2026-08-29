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
        tenant.kybStatus shouldBe VerificationStatus.PENDING
        tenant.adminKycStatus shouldBe VerificationStatus.PENDING
        tenant.kybVerificationDeadline shouldBe null
        tenant.adminPhoneNumber shouldBe null
        tenant.adminPhoneVerificationStatus shouldBe VerificationStatus.PENDING
        tenant.phoneVerificationDeadline shouldBe null
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
    fun `given a Draft tenant with a Company and admin Membership, when activated, then it succeeds even though KYB and admin KYC are still Pending`() {
        val tenant = Tenant.onboard("Acme SMB", TenantSegment.EXTERNAL_B2B, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())

        val result = tenant.activate()

        result.isValid shouldBe true
        tenant.status shouldBe TenantStatus.ACTIVE
        tenant.kybStatus shouldBe VerificationStatus.PENDING
        tenant.adminKycStatus shouldBe VerificationStatus.PENDING
    }

    @Test
    fun `given a Draft tenant whose KYB is Flagged, when activation is attempted, then it is rejected`() {
        val tenant = Tenant.onboard("Suspicious Co", TenantSegment.EXTERNAL_B2B, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        tenant.recordKybOutcome(VerificationStatus.FLAGGED)

        val result = tenant.activate()

        result.isValid shouldBe false
        tenant.status shouldBe TenantStatus.DRAFT
    }

    @Test
    fun `given a Draft tenant whose admin KYC is Flagged, when activation is attempted, then it is rejected too`() {
        val tenant = Tenant.onboard("Acme SMB", TenantSegment.EXTERNAL_B2B, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        tenant.recordAdminKycOutcome(VerificationStatus.FLAGGED)

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
    fun `given an Active tenant past its deadline with KYB and admin KYC still Pending, when checked, then the grace period is expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        val justAfterDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(justAfterDeadline) shouldBe true
    }

    @Test
    fun `given an Active tenant within its deadline, when checked, then the grace period is not expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        val wellBeforeDeadline = activatedAt.plus(90, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(wellBeforeDeadline) shouldBe false
    }

    @Test
    fun `given KYB Verified but admin KYC still Pending, when checked past the deadline, then the grace period is still expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordKybOutcome(VerificationStatus.VERIFIED)

        val afterDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(afterDeadline) shouldBe true
    }

    @Test
    fun `given KYB and admin KYC Verified but admin phone still Pending, when checked past the original deadline, then the grace period is still expired - phone is part of KYB`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordKybOutcome(VerificationStatus.VERIFIED)
        tenant.recordAdminKycOutcome(VerificationStatus.VERIFIED)

        val afterOriginalDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(afterOriginalDeadline) shouldBe true
    }

    @Test
    fun `given KYB, admin KYC, and admin phone all Verified, when checked past the original deadline, then the grace period is not expired`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordKybOutcome(VerificationStatus.VERIFIED)
        tenant.recordAdminKycOutcome(VerificationStatus.VERIFIED)
        tenant.recordAdminPhoneNumber(PhoneNumber("+15550123456"))
        tenant.recordAdminPhoneVerificationOutcome(VerificationStatus.VERIFIED)

        val afterOriginalDeadline = activatedAt.plus(181, ChronoUnit.DAYS)

        tenant.isKybGracePeriodExpired(afterOriginalDeadline) shouldBe false
    }

    @Test
    fun `given a successful activation, when the phone deadline is checked, then it is 14 days from activation`() {
        val tenant = readyToActivate()
        val now = Instant.parse("2026-08-11T00:00:00Z")

        tenant.activate(now)

        tenant.phoneVerificationDeadline shouldBe now.plus(14, ChronoUnit.DAYS)
    }

    @Test
    fun `given no phone number recorded, when checked past the 14-day deadline, then phone verification is overdue`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        val justAfterDeadline = activatedAt.plus(15, ChronoUnit.DAYS)

        tenant.isPhoneVerificationOverdue(justAfterDeadline) shouldBe true
    }

    @Test
    fun `given a phone number recorded but not yet verified, when checked within the 14-day deadline, then phone verification is not overdue`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordAdminPhoneNumber(PhoneNumber("+15550123456"))

        val withinDeadline = activatedAt.plus(7, ChronoUnit.DAYS)

        tenant.isPhoneVerificationOverdue(withinDeadline) shouldBe false
    }

    @Test
    fun `given a verified phone number, when checked well past the 14-day deadline, then phone verification is not overdue`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordAdminPhoneNumber(PhoneNumber("+15550123456"))
        tenant.recordAdminPhoneVerificationOutcome(VerificationStatus.VERIFIED)

        val afterDeadline = activatedAt.plus(15, ChronoUnit.DAYS)

        tenant.isPhoneVerificationOverdue(afterDeadline) shouldBe false
    }

    @Test
    fun `given a phone number recorded again, when checked, then verification status resets to Pending - an old number's Verified outcome does not carry over`() {
        val tenant = readyToActivate()
        tenant.activate()
        tenant.recordAdminPhoneNumber(PhoneNumber("+15550123456"))
        tenant.recordAdminPhoneVerificationOutcome(VerificationStatus.VERIFIED)

        tenant.recordAdminPhoneNumber(PhoneNumber("+15559876543"))

        tenant.adminPhoneVerificationStatus shouldBe VerificationStatus.PENDING
    }

    @Test
    fun `given only the phone deadline expired (KYB and admin KYC already Verified), when the automated sweep runs, then it still suspends and raises KybGracePeriodExpired - not a separate event`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)
        tenant.recordKybOutcome(VerificationStatus.VERIFIED)
        tenant.recordAdminKycOutcome(VerificationStatus.VERIFIED)
        tenant.pullDomainEvents()

        val justAfterPhoneDeadline = activatedAt.plus(15, ChronoUnit.DAYS)

        val result = tenant.suspendForExpiredKyb(justAfterPhoneDeadline)

        result.isValid shouldBe true
        tenant.status shouldBe TenantStatus.SUSPENDED
        tenant.pullDomainEvents() shouldContain KybGracePeriodExpired(tenant.id, justAfterPhoneDeadline)
    }

    @Test
    fun `given an expired grace period, when the automated sweep suspends it, then a KybGracePeriodExpired event is raised`() {
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
    fun `given neither the KYB nor the phone grace period has expired, when the automated sweep is attempted, then it is rejected`() {
        val tenant = readyToActivate()
        val activatedAt = Instant.parse("2026-01-01T00:00:00Z")
        tenant.activate(activatedAt)

        // Within both the 14-day phone deadline and the 180-day KYB one -
        // 30 days (the pre-phone-requirement value here) would now trip
        // the phone deadline on its own, so this uses 5 days instead to
        // keep testing "nothing has expired yet" rather than "the KYB
        // deadline specifically hasn't expired" (that's the sweep test
        // above, since the two deadlines differ so much in length now).
        val result = tenant.suspendForExpiredKyb(activatedAt.plus(5, ChronoUnit.DAYS))

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
