package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedLeaveAccrualRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 25)

/**
 * `RemeasureLeaveAccrualUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.18). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class RemeasureLeaveAccrualUseCaseIntegrationTest {

    private val leaveAccrualRepository = ExposedLeaveAccrualRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = RemeasureLeaveAccrualUseCase(
        leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository
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
    fun `given a LeaveAccrual remeasured twice against a real database, then the embedded Provision round-trips correctly both times`() {
        // A real, persisted Company - leave_accruals.company_id carries a
        // real FK (see PostPurchaseOrderUseCaseIntegrationTest's own
        // note - the same class of bug this construction avoids).
        val tenant = Tenant.onboard("LeaveAccrual Test Tenant ${java.util.UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "LeaveAccrual Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val expense = Account.create(companyId, AccountType.EXPENSE, null, "6100", "Leave Expense")
        val liability = Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2400", "Accrued Leave")
        accountRepository.save(expense)
        accountRepository.save(liability)
        val accrual = LeaveAccrual.create(companyId, EmployeeId.generate(), GBP)
        leaveAccrualRepository.save(accrual)

        val firstResult = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("400.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )
        val firstSuccess = firstResult.shouldBeInstanceOf<RemeasureLeaveAccrualResult.Success>()

        val reloadedAfterFirst = requireNotNull(leaveAccrualRepository.findById(accrual.id))
        reloadedAfterFirst.balance shouldBe Money(BigDecimal("400.00"), GBP)
        val firstEntry = requireNotNull(journalEntryRepository.findById(firstSuccess.journalEntry.id))
        firstEntry.status shouldBe PostingStatus.POSTED

        // Second remeasurement, against the *reloaded* aggregate - proves
        // Provision.reconstitute() genuinely restored a usable balance,
        // not just a value that happened to print correctly.
        val secondUseCase = RemeasureLeaveAccrualUseCase(
            leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository
        )
        val secondResult = secondUseCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("600.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )
        val secondSuccess = secondResult.shouldBeInstanceOf<RemeasureLeaveAccrualResult.Success>()

        val reloadedAfterSecond = requireNotNull(leaveAccrualRepository.findById(accrual.id))
        reloadedAfterSecond.balance shouldBe Money(BigDecimal("600.00"), GBP)
        val secondEntry = requireNotNull(journalEntryRepository.findById(secondSuccess.journalEntry.id))
        secondEntry.status shouldBe PostingStatus.POSTED
    }
}
