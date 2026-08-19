package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedMembershipRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedUserRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * `KybGracePeriodSweepTest` (`src/test`) uses an in-memory fake whose
 * `findAllActive()` is a trivial in-memory filter - it can't prove the
 * real `WHERE status = 'ACTIVE'` query actually excludes Draft/Closed
 * Tenants, or that a suspended Tenant's status genuinely persists.
 * Exercises the same sweep against real Exposed repositories and a live
 * Postgres database (docs/DDD_Design.md Section 10.7). Skips (not fails)
 * if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class KybGracePeriodSweepIntegrationTest {

    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val userRepository = ExposedUserRepository()
    private val membershipRepository = ExposedMembershipRepository()
    private val sweep = KybGracePeriodSweep(tenantRepository)

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

    /**
     * Activates a real, fully-linked Tenant - a real Company and admin
     * Membership (and the User behind it) genuinely saved via their own
     * repositories first, not fabricated IDs. `tenant_companies`/
     * `tenant_admin_memberships` carry real foreign keys to `companies`/
     * `memberships` (docs/DDD_Design.md Section 10.3), so a fabricated
     * `CompanyId.generate()` that was never actually persisted - fine
     * against `KybGracePeriodSweepTest`'s fakes, which enforce nothing -
     * fails loudly here, exactly the class of bug this integration test
     * exists to catch.
     */
    private fun activatedTenant(name: String, activatedAt: Instant): Tenant {
        val tenant = Tenant.onboard(name, TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)

        val company = Company.create(tenant.id, "$name Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val user = User.create("admin-${UUID.randomUUID()}@example.com", "Admin")
        userRepository.save(user)
        val membership = Membership.grant(user.id, tenant.id, Role.OWNER_ADMIN)
        membershipRepository.save(membership)

        tenant.addCompany(company.id)
        tenant.addAdminMembership(membership.id)
        tenant.activate(activatedAt)
        tenantRepository.save(tenant)
        return tenant
    }

    @Test
    fun `given an expired Active Tenant, a Draft Tenant, and a healthy Active Tenant, when the sweep runs against a real database, then only the expired one is suspended`() {
        val expired = activatedTenant("Expired ${UUID.randomUUID()}", Instant.now().minus(181, ChronoUnit.DAYS))

        val draft = Tenant.onboard("Draft ${UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(draft)

        val healthy = activatedTenant("Healthy ${UUID.randomUUID()}", Instant.now())

        val result = sweep.run()

        result.suspended.map { it.tenant.id } shouldContain expired.id
        result.suspended.map { it.tenant.id } shouldNotContain draft.id
        result.suspended.map { it.tenant.id } shouldNotContain healthy.id

        requireNotNull(tenantRepository.findById(expired.id)).status shouldBe TenantStatus.SUSPENDED
        requireNotNull(tenantRepository.findById(draft.id)).status shouldBe TenantStatus.DRAFT
        requireNotNull(tenantRepository.findById(healthy.id)).status shouldBe TenantStatus.ACTIVE
    }
}
