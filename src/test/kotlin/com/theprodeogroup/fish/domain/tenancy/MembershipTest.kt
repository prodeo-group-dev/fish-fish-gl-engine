package com.theprodeogroup.fish.domain.tenancy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MembershipTest {

    @Test
    fun `given a new Membership, when granted, then it starts Active with the given role`() {
        val membership = Membership.grant(UserId.generate(), TenantId.generate(), Role.ACCOUNTANT)

        membership.status shouldBe MembershipStatus.ACTIVE
        membership.role shouldBe Role.ACCOUNTANT
    }

    @Test
    fun `given no explicit accessLevel, when each Role is granted, then it defaults to that role's usual access`() {
        Membership.grant(UserId.generate(), TenantId.generate(), Role.OWNER_ADMIN).accessLevel shouldBe AccessLevel.ADMIN
        Membership.grant(UserId.generate(), TenantId.generate(), Role.ACCOUNTANT).accessLevel shouldBe AccessLevel.WRITE
        Membership.grant(UserId.generate(), TenantId.generate(), Role.APPROVER).accessLevel shouldBe AccessLevel.APPROVE
        Membership.grant(UserId.generate(), TenantId.generate(), Role.READ_ONLY).accessLevel shouldBe AccessLevel.READ
        Membership.grant(UserId.generate(), TenantId.generate(), Role.COMPLIANCE_ETHICS_REVIEW).accessLevel shouldBe AccessLevel.READ
    }

    @Test
    fun `given an explicit accessLevel, when granted, then it overrides the role's usual default`() {
        val membership = Membership.grant(UserId.generate(), TenantId.generate(), Role.ACCOUNTANT, AccessLevel.READ)

        membership.role shouldBe Role.ACCOUNTANT
        membership.accessLevel shouldBe AccessLevel.READ
    }

    @Test
    fun `given an active Membership, when revoked, then status becomes Revoked`() {
        val membership = Membership.grant(UserId.generate(), TenantId.generate(), Role.READ_ONLY)

        val result = membership.revoke()

        result.isValid shouldBe true
        membership.status shouldBe MembershipStatus.REVOKED
    }

    @Test
    fun `given a revoked Membership, when revoked again, then it fails`() {
        val membership = Membership.grant(UserId.generate(), TenantId.generate(), Role.READ_ONLY)
        membership.revoke()

        val result = membership.revoke()

        result.isValid shouldBe false
    }

    @Test
    fun `given a revoked Membership, when reactivated, then status becomes Active again`() {
        val membership = Membership.grant(UserId.generate(), TenantId.generate(), Role.APPROVER)
        membership.revoke()

        val result = membership.reactivate()

        result.isValid shouldBe true
        membership.status shouldBe MembershipStatus.ACTIVE
    }

    @Test
    fun `given an already-active Membership, when reactivated, then it fails`() {
        val membership = Membership.grant(UserId.generate(), TenantId.generate(), Role.OWNER_ADMIN)

        val result = membership.reactivate()

        result.isValid shouldBe false
    }
}
