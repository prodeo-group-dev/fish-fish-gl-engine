package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.MembershipStatus
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationResult
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

class InviteStaffMemberUseCaseTest {

    private val tenantRepository = FakeTenantRepository()
    private val userRepository = FakeUserRepository()
    private val membershipRepository = FakeMembershipRepository()
    private val notificationGateway = FakeStaffInviteNotificationGateway()
    private val useCase = InviteStaffMemberUseCase(tenantRepository, userRepository, membershipRepository, notificationGateway)

    private fun activeTenant(): Tenant {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        tenant.activate()
        tenantRepository.save(tenant)
        return tenant
    }

    private fun request(
        tenantId: TenantId,
        email: String = "accountant@example.com",
        role: Role = Role.ACCOUNTANT,
        modules: Set<ManagedModule> = setOf(ManagedModule.GL)
    ) =
        InviteStaffMemberUseCase.Request(
            tenantId = tenantId,
            email = email,
            name = "Jane Accountant",
            role = role,
            inviterName = "Femi Falase",
            modules = modules
        )

    @Test
    fun `given a nonexistent Tenant, when executed, then it returns TenantNotFound and saves nothing`() {
        val result = useCase.execute(request(TenantId.generate()))

        result shouldBe InviteStaffMemberUseCase.Result.TenantNotFound
        membershipRepository.findAllByTenant(TenantId.generate()) shouldBe emptyList()
    }

    @Test
    fun `given a brand-new email, when invited, then a new User and an ACTIVE Membership with the requested Role are granted`() {
        val tenant = activeTenant()

        val result = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited

        result.user.email shouldBe "accountant@example.com"
        result.membership.userId shouldBe result.user.id
        result.membership.tenantId shouldBe tenant.id
        result.membership.role shouldBe Role.ACCOUNTANT
        result.membership.status shouldBe MembershipStatus.ACTIVE
        result.alreadyMember shouldBe false
        membershipRepository.findAllByTenant(tenant.id) shouldBe listOf(result.membership)
    }

    @Test
    fun `given a request restricted to specific modules, when invited, then the Membership is granted exactly those modules`() {
        val tenant = activeTenant()

        val result = useCase.execute(
            request(tenant.id, modules = setOf(ManagedModule.HR, ManagedModule.IM))
        ) as InviteStaffMemberUseCase.Result.Invited

        result.membership.grantedModules shouldBe setOf(ManagedModule.HR, ManagedModule.IM)
    }

    @Test
    fun `given a REVOKED Membership reactivated by re-invite, then its originally-granted modules are unchanged - not replaced by the new request`() {
        val tenant = activeTenant()
        val firstInvite = useCase.execute(request(tenant.id, modules = setOf(ManagedModule.GL))) as InviteStaffMemberUseCase.Result.Invited
        firstInvite.membership.revoke()
        membershipRepository.save(firstInvite.membership)

        val secondInvite = useCase.execute(
            request(tenant.id, modules = setOf(ManagedModule.HR, ManagedModule.SOP))
        ) as InviteStaffMemberUseCase.Result.Invited

        secondInvite.membership.grantedModules shouldBe setOf(ManagedModule.GL)
    }

    @Test
    fun `given an email that already has a User elsewhere, when invited, then that existing User is reused, not duplicated`() {
        val tenant = activeTenant()
        val firstInvite = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited

        val otherTenant = activeTenant()
        val secondInvite = useCase.execute(request(otherTenant.id)) as InviteStaffMemberUseCase.Result.Invited

        secondInvite.user.id shouldBe firstInvite.user.id
        userRepository.findByEmail("accountant@example.com") shouldBe firstInvite.user
    }

    @Test
    fun `given an email already ACTIVE in this Tenant, when re-invited, then the same Membership is returned and alreadyMember is true`() {
        val tenant = activeTenant()
        val firstInvite = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited

        val secondInvite = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited

        secondInvite.membership.id shouldBe firstInvite.membership.id
        secondInvite.alreadyMember shouldBe true
        membershipRepository.findAllByTenant(tenant.id) shouldBe listOf(firstInvite.membership)
    }

    @Test
    fun `given a REVOKED Membership for this email in this Tenant, when re-invited, then it is reactivated rather than duplicated`() {
        val tenant = activeTenant()
        val firstInvite = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited
        firstInvite.membership.revoke()
        membershipRepository.save(firstInvite.membership)

        val secondInvite = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited

        secondInvite.membership.id shouldBe firstInvite.membership.id
        secondInvite.membership.status shouldBe MembershipStatus.ACTIVE
        secondInvite.alreadyMember shouldBe false
        membershipRepository.findAllByTenant(tenant.id) shouldBe listOf(firstInvite.membership)
    }

    @Test
    fun `given a successful invite, then the notification gateway is called with the invited email`() {
        val tenant = activeTenant()

        useCase.execute(request(tenant.id))

        notificationGateway.sentTo shouldBe listOf("accountant@example.com")
    }

    @Test
    fun `given the notification gateway fails, when invited, then the Membership is still granted - the invite doesn't fail`() {
        notificationGateway.alwaysFail()
        val tenant = activeTenant()

        val result = useCase.execute(request(tenant.id)) as InviteStaffMemberUseCase.Result.Invited

        result.membership.status shouldBe MembershipStatus.ACTIVE
        result.notification shouldBe StaffInviteNotificationResult.Failure("test gateway configured to fail")
        membershipRepository.findAllByTenant(tenant.id) shouldBe listOf(result.membership)
    }
}
