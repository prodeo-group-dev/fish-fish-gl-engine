package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * Verifies `ExposedIdempotencyKeyRepository` genuinely round-trips
 * through a real Postgres database (`V7__idempotency_keys.sql`,
 * docs/GL_Production_Readiness_Plan.md), and - the one thing a fake
 * can't prove - that the unique index on (tenant_id, endpoint,
 * idempotency_key) actually enforces itself at the database layer, not
 * just in `FakeIdempotencyKeyRepository`'s in-memory map. Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set, matching
 * every other `integrationTest` class.
 */
class IdempotencyKeyRepositoryIntegrationTest {

    private val idempotencyKeyRepository = ExposedIdempotencyKeyRepository()
    private 
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

    private fun realTenantId(): TenantId {
        val tenant = TenantId.generate()
        return tenant
    }

    @Test
    fun `given a new key, when insertIfAbsent is called, then it returns true and the record is findable afterward`() {
        val tenantId = realTenantId()
        val record = IdempotencyRecord(tenantId, "record-sale", UUID.randomUUID().toString(), "fingerprint-1", 200, """{"ok":true}""")

        val inserted = idempotencyKeyRepository.insertIfAbsent(record)

        inserted shouldBe true
        val found = idempotencyKeyRepository.find(tenantId, "record-sale", record.idempotencyKey)
        found?.requestFingerprint shouldBe "fingerprint-1"
        found?.responseStatusCode shouldBe 200
        found?.responseBody shouldBe """{"ok":true}"""
    }

    @Test
    fun `given a key already inserted, when insertIfAbsent is called again with the same key, then it returns false and does not overwrite the original`() {
        val tenantId = realTenantId()
        val idempotencyKey = UUID.randomUUID().toString()
        idempotencyKeyRepository.insertIfAbsent(IdempotencyRecord(tenantId, "record-sale", idempotencyKey, "fingerprint-1", 200, "first"))

        val insertedAgain = idempotencyKeyRepository.insertIfAbsent(
            IdempotencyRecord(tenantId, "record-sale", idempotencyKey, "fingerprint-2", 200, "second")
        )

        insertedAgain shouldBe false
        val found = idempotencyKeyRepository.find(tenantId, "record-sale", idempotencyKey)
        found?.responseBody shouldBe "first"
    }

    @Test
    fun `given no record for a key, when found, then it returns null`() {
        val tenantId = realTenantId()

        val found = idempotencyKeyRepository.find(tenantId, "record-sale", UUID.randomUUID().toString())

        found shouldBe null
    }

    @Test
    fun `given the same idempotency key used on two different endpoints, when found, then each endpoint has its own independent record`() {
        val tenantId = realTenantId()
        val idempotencyKey = UUID.randomUUID().toString()
        idempotencyKeyRepository.insertIfAbsent(IdempotencyRecord(tenantId, "record-sale", idempotencyKey, "fp", 200, "sale-response"))

        val insertedForOtherEndpoint = idempotencyKeyRepository.insertIfAbsent(
            IdempotencyRecord(tenantId, "record-collection", idempotencyKey, "fp", 200, "collection-response")
        )

        insertedForOtherEndpoint shouldBe true
        idempotencyKeyRepository.find(tenantId, "record-sale", idempotencyKey)?.responseBody shouldBe "sale-response"
        idempotencyKeyRepository.find(tenantId, "record-collection", idempotencyKey)?.responseBody shouldBe "collection-response"
    }

    @Test
    fun `given the same idempotency key used by two different Tenants, when found, then each Tenant has its own independent record`() {
        val tenantIdA = realTenantId()
        val tenantIdB = realTenantId()
        val idempotencyKey = UUID.randomUUID().toString()
        idempotencyKeyRepository.insertIfAbsent(IdempotencyRecord(tenantIdA, "record-sale", idempotencyKey, "fp", 200, "tenant-a-response"))

        val insertedForOtherTenant = idempotencyKeyRepository.insertIfAbsent(
            IdempotencyRecord(tenantIdB, "record-sale", idempotencyKey, "fp", 200, "tenant-b-response")
        )

        insertedForOtherTenant shouldBe true
        idempotencyKeyRepository.find(tenantIdA, "record-sale", idempotencyKey)?.responseBody shouldBe "tenant-a-response"
        idempotencyKeyRepository.find(tenantIdB, "record-sale", idempotencyKey)?.responseBody shouldBe "tenant-b-response"
    }
}
