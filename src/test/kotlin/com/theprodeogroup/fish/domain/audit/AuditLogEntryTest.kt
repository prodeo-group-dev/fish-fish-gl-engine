package com.theprodeogroup.fish.domain.audit

import com.theprodeogroup.fish.domain.common.AuditAction
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class AuditLogEntryTest {

    @Test
    fun `given valid input, when created, then every field round-trips`() {
        val tenantId = TenantId.generate()
        val companyId = CompanyId.generate()
        val occurredAt = Instant.parse("2026-10-03T12:00:00Z")

        val entry = AuditLogEntry.create(
            tenantId = tenantId,
            companyId = companyId,
            actorEmail = "founder@example.com",
            action = AuditAction.POSTED,
            entityType = "JournalEntry",
            entityId = "a1b2c3",
            detail = "Posted a sale",
            occurredAt = occurredAt
        )

        entry.tenantId shouldBe tenantId
        entry.companyId shouldBe companyId
        entry.actorEmail shouldBe "founder@example.com"
        entry.action shouldBe AuditAction.POSTED
        entry.entityType shouldBe "JournalEntry"
        entry.entityId shouldBe "a1b2c3"
        entry.detail shouldBe "Posted a sale"
        entry.occurredAt shouldBe occurredAt
    }

    @Test
    fun `given no detail supplied, when created, then detail is null`() {
        val entry = AuditLogEntry.create(
            tenantId = TenantId.generate(),
            companyId = CompanyId.generate(),
            actorEmail = "founder@example.com",
            action = AuditAction.CREATED,
            entityType = "Account",
            entityId = "1100"
        )

        entry.detail shouldBe null
    }

    @Test
    fun `given a blank actorEmail, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            AuditLogEntry.create(
                tenantId = TenantId.generate(),
                companyId = CompanyId.generate(),
                actorEmail = "",
                action = AuditAction.CREATED,
                entityType = "Account",
                entityId = "1100"
            )
        }
    }

    @Test
    fun `given a blank entityType, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            AuditLogEntry.create(
                tenantId = TenantId.generate(),
                companyId = CompanyId.generate(),
                actorEmail = "founder@example.com",
                action = AuditAction.CREATED,
                entityType = "",
                entityId = "1100"
            )
        }
    }

    @Test
    fun `given a blank entityId, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            AuditLogEntry.create(
                tenantId = TenantId.generate(),
                companyId = CompanyId.generate(),
                actorEmail = "founder@example.com",
                action = AuditAction.CREATED,
                entityType = "Account",
                entityId = ""
            )
        }
    }

    @Test
    fun `given a system-generated posting, when created with the system actor, then it is attributed to the well-known sentinel`() {
        val entry = AuditLogEntry.create(
            tenantId = TenantId.generate(),
            companyId = CompanyId.generate(),
            actorEmail = AuditLogEntry.SYSTEM_ACTOR_EMAIL,
            action = AuditAction.SYSTEM_GENERATED,
            entityType = "JournalEntry",
            entityId = "dep-run-1"
        )

        entry.actorEmail shouldBe "system@theprodeogroup.com"
    }
}
