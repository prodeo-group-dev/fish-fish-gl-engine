package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantOnboarded
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserId
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * In-memory stand-ins for the repository interfaces - pure orchestration
 * tests for `OnboardTenantUseCase` shouldn't need a real database (that's
 * already covered by the Exposed `integrationTest` suite for each
 * individual repository, docs/DDD_Design.md Section 10.2-10.4). These
 * fakes don't round-trip through reconstitute() - they just hold the
 * same object reference, which is enough to verify what the use case
 * calls and in what order/state.
 */
private class FakeTenantRepository : TenantRepository {
    /**
     * Snapshots, not raw `Tenant` references - `Tenant` is mutable, so
     * storing the object itself would make every recorded call reflect
     * whatever its *final* state ends up being, not its state at the
     * moment `save()` was actually called.
     */
    data class SaveSnapshot(val status: TenantStatus, val companyIds: Set<CompanyId>)

    val saveCalls = mutableListOf<SaveSnapshot>()
    private val store = mutableMapOf<TenantId, Tenant>()
    override fun save(tenant: Tenant) {
        saveCalls.add(SaveSnapshot(tenant.status, tenant.companyIds))
        store[tenant.id] = tenant
    }
    override fun findById(id: TenantId): Tenant? = store[id]
}

private class FakeCompanyRepository : CompanyRepository {
    private val store = mutableMapOf<CompanyId, Company>()
    override fun save(company: Company) { store[company.id] = company }
    override fun findById(id: CompanyId): Company? = store[id]
    override fun findAllByTenant(tenantId: TenantId): List<Company> = store.values.filter { it.tenantId == tenantId }
}

private class FakeUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, User>()
    override fun save(user: User) { store[user.id] = user }
    override fun findById(id: UserId): User? = store[id]
    override fun findByEmail(email: String): User? = store.values.find { it.email == email }
}

private class FakeMembershipRepository : MembershipRepository {
    private val store = mutableMapOf<MembershipId, Membership>()
    override fun save(membership: Membership) { store[membership.id] = membership }
    override fun findById(id: MembershipId): Membership? = store[id]
    override fun findAllByTenant(tenantId: TenantId): List<Membership> = store.values.filter { it.tenantId == tenantId }
    override fun findAllByUser(userId: UserId): List<Membership> = store.values.filter { it.userId == userId }
}

class OnboardTenantUseCaseTest {

    private val tenantRepository = FakeTenantRepository()
    private val companyRepository = FakeCompanyRepository()
    private val userRepository = FakeUserRepository()
    private val membershipRepository = FakeMembershipRepository()
    private val useCase = OnboardTenantUseCase(tenantRepository, companyRepository, userRepository, membershipRepository)

    private fun validRequest(segment: TenantSegment = TenantSegment.INTERNAL_VENTURE) = OnboardTenantUseCase.Request(
        tenantName = "Purse",
        tenantSegment = segment,
        tenantBaseCurrency = GBP,
        companyName = "Purse UK",
        clientType = ClientType.NON_PROFIT,
        jurisdiction = "GB",
        companyBaseCurrency = GBP,
        adminEmail = "founder@purse.example",
        adminName = "Founding Admin"
    )

    @Test
    fun `given a valid request, when executed, then Tenant Company User and Membership are all created and linked`() {
        val result = useCase.execute(validRequest())

        result.tenant.name shouldBe "Purse"
        result.company.tenantId shouldBe result.tenant.id
        result.adminMembership.userId shouldBe result.adminUser.id
        result.adminMembership.tenantId shouldBe result.tenant.id
        result.adminMembership.role shouldBe Role.OWNER_ADMIN
    }

    @Test
    fun `given a valid request, when executed, then the Tenant is Active with both referenced ID sets populated`() {
        val result = useCase.execute(validRequest())

        result.tenant.status shouldBe TenantStatus.ACTIVE
        result.tenant.companyIds shouldBe setOf(result.company.id)
        result.tenant.adminMembershipIds shouldBe setOf(result.adminMembership.id)
    }

    @Test
    fun `given a valid request, when executed, then every aggregate is persisted via its repository`() {
        val result = useCase.execute(validRequest())

        tenantRepository.findById(result.tenant.id) shouldBe result.tenant
        companyRepository.findById(result.company.id) shouldBe result.company
        userRepository.findById(result.adminUser.id) shouldBe result.adminUser
        membershipRepository.findById(result.adminMembership.id) shouldBe result.adminMembership
    }

    @Test
    fun `given a valid request, when executed, then Tenant is saved twice - once bare, once fully linked`() {
        useCase.execute(validRequest())

        tenantRepository.saveCalls.size shouldBe 2
        tenantRepository.saveCalls[0].companyIds shouldBe emptySet()
        tenantRepository.saveCalls[0].status shouldBe TenantStatus.DRAFT
        tenantRepository.saveCalls[1].companyIds.size shouldBe 1
        tenantRepository.saveCalls[1].status shouldBe TenantStatus.ACTIVE
    }

    @Test
    fun `given a valid request, when executed, then TenantOnboarded is among the returned domain events`() {
        val result = useCase.execute(validRequest())

        result.events.map { it::class } shouldContain TenantOnboarded::class
    }

    @Test
    fun `given an external B2B segment, when executed, then it onboards through the same flow`() {
        val result = useCase.execute(validRequest(segment = TenantSegment.EXTERNAL_B2B))

        result.tenant.segment shouldBe TenantSegment.EXTERNAL_B2B
        result.tenant.status shouldBe TenantStatus.ACTIVE
    }
}
