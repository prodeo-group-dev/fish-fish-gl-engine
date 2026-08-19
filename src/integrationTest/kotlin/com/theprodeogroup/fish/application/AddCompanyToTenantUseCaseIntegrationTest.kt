package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedMembershipRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedUserRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * `AddCompanyToTenantUseCaseTest` (`src/test`) uses in-memory fakes that
 * enforce no constraints - they can't catch a real foreign-key ordering
 * mistake. This exercises the same use case against real Exposed
 * repositories and a live Postgres database (docs/DDD_Design.md Section
 * 10.6), same discipline as `OnboardTenantUseCaseIntegrationTest`. Skips
 * (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class AddCompanyToTenantUseCaseIntegrationTest {

    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = AddCompanyToTenantUseCase(tenantRepository, companyRepository)

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping (see docs/DDD_Design.md Section 10 for local setup)"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    @Test
    fun `given an already-persisted Active Tenant, when a Company is added against a real database, then it round-trips fully linked`() {
        val tenant = Tenant.onboard("Integration Test Venture ${UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val bootstrap = OnboardTenantUseCase(
            tenantRepository, companyRepository, ExposedUserRepository(), ExposedMembershipRepository()
        ).execute(
            OnboardTenantUseCase.Request(
                tenantName = tenant.name, tenantSegment = tenant.segment, tenantBaseCurrency = GBP,
                companyName = "Bootstrap Company", clientType = ClientType.NON_PROFIT, jurisdiction = "GB",
                companyBaseCurrency = GBP, adminEmail = "founder-${UUID.randomUUID()}@example.com", adminName = "Founder"
            )
        )
        val activeTenant = bootstrap.tenant

        val result = useCase.execute(
            AddCompanyToTenantUseCase.Request(
                tenantId = activeTenant.id, companyName = "Sierra Leone Entity",
                clientType = ClientType.NON_PROFIT, jurisdiction = "SL", companyBaseCurrency = Currency.getInstance("SLE")
            )
        )

        checkNotNull(result)
        val reloadedTenant = requireNotNull(tenantRepository.findById(activeTenant.id))
        reloadedTenant.companyIds shouldBe setOf(bootstrap.company.id, result.company.id)

        val reloadedCompany = requireNotNull(companyRepository.findById(result.company.id))
        reloadedCompany.tenantId shouldBe activeTenant.id
        reloadedCompany.jurisdiction shouldBe "SL"
    }
}
