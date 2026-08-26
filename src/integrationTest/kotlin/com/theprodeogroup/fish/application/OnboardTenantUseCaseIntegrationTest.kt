package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedMembershipRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedUserRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * The unit-level `OnboardTenantUseCaseTest` (`src/test`) uses in-memory
 * fakes that can't catch a real foreign-key ordering mistake - they don't
 * enforce any constraints at all. This exercises the exact same use case
 * against real Exposed repositories and a live Postgres database, to
 * genuinely prove the two-phase Tenant save (docs/DDD_Design.md Section
 * 10.5) actually satisfies both directions of the FK cycle described in
 * `OnboardTenantUseCase`'s own KDoc. Skips (not fails) if
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set, same precedent as every
 * other `integrationTest` class.
 */
class OnboardTenantUseCaseIntegrationTest {

    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val userRepository = ExposedUserRepository()
    private val membershipRepository = ExposedMembershipRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val useCase = OnboardTenantUseCase(
        tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository
    )

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
    fun `given a valid request, when executed against a real database, then every aggregate round-trips fully linked`() {
        val request = OnboardTenantUseCase.Request(
            tenantName = "Integration Test Venture ${UUID.randomUUID()}",
            tenantSegment = TenantSegment.INTERNAL_VENTURE,
            tenantBaseCurrency = GBP,
            companyName = "Integration Test Co",
            clientType = ClientType.NON_PROFIT,
            jurisdiction = "GB",
            companyBaseCurrency = GBP,
            adminEmail = "founder-${UUID.randomUUID()}@example.com",
            adminName = "Founding Admin"
        )

        val result = useCase.execute(request)

        val reloadedTenant = requireNotNull(tenantRepository.findById(result.tenant.id))
        reloadedTenant.status shouldBe TenantStatus.ACTIVE
        reloadedTenant.companyIds shouldBe setOf(result.company.id)
        reloadedTenant.adminMembershipIds shouldBe setOf(result.adminMembership.id)

        val reloadedCompany = requireNotNull(companyRepository.findById(result.company.id))
        reloadedCompany.tenantId shouldBe result.tenant.id

        val reloadedMembership = requireNotNull(membershipRepository.findById(result.adminMembership.id))
        reloadedMembership.tenantId shouldBe result.tenant.id
        reloadedMembership.userId shouldBe result.adminUser.id

        val reloadedAccounts = accountRepository.findAllByCompany(result.company.id)
        reloadedAccounts.size shouldBe result.chartOfAccounts.size
        reloadedAccounts.map { it.code }.toSet() shouldBe result.chartOfAccounts.map { it.code }.toSet()

        val reloadedPeriod = requireNotNull(periodRepository.findById(result.openingPeriod.id))
        reloadedPeriod.companyId shouldBe result.company.id
        reloadedPeriod.allowsPosting() shouldBe true
    }

    @Test
    fun `given an opening cash balance, when executed against a real database, then the opening-balance JournalEntry round-trips`() {
        val request = OnboardTenantUseCase.Request(
            tenantName = "Integration Test Venture ${UUID.randomUUID()}",
            tenantSegment = TenantSegment.INTERNAL_VENTURE,
            tenantBaseCurrency = GBP,
            companyName = "Integration Test Co",
            clientType = ClientType.NON_PROFIT,
            jurisdiction = "GB",
            companyBaseCurrency = GBP,
            adminEmail = "founder-${UUID.randomUUID()}@example.com",
            adminName = "Founding Admin",
            openingCashBalance = BigDecimal("1000.00")
        )

        val result = useCase.execute(request)

        val entry = requireNotNull(result.openingBalanceEntry)
        val reloadedEntry = requireNotNull(journalEntryRepository.findById(entry.id))
        reloadedEntry.lines.size shouldBe 2
        val debitLine = reloadedEntry.lines.single { it.side == TransactionSide.DEBIT }
        debitLine.amount shouldBe Money(BigDecimal("1000.00"), GBP)

        // `hasPostedActivity` is internal (no friend-module access from this
        // custom source set) - already covered thoroughly by the fake-backed
        // unit tests in `src/test`, which do have that access. This test's
        // job is the real-database round-trip, not that in-memory flag.
        accountRepository.findAllByCompany(result.company.id).size shouldBe result.chartOfAccounts.size
    }
}
