package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.time.LocalDate

private val JANUARY = LocalDate.of(2026, 1, 1)
private val JANUARY_END = LocalDate.of(2026, 1, 31)

class PeriodTest {

    @Test
    fun `given valid Company, type, and date range, when a Period is created, then it starts in Draft`() {
        val period = Period.create(
            companyId = CompanyId.generate(),
            periodType = PeriodType.MONTH,
            startDate = JANUARY,
            endDate = JANUARY_END
        )

        period.status shouldBe PeriodStatus.DRAFT
        period.allowsPosting() shouldBe false
    }

    @Test
    fun `given an end date before the start date, when a Period is created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            Period.create(
                companyId = CompanyId.generate(),
                periodType = PeriodType.MONTH,
                startDate = JANUARY_END,
                endDate = JANUARY
            )
        }
    }

    @Test
    fun `given a new Period in Draft, when opened, then status becomes Open and allowsPosting is true`() {
        val period = readyPeriod()

        val result = period.open()

        result.isValid shouldBe true
        period.status shouldBe PeriodStatus.OPEN
        period.allowsPosting() shouldBe true
    }

    @Test
    fun `given an Open Period, when closed, then status becomes Closed and allowsPosting is false`() {
        val period = readyPeriod()
        period.open()

        val result = period.close()

        result.isValid shouldBe true
        period.status shouldBe PeriodStatus.CLOSED
        period.allowsPosting() shouldBe false
    }

    @Test
    fun `given a Closed Period, when reopened, then status returns to Open`() {
        val period = readyPeriod()
        period.open()
        period.close()

        val result = period.reopen()

        result.isValid shouldBe true
        period.status shouldBe PeriodStatus.OPEN
    }

    @Test
    fun `given a Closed Period, when locked, then status becomes Locked`() {
        val period = readyPeriod()
        period.open()
        period.close()

        val result = period.lock()

        result.isValid shouldBe true
        period.status shouldBe PeriodStatus.LOCKED
    }

    @Test
    fun `given a Locked Period, when any transition is attempted, then it fails - Locked is terminal`() {
        val period = readyPeriod()
        period.open()
        period.close()
        period.lock()

        period.open().isValid shouldBe false
        period.close().isValid shouldBe false
        period.reopen().isValid shouldBe false
        period.lock().isValid shouldBe false
        period.status shouldBe PeriodStatus.LOCKED
    }

    @Test
    fun `given a Draft Period, when closed directly, then it fails - must be opened first`() {
        val period = readyPeriod()

        val result = period.close()

        result.isValid shouldBe false
        period.status shouldBe PeriodStatus.DRAFT
    }

    @Test
    fun `given an Open Period, when closed, then a PeriodClosed event is raised`() {
        val period = readyPeriod()
        period.open()

        period.close()

        val events = period.pullDomainEvents()
        events.map { it::class } shouldBe listOf(PeriodClosed::class)
    }

    @Test
    fun `given a Draft Period, when close is rejected, then no event is raised`() {
        val period = readyPeriod()

        period.close()

        period.pullDomainEvents() shouldBe emptyList()
    }

    @Test
    fun `given a Closed Period, when locked, then a PeriodLocked event is raised`() {
        val period = readyPeriod()
        period.open()
        period.close()
        period.pullDomainEvents() // drain the PeriodClosed event from above - this test is only about lock()

        period.lock()

        val events = period.pullDomainEvents()
        events.map { it::class } shouldBe listOf(PeriodLocked::class)
    }

    @Test
    fun `given events were already pulled, when pulled again, then it returns empty`() {
        val period = readyPeriod()
        period.open()
        period.close()
        period.pullDomainEvents()

        period.pullDomainEvents() shouldBe emptyList()
    }

    private fun readyPeriod(): Period = Period.create(
        companyId = CompanyId.generate(),
        periodType = PeriodType.MONTH,
        startDate = JANUARY,
        endDate = JANUARY_END
    )
}
