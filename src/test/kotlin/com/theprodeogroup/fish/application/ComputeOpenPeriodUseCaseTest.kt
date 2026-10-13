package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

class ComputeOpenPeriodUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val periods = FakePeriodRepository()
    private val useCase = ComputeOpenPeriodUseCase(companies, periods)

    private fun company() = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }

    private fun period(company: Company, start: String, end: String, open: Boolean = true) =
        Period.create(company.id, PeriodType.MONTH, LocalDate.parse(start), LocalDate.parse(end)).also {
            if (open) it.open()
            periods.save(it)
        }

    @Test
    fun `given an open Period, then it is returned with its dates and status`() {
        val company = company()
        val period = period(company, "2026-10-01", "2026-10-31")

        val found = useCase.execute(company.id).shouldBeInstanceOf<ComputeOpenPeriodUseCase.Result.Success>().period

        found.id shouldBe period.id
        found.startDate shouldBe LocalDate.parse("2026-10-01")
        found.endDate shouldBe LocalDate.parse("2026-10-31")
        found.status.name shouldBe "OPEN"
    }

    @Test
    fun `given a Period that is not open, then there is no open Period`() {
        val company = company()
        period(company, "2026-10-01", "2026-10-31", open = false)

        useCase.execute(company.id) shouldBe ComputeOpenPeriodUseCase.Result.NoOpenPeriod
    }

    @Test
    fun `given a closed Period and an open one, then the open one is returned`() {
        val company = company()
        period(company, "2026-09-01", "2026-09-30").also { it.close() ; periods.save(it) }
        val open = period(company, "2026-10-01", "2026-10-31")

        useCase.execute(company.id).shouldBeInstanceOf<ComputeOpenPeriodUseCase.Result.Success>().period.id shouldBe open.id
    }

    @Test
    fun `given another Company's open Period, then it is never returned for this Company`() {
        val mine = company()
        val other = company()
        period(other, "2026-10-01", "2026-10-31")

        useCase.execute(mine.id) shouldBe ComputeOpenPeriodUseCase.Result.NoOpenPeriod
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(CompanyId.generate()) shouldBe ComputeOpenPeriodUseCase.Result.CompanyNotFound
    }
}
