package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
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
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedLeaveAccrualRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
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
 * `UtilizeLeaveAccrualUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.18). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class UtilizeLeaveAccrualUseCaseIntegrationTest {

    private val leaveAccrualRepository = ExposedLeaveAccrualRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val remeasureUseCase = RemeasureLeaveAccrualUseCase(
        leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository
    )
    private val utilizeUseCase = UtilizeLeaveAccrualUseCase(
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
    fun `given an accrued LeaveAccrual against a real database, when leave is utilized, then the balance draws down correctly`() {
        // A real, persisted Company - same construction as
        // RemeasureLeaveAccrualUseCaseIntegrationTest, avoiding the
        // fabricated-CompanyId bug class already caught repeatedly.
        val tenant = TenantId.generate()
        val company = Company.create(tenant, "Utilize LeaveAccrual Test Co", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val expense = Account.create(companyId, AccountType.EXPENSE, null, "6100", "Leave Expense")
        val liability = Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2400", "Accrued Leave")
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        accountRepository.save(expense)
        accountRepository.save(liability)
        accountRepository.save(cash)
        val accrual = LeaveAccrual.create(companyId, EmployeeId.generate(), GBP)
        leaveAccrualRepository.save(accrual)
        val remeasureResult = remeasureUseCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )
        remeasureResult.shouldBeInstanceOf<RemeasureLeaveAccrualResult.Success>()

        val utilizeResult = utilizeUseCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("150.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        val success = utilizeResult.shouldBeInstanceOf<UtilizeLeaveAccrualResult.Success>()

        val reloadedAccrual = requireNotNull(leaveAccrualRepository.findById(accrual.id))
        reloadedAccrual.balance shouldBe Money(BigDecimal("350.00"), GBP)

        val reloadedEntry = requireNotNull(journalEntryRepository.findById(success.journalEntry.id))
        reloadedEntry.status shouldBe PostingStatus.POSTED
    }
}
