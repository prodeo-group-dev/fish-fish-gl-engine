package com.theprodeogroup.fish.domain.lending

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.GoingConcernStatus
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

private val TODAY = LocalDate.of(2026, 1, 15)

class ArrearsCaseTest {

    @Test
    fun `given a new ArrearsCase, when opened, then it starts at Early Contact and unresolved`() {
        val case = openCase()

        case.stage shouldBe ArrearsStage.EARLY_CONTACT
        case.isResolved shouldBe false
        case.outcome shouldBe null
    }

    @Test
    fun `given a case at Early Contact, when escalated, then it moves to Pastoral Engagement and raises an event`() {
        val case = openCase()

        val result = case.escalate()

        result.isValid shouldBe true
        case.stage shouldBe ArrearsStage.PASTORAL_ENGAGEMENT
        val events = case.pullDomainEvents()
        events shouldHaveSize 1
        (events.first() as ArrearsStageChanged).newStage shouldBe ArrearsStage.PASTORAL_ENGAGEMENT
    }

    @Test
    fun `given a case escalated through every stage, when escalated again from Final Resolution, then it fails`() {
        val case = openCase()
        case.escalate()
        case.escalate()
        case.escalate()

        val result = case.escalate()

        result.isValid shouldBe false
        case.stage shouldBe ArrearsStage.FINAL_RESOLUTION
    }

    @Test
    fun `given an individual borrower's case, when escalated to Final Resolution without a Company, then it succeeds`() {
        val case = openCase(companyId = null)
        case.escalate()
        case.escalate()

        val result = case.escalate()

        result.isValid shouldBe true
        case.stage shouldBe ArrearsStage.FINAL_RESOLUTION
    }

    @Test
    fun `given a business borrower's case, when escalated to Final Resolution without providing the Company, then it fails`() {
        val companyId = businessCompany(TenantId.generate()).id
        val case = openCase(companyId = companyId)
        case.escalate()
        case.escalate()

        val result = case.escalate()

        result.isValid shouldBe false
        case.stage shouldBe ArrearsStage.FORMAL_REVIEW
    }

    @Test
    fun `given a business borrower's case, when escalated to Final Resolution with the matching Company, then the Company's going concern is flagged`() {
        val company = businessCompany(TenantId.generate())
        val case = openCase(companyId = company.id)
        case.escalate()
        case.escalate()

        val result = case.escalate(company)

        result.isValid shouldBe true
        case.stage shouldBe ArrearsStage.FINAL_RESOLUTION
        company.goingConcernStatus shouldBe GoingConcernStatus.SUBSTANTIAL_DOUBT
    }

    @Test
    fun `given a business borrower's case, when escalated to Final Resolution with a mismatched Company, then it fails`() {
        val company = businessCompany(TenantId.generate())
        val wrongCompany = businessCompany(TenantId.generate())
        val case = openCase(companyId = company.id)
        case.escalate()
        case.escalate()

        val result = case.escalate(wrongCompany)

        result.isValid shouldBe false
        case.stage shouldBe ArrearsStage.FORMAL_REVIEW
    }

    @Test
    fun `given a Company already in Substantial Doubt from another case, when a second case escalates to Final Resolution for it, then it still succeeds`() {
        val company = businessCompany(TenantId.generate())
        company.flagSubstantialDoubt()
        val case = openCase(companyId = company.id)
        case.escalate()
        case.escalate()

        val result = case.escalate(company)

        result.isValid shouldBe true
        case.stage shouldBe ArrearsStage.FINAL_RESOLUTION
    }

    @Test
    fun `given a business borrower's case not yet at Final Resolution, when escalated without a Company, then it succeeds`() {
        val company = businessCompany(TenantId.generate())
        val case = openCase(companyId = company.id)

        val result = case.escalate()

        result.isValid shouldBe true
        case.stage shouldBe ArrearsStage.PASTORAL_ENGAGEMENT
        company.goingConcernStatus shouldBe GoingConcernStatus.ASSUMED
    }

    @Test
    fun `given a resolved case, when escalated, then it fails`() {
        val case = openCase()
        case.resolve(ArrearsOutcome.RESTRUCTURED)

        val result = case.escalate()

        result.isValid shouldBe false
    }

    @Test
    fun `given an unresolved case, when resolved, then the outcome is recorded and an event is raised`() {
        val case = openCase()

        val result = case.resolve(ArrearsOutcome.PAYMENT_HOLIDAY_GRANTED)

        result.isValid shouldBe true
        case.isResolved shouldBe true
        case.outcome shouldBe ArrearsOutcome.PAYMENT_HOLIDAY_GRANTED
        val event = case.pullDomainEvents().first() as ArrearsCaseResolved
        event.outcome shouldBe ArrearsOutcome.PAYMENT_HOLIDAY_GRANTED
    }

    @Test
    fun `given a case still at Early Contact, when resolved directly, then it succeeds without escalating first`() {
        val case = openCase()

        val result = case.resolve(ArrearsOutcome.RESTRUCTURED)

        result.isValid shouldBe true
        case.stage shouldBe ArrearsStage.EARLY_CONTACT
    }

    @Test
    fun `given an already-resolved case, when resolved again, then it fails`() {
        val case = openCase()
        case.resolve(ArrearsOutcome.RESTRUCTURED)

        val result = case.resolve(ArrearsOutcome.FORMAL_COLLECTION)

        result.isValid shouldBe false
        case.outcome shouldBe ArrearsOutcome.RESTRUCTURED
    }

    private fun openCase(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId? = null): ArrearsCase =
        ArrearsCase.open(BorrowerId.generate(), AccountId.generate(), TODAY, companyId)

    private fun businessCompany(tenantId: TenantId): Company =
        Company.create(tenantId, "Acme Farms Ltd", ClientType.COMPANY_LIMITED, "SL", Currency.getInstance("SLE"))
}
