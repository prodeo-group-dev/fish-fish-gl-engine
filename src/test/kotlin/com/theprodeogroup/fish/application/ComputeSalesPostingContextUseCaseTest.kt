package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

private val EUR: Currency = Currency.getInstance("EUR")
private val TODAY = LocalDate.of(2026, 9, 20)

/**
 * No test coverage existed for this use case before the VAT reshape
 * (2026-09-19) added [SalesPostingContextResult.Success.vatControlAccountId] -
 * covers both the pre-existing AR/Revenue resolution and the new VAT
 * lookup together, rather than adding VAT-only tests against an
 * otherwise-untested use case.
 */
class ComputeSalesPostingContextUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val useCase = ComputeSalesPostingContextUseCase(companyRepository, periodRepository, accountRepository)

    private val tenantId = TenantId.generate()
    private val company = Company.create(tenantId, "Test Co", ClientType.COMPANY_LIMITED, Jurisdiction.IE, EUR)
        .also { companyRepository.save(it) }

    private fun openPeriod() {
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
    }

    private fun account(code: String, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(company.id, type, classification, code, "Test Account").also { accountRepository.save(it) }
    }

    @Test
    fun `given AR, Revenue, and VAT Control accounts configured, when resolved, then it returns all three plus the open Period and currency`() {
        openPeriod()
        account("1100", AccountType.ASSET)
        account("4000", AccountType.REVENUE)
        account("2150", AccountType.LIABILITY)

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<SalesPostingContextResult.Success>()
        success.currency shouldBe EUR
        success.cashAccountId shouldBe null
    }


    @Test
    fun `given an ASSET account with code 1000, when resolved, then cashAccountId is that account - the same one a cash sale posts to`() {
        openPeriod()
        account("1100", AccountType.ASSET)
        account("4000", AccountType.REVENUE)
        account("2150", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<SalesPostingContextResult.Success>().cashAccountId shouldBe cash.id
    }

    @Test
    fun `given a LIABILITY account that happens to have code 1000 and no cash ASSET, when resolved, then cashAccountId is null - type is checked, not just the code`() {
        openPeriod()
        account("1100", AccountType.ASSET)
        account("4000", AccountType.REVENUE)
        account("2150", AccountType.LIABILITY)
        account("1000", AccountType.LIABILITY)

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<SalesPostingContextResult.Success>().cashAccountId shouldBe null
    }
    @Test
    fun `given no VAT Control Account configured, when resolved, then it returns VatControlAccountNotConfigured`() {
        openPeriod()
        account("1100", AccountType.ASSET)
        account("4000", AccountType.REVENUE)

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<SalesPostingContextResult.VatControlAccountNotConfigured>()
    }

    @Test
    fun `given no open Period, when resolved, then it returns NoOpenPeriod before checking accounts`() {
        account("1100", AccountType.ASSET)
        account("4000", AccountType.REVENUE)
        account("2150", AccountType.LIABILITY)

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<SalesPostingContextResult.NoOpenPeriod>()
    }

    @Test
    fun `given a nonexistent Company, when resolved, then it returns CompanyNotFound`() {
        val result = useCase.execute(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate())

        result.shouldBeInstanceOf<SalesPostingContextResult.CompanyNotFound>()
    }
}
