package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * T15 / G1 on a real Postgres: the repositories' "by Company" queries return only that Company's rows
 * (the in-memory fakes can only fake this, which once hid a gap), and an idempotency key belongs to its
 * Tenant. Two Companies in two Tenants, each with data; neither sees the other's.
 */
class TenantIsolationRepositoriesIntegrationTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val day = LocalDate.of(2026, 8, 21)
    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val fixedAssetRepository = ExposedFixedAssetRepository()
    private val leaveAccrualRepository = ExposedLeaveAccrualRepository()
    private val idempotencyKeyRepository = ExposedIdempotencyKeyRepository()

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

    private class World(val companyId: CompanyId, val accountIds: Set<UUID>, val periodId: UUID, val entryId: UUID, val assetId: UUID, val accrualId: UUID)

    private fun world(label: String): World {
        val company = Company.create(TenantId.generate(), "Isolation $label", ClientType.NON_PROFIT, Jurisdiction.UK, gbp).also { companyRepository.save(it) }
        val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "$label Cash").also { accountRepository.save(it) }
        val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "$label Sales").also { accountRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, day, day.plusDays(30)).also { it.open(); periodRepository.save(it) }
        val entry = JournalEntry.create(
            period.id, day,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("100.00"), gbp), TransactionSide.DEBIT),
                JournalLine(sales.id, Money(BigDecimal("100.00"), gbp), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        ).also { it.post(); journalEntryRepository.save(it) }
        val asset = FixedAsset.create(company.id, "$label Van", AssetCategory.VEHICLES, Money(BigDecimal("1000.00"), gbp), day, 5).also { fixedAssetRepository.save(it) }
        val accrual = LeaveAccrual.create(company.id, EmployeeId.generate(), gbp).also { leaveAccrualRepository.save(it) }
        return World(company.id, setOf(cash.id.value, sales.id.value), period.id.value, entry.id.value, asset.id.value, accrual.id.value)
    }

    @Test
    fun `each by-Company query returns only that Company's rows`() {
        val a = world("A")
        val b = world("B")

        accountRepository.findAllByCompany(a.companyId).map { it.id.value }.toSet() shouldBe a.accountIds
        accountRepository.findAllByCompany(b.companyId).map { it.id.value }.toSet() shouldBe b.accountIds
        periodRepository.findAllByCompany(a.companyId).map { it.id.value } shouldBe listOf(a.periodId)
        periodRepository.findAllByCompany(b.companyId).map { it.id.value } shouldBe listOf(b.periodId)
        // journal_entries has no company column: the query goes through the Company's Periods.
        journalEntryRepository.findAllByCompany(a.companyId).map { it.id.value } shouldBe listOf(a.entryId)
        journalEntryRepository.findAllByCompany(b.companyId).map { it.id.value } shouldBe listOf(b.entryId)
        journalEntryRepository.findAllByPeriod(com.theprodeogroup.fish.domain.ledger.PeriodId(a.periodId)).map { it.id.value } shouldBe listOf(a.entryId)
        fixedAssetRepository.findAllByCompany(a.companyId).map { it.id.value } shouldBe listOf(a.assetId)
        fixedAssetRepository.findAllByCompany(b.companyId).map { it.id.value } shouldBe listOf(b.assetId)
        leaveAccrualRepository.findAllByCompany(a.companyId).map { it.id.value } shouldBe listOf(a.accrualId)
        leaveAccrualRepository.findAllByCompany(b.companyId).map { it.id.value } shouldBe listOf(b.accrualId)
        companyRepository.findAllByTenant(companyRepository.findById(a.companyId)!!.tenantId).map { it.id } shouldBe listOf(a.companyId)
    }

    @Test
    fun `an idempotency key belongs to its Tenant, so the same key in two Tenants never collides or replays`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        val key = UUID.randomUUID().toString()

        idempotencyKeyRepository.insertIfAbsent(IdempotencyRecord(tenantA, "record-sale", key, "fingerprint-a", 200, "response-for-a")) shouldBe true
        // The same key, same endpoint, another Tenant: a fresh key, not a replay of A's response.
        idempotencyKeyRepository.find(tenantB, "record-sale", key) shouldBe null
        idempotencyKeyRepository.insertIfAbsent(IdempotencyRecord(tenantB, "record-sale", key, "fingerprint-b", 200, "response-for-b")) shouldBe true

        idempotencyKeyRepository.find(tenantA, "record-sale", key)?.responseBody shouldBe "response-for-a"
        idempotencyKeyRepository.find(tenantB, "record-sale", key)?.responseBody shouldBe "response-for-b"
    }
}
