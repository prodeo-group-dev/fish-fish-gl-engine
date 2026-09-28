package com.theprodeogroup.fish.infrastructure.ea

import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun membership(isOwnerAdmin: Boolean, companies: List<CompanyAccess> = emptyList()) = CallerMembership(
    tenantId = TenantId.generate(),
    tenantName = "Test Tenant",
    isOwnerAdmin = isOwnerAdmin,
    tenantStatus = "ACTIVE",
    kybStatus = "VERIFIED",
    adminPhoneNumber = null,
    adminPhoneVerificationStatus = "VERIFIED",
    phoneVerificationDeadline = null,
    companies = companies
)

/**
 * Real bug fixed 2026-09-28: [CallerMembership.accessLevelAt] used to
 * return [AccessLevel.NONE] for an Owner-Admin whenever the requested
 * `companyId` was entirely absent from [CallerMembership.companies] -
 * only checking [CallerMembership.isOwnerAdmin] when the company *was*
 * present with a null `accessLevel`. That relied on EA's `GET /me`
 * always enumerating every Tenant-owned Company, which it doesn't when
 * a Company has no matching name record - see `ea_membership_gateway.kt`'s
 * own KDoc.
 */
class CallerMembershipTest {

    @Test
    fun `an Owner-Admin gets READ at a Company entirely absent from companies`() {
        val ownerAdmin = membership(isOwnerAdmin = true, companies = emptyList())

        ownerAdmin.accessLevelAt(CompanyId.generate()) shouldBe AccessLevel.READ
    }

    @Test
    fun `an Owner-Admin gets READ at a Company present with a null accessLevel (the intrinsic-floor entry)`() {
        val companyId = CompanyId.generate()
        val ownerAdmin = membership(isOwnerAdmin = true, companies = listOf(CompanyAccess(companyId, "Co", null, null, emptySet())))

        ownerAdmin.accessLevelAt(companyId) shouldBe AccessLevel.READ
    }

    @Test
    fun `an Owner-Admin's explicit accessLevel at a Company still wins over the intrinsic floor`() {
        val companyId = CompanyId.generate()
        val ownerAdmin = membership(isOwnerAdmin = true, companies = listOf(CompanyAccess(companyId, "Co", null, AccessLevel.WRITE, emptySet())))

        ownerAdmin.accessLevelAt(companyId) shouldBe AccessLevel.WRITE
    }

    @Test
    fun `a non-Owner-Admin gets NONE at a Company entirely absent from companies`() {
        val staff = membership(isOwnerAdmin = false, companies = emptyList())

        staff.accessLevelAt(CompanyId.generate()) shouldBe AccessLevel.NONE
    }

    @Test
    fun `a non-Owner-Admin's explicit accessLevel at a Company is honoured`() {
        val companyId = CompanyId.generate()
        val staff = membership(isOwnerAdmin = false, companies = listOf(CompanyAccess(companyId, "Co", null, AccessLevel.READ, emptySet())))

        staff.accessLevelAt(companyId) shouldBe AccessLevel.READ
    }
}
