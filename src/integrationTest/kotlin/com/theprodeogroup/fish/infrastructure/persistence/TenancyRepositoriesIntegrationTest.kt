package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.VerificationStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * Every real run hits the same persistent local database (no cleanup
 * between runs) - a fixed string literal email would collide with
 * `users.email`'s UNIQUE constraint on the second run. `CompanyId`/
 * `TenantId.generate()` sidestep this for other aggregates by never
 * repeating; this does the same for the one natural-key field here.
 */
private fun uniqueEmail(label: String): String = "$label-${UUID.randomUUID()}@example.com"

/**
 * Verifies the Tenancy repositories genuinely round-trip through a real
 * Postgres database (docs/DDD_Design.md Section 10.3), same discipline as
 * `CoreLedgerRepositoriesIntegrationTest`. Skips (not fails) if
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class TenancyRepositoriesIntegrationTest {

    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val userRepository = ExposedUserRepository()
    private val membershipRepository = ExposedMembershipRepository()

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
    fun `given a User, when saved and reloaded by id and by email, then both return the same identity`() {
        val email = uniqueEmail("jane")
        val user = User.create(email, "Jane Doe")
        userRepository.save(user)

        val byId = requireNotNull(userRepository.findById(user.id))
        val byEmail = requireNotNull(userRepository.findByEmail(email))

        byId.id shouldBe user.id
        byId.email shouldBe email
        byId.name shouldBe "Jane Doe"
        byEmail.id shouldBe user.id
    }

    @Test
    fun `given a Company with a non-default going-concern status, when saved and reloaded, then every field round-trips`() {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        company.flagSubstantialDoubt()

        companyRepository.save(company)
        val reloaded = requireNotNull(companyRepository.findById(company.id))

        reloaded.id shouldBe company.id
        reloaded.tenantId shouldBe tenant.id
        reloaded.name shouldBe "Purse UK"
        reloaded.clientType shouldBe ClientType.NON_PROFIT
        reloaded.jurisdiction shouldBe "GB"
        reloaded.baseCurrency shouldBe GBP
        reloaded.goingConcernStatus shouldBe company.goingConcernStatus
    }

    @Test
    fun `given a Membership that has been revoked, when saved and reloaded, then the status round-trips`() {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val user = User.create(uniqueEmail("admin"), "Admin User")
        userRepository.save(user)
        val membership = Membership.grant(user.id, tenant.id, Role.OWNER_ADMIN)
        membership.revoke()

        membershipRepository.save(membership)
        val reloaded = requireNotNull(membershipRepository.findById(membership.id))

        reloaded.userId shouldBe user.id
        reloaded.tenantId shouldBe tenant.id
        reloaded.role shouldBe Role.OWNER_ADMIN
        reloaded.status shouldBe membership.status
    }

    @Test
    fun `given an activated Tenant with a Company and admin Membership, when saved and reloaded, then the referenced ID sets round-trip`() {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        val company = Company.create(tenant.id, "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        val user = User.create(uniqueEmail("founder"), "Founder")
        val membership = Membership.grant(user.id, tenant.id, Role.OWNER_ADMIN)

        tenantRepository.save(tenant)
        companyRepository.save(company)
        userRepository.save(user)
        membershipRepository.save(membership)

        tenant.addCompany(company.id)
        tenant.addAdminMembership(membership.id)
        tenant.recordKybOutcome(VerificationStatus.VERIFIED)
        tenant.recordAdminKycOutcome(VerificationStatus.VERIFIED)
        tenant.activate()

        tenantRepository.save(tenant)
        val reloaded = requireNotNull(tenantRepository.findById(tenant.id))

        reloaded.id shouldBe tenant.id
        reloaded.name shouldBe "Purse"
        reloaded.segment shouldBe TenantSegment.INTERNAL_VENTURE
        reloaded.baseCurrency shouldBe GBP
        reloaded.status shouldBe tenant.status
        reloaded.kybStatus shouldBe VerificationStatus.VERIFIED
        reloaded.adminKycStatus shouldBe VerificationStatus.VERIFIED
        // Truncate to millis, not just Postgres TIMESTAMP's own microsecond
        // precision - the pgjdbc driver has a known +-1-microsecond rounding
        // artifact converting Instant to its backend representation, and
        // millisecond precision is already far finer than this field's real
        // business meaning (a 180-day grace-period deadline) ever needs.
        reloaded.kybVerificationDeadline?.truncatedTo(ChronoUnit.MILLIS) shouldBe
            tenant.kybVerificationDeadline?.truncatedTo(ChronoUnit.MILLIS)
        reloaded.companyIds shouldBe setOf(company.id)
        reloaded.adminMembershipIds shouldBe setOf(membership.id)
    }

    @Test
    fun `given a Tenant is saved twice after adding a second Company, when reloaded, then both Companies are present`() {
        val tenant = Tenant.onboard("Osusu", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val companyA = Company.create(tenant.id, "Osusu Group A", ClientType.NON_PROFIT, "GB", GBP)
        val companyB = Company.create(tenant.id, "Osusu Group B", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(companyA)
        companyRepository.save(companyB)

        tenant.addCompany(companyA.id)
        tenantRepository.save(tenant)
        tenant.addCompany(companyB.id)
        tenantRepository.save(tenant)

        val reloaded = requireNotNull(tenantRepository.findById(tenant.id))

        reloaded.companyIds shouldBe setOf(companyA.id, companyB.id)
    }

    @Test
    fun `given two Companies under one Tenant, when found by tenant, then both are returned`() {
        val tenant = Tenant.onboard("Scrip", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val companyA = Company.create(tenant.id, "Scrip Treasury", ClientType.NON_PROFIT, "GB", GBP)
        val companyB = Company.create(tenant.id, "Scrip Investments", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(companyA)
        companyRepository.save(companyB)

        val found = companyRepository.findAllByTenant(tenant.id)

        found.map { it.id }.toSet() shouldBe setOf(companyA.id, companyB.id)
    }

    @Test
    fun `given a User with two Memberships across Tenants, when found by user, then both are returned`() {
        val tenantA = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        val tenantB = Tenant.onboard("Scrip", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenantA)
        tenantRepository.save(tenantB)
        val user = User.create(uniqueEmail("multi"), "Multi Tenant User")
        userRepository.save(user)
        val membershipA = Membership.grant(user.id, tenantA.id, Role.ACCOUNTANT)
        val membershipB = Membership.grant(user.id, tenantB.id, Role.READ_ONLY)
        membershipRepository.save(membershipA)
        membershipRepository.save(membershipB)

        val found = membershipRepository.findAllByUser(user.id)

        found.map { it.id }.toSet() shouldBe setOf(membershipA.id, membershipB.id)
    }
}
