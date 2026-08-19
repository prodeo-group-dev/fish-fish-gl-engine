package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantOnboarded
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/** Fakes shared across this package's use-case tests live in `TenancyRepositoryFakes.kt`. */
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
