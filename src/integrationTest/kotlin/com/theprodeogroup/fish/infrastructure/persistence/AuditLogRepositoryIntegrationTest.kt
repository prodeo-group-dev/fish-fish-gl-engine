package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.audit.AuditLogEntry
import com.theprodeogroup.fish.domain.common.AuditAction
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * Verifies [ExposedAuditLogRepository] genuinely round-trips through a
 * real Postgres database, same discipline as
 * `EcosystemRepositoriesIntegrationTest`. Skips (not fails) if
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class AuditLogRepositoryIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val auditLogRepository = ExposedAuditLogRepository()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    private fun newCompany(): com.theprodeogroup.fish.domain.tenancy.CompanyId {
        val tenant = TenantId.generate()
        val company = Company.create(tenant, "Audit Test Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        return company.id
    }

    @Test
    fun `given a saved entry, when found by company, then every field round-trips`() {
        val companyId = newCompany()
        val entry = AuditLogEntry.create(
            tenantId = TenantId.generate(),
            companyId = companyId,
            actorEmail = "founder@example.com",
            action = AuditAction.POSTED,
            entityType = "JournalEntry",
            entityId = "abc-123",
            detail = "Posted a sale",
            occurredAt = Instant.parse("2026-10-03T12:00:00Z")
        )

        auditLogRepository.save(entry)
        val result = auditLogRepository.findByCompany(companyId)

        result.items.size shouldBe 1
        val loaded = result.items.single()
        loaded.id shouldBe entry.id
        loaded.tenantId shouldBe entry.tenantId
        loaded.companyId shouldBe companyId
        loaded.actorEmail shouldBe "founder@example.com"
        loaded.action shouldBe AuditAction.POSTED
        loaded.entityType shouldBe "JournalEntry"
        loaded.entityId shouldBe "abc-123"
        loaded.detail shouldBe "Posted a sale"
        loaded.occurredAt shouldBe entry.occurredAt
    }

    @Test
    fun `given entries for two Companies, when found by company, then only the requested Company's entries come back`() {
        val companyA = newCompany()
        val companyB = newCompany()
        auditLogRepository.save(entryFor(companyA, "JournalEntry", "1"))
        auditLogRepository.save(entryFor(companyB, "JournalEntry", "2"))

        val result = auditLogRepository.findByCompany(companyA)

        result.items.size shouldBe 1
        result.items.single().companyId shouldBe companyA
    }

    @Test
    fun `given entries at different times, when found by company, then they come back newest-first`() {
        val companyId = newCompany()
        val older = entryFor(companyId, "JournalEntry", "old", Instant.parse("2026-01-01T00:00:00Z"))
        val newer = entryFor(companyId, "JournalEntry", "new", Instant.parse("2026-06-01T00:00:00Z"))
        auditLogRepository.save(older)
        auditLogRepository.save(newer)

        val result = auditLogRepository.findByCompany(companyId)

        result.items.map { it.entityId } shouldBe listOf("new", "old")
    }

    @Test
    fun `given a date range filter, when found by company, then only entries within range come back`() {
        val companyId = newCompany()
        auditLogRepository.save(entryFor(companyId, "JournalEntry", "jan", Instant.parse("2026-01-15T00:00:00Z")))
        auditLogRepository.save(entryFor(companyId, "JournalEntry", "jun", Instant.parse("2026-06-15T00:00:00Z")))
        auditLogRepository.save(entryFor(companyId, "JournalEntry", "dec", Instant.parse("2026-12-15T00:00:00Z")))

        val result = auditLogRepository.findByCompany(
            companyId,
            from = Instant.parse("2026-03-01T00:00:00Z"),
            to = Instant.parse("2026-09-01T00:00:00Z")
        )

        result.items.map { it.entityId } shouldBe listOf("jun")
    }

    @Test
    fun `given an entityType filter, when found by company, then only matching entries come back`() {
        val companyId = newCompany()
        auditLogRepository.save(entryFor(companyId, "JournalEntry", "1"))
        auditLogRepository.save(entryFor(companyId, "Account", "2"))

        val result = auditLogRepository.findByCompany(companyId, entityType = "Account")

        result.items.size shouldBe 1
        result.items.single().entityType shouldBe "Account"
    }

    @Test
    fun `given an action filter, when found by company, then only matching entries come back`() {
        val companyId = newCompany()
        auditLogRepository.save(entryFor(companyId, "JournalEntry", "1", action = AuditAction.POSTED))
        auditLogRepository.save(entryFor(companyId, "JournalEntry", "2", action = AuditAction.REVERSED))

        val result = auditLogRepository.findByCompany(companyId, action = AuditAction.REVERSED)

        result.items.size shouldBe 1
        result.items.single().action shouldBe AuditAction.REVERSED
    }

    @Test
    fun `given more entries than the page limit, when found by company, then pagination reports the real total`() {
        val companyId = newCompany()
        repeat(3) { auditLogRepository.save(entryFor(companyId, "JournalEntry", "$it")) }

        val result = auditLogRepository.findByCompany(companyId, limit = 2, offset = 0)

        result.items.size shouldBe 2
        result.total shouldBe 3
        result.hasMore shouldBe true
    }

    private fun entryFor(
        companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId,
        entityType: String,
        entityId: String,
        occurredAt: Instant = Instant.now(),
        action: AuditAction = AuditAction.POSTED
    ): AuditLogEntry = AuditLogEntry.create(
        tenantId = TenantId.generate(),
        companyId = companyId,
        actorEmail = "founder@example.com",
        action = action,
        entityType = entityType,
        entityId = entityId,
        occurredAt = occurredAt
    )
}
