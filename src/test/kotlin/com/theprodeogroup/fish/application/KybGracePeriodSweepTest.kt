package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.KybGracePeriodExpired
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.PhoneNumber
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.domain.tenancy.VerificationStatus
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/** Fakes shared across this package's tests live in `TenancyRepositoryFakes.kt`. */
class KybGracePeriodSweepTest {

    private val tenantRepository = FakeTenantRepository()
    private val sweep = KybGracePeriodSweep(tenantRepository)

    private fun activatedTenant(
        kybStatus: VerificationStatus = VerificationStatus.PENDING,
        adminKycStatus: VerificationStatus = VerificationStatus.PENDING,
        adminPhoneVerificationStatus: VerificationStatus = VerificationStatus.PENDING,
        activatedAt: Instant = Instant.now()
    ): Tenant {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        tenant.recordKybOutcome(kybStatus)
        tenant.recordAdminKycOutcome(adminKycStatus)
        tenant.activate(activatedAt)
        if (adminPhoneVerificationStatus != VerificationStatus.PENDING) {
            tenant.recordAdminPhoneNumber(PhoneNumber("+15550123456"))
            tenant.recordAdminPhoneVerificationOutcome(adminPhoneVerificationStatus)
        }
        tenant.pullDomainEvents() // drain the activation events - the sweep's own events are what we're testing
        tenantRepository.save(tenant)
        return tenant
    }

    @Test
    fun `given a Tenant past its deadline still unverified, when the sweep runs, then it is suspended`() {
        val activatedAt = Instant.now().minus(181, ChronoUnit.DAYS)
        val tenant = activatedTenant(activatedAt = activatedAt)

        val result = sweep.run()

        result.suspended.map { it.tenant.id } shouldContain tenant.id
        tenantRepository.findById(tenant.id)?.status shouldBe TenantStatus.SUSPENDED
    }

    @Test
    fun `given a Tenant past its deadline still unverified, when the sweep runs, then KybGracePeriodExpired is among its events`() {
        val activatedAt = Instant.now().minus(181, ChronoUnit.DAYS)
        val tenant = activatedTenant(activatedAt = activatedAt)

        val result = sweep.run()

        val entry = result.suspended.single { it.tenant.id == tenant.id }
        entry.events.map { it::class } shouldContain KybGracePeriodExpired::class
    }

    @Test
    fun `given a Tenant past its deadline but all three fields Verified - including admin phone, when the sweep runs, then it is left Active`() {
        val activatedAt = Instant.now().minus(181, ChronoUnit.DAYS)
        val tenant = activatedTenant(
            kybStatus = VerificationStatus.VERIFIED,
            adminKycStatus = VerificationStatus.VERIFIED,
            adminPhoneVerificationStatus = VerificationStatus.VERIFIED,
            activatedAt = activatedAt
        )

        val result = sweep.run()

        result.suspended.map { it.tenant.id } shouldBe emptyList()
        tenantRepository.findById(tenant.id)?.status shouldBe TenantStatus.ACTIVE
    }

    @Test
    fun `given KYB and admin KYC Verified but admin phone still Pending past only the 14-day phone deadline, when the sweep runs, then it is suspended - phone is part of KYB`() {
        val activatedAt = Instant.now().minus(15, ChronoUnit.DAYS)
        val tenant = activatedTenant(
            kybStatus = VerificationStatus.VERIFIED,
            adminKycStatus = VerificationStatus.VERIFIED,
            activatedAt = activatedAt
        )

        val result = sweep.run()

        result.suspended.map { it.tenant.id } shouldContain tenant.id
        tenantRepository.findById(tenant.id)?.status shouldBe TenantStatus.SUSPENDED
    }

    @Test
    fun `given a Tenant well within its 180-day grace period, when the sweep runs, then it is left Active`() {
        val tenant = activatedTenant(activatedAt = Instant.now())

        val result = sweep.run()

        result.suspended.map { it.tenant.id } shouldBe emptyList()
        tenantRepository.findById(tenant.id)?.status shouldBe TenantStatus.ACTIVE
    }

    @Test
    fun `given one expired and one healthy Tenant, when the sweep runs, then only the expired one is suspended`() {
        val expired = activatedTenant(activatedAt = Instant.now().minus(200, ChronoUnit.DAYS))
        val healthy = activatedTenant(activatedAt = Instant.now())

        val result = sweep.run()

        result.suspended.map { it.tenant.id } shouldBe listOf(expired.id)
        tenantRepository.findById(healthy.id)?.status shouldBe TenantStatus.ACTIVE
    }
}
