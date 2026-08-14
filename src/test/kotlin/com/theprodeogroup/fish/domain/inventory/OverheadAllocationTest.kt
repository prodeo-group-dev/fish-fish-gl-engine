package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.ledger.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

class OverheadAllocationTest {

    @Test
    fun `given actual production below normal capacity, when allocated, then fixed overhead is only partly allocated and the rest is unallocated`() {
        val allocation = OverheadAllocation.calculate(
            normalCapacity = BigDecimal("1000"),
            actualProduction = BigDecimal("800"),
            fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
            variableOverheadPool = Money(BigDecimal("4000.00"), GBP)
        )

        allocation.allocatedFixedOverhead shouldBe Money(BigDecimal("8000.00"), GBP)
        allocation.unallocatedFixedOverhead shouldBe Money(BigDecimal("2000.00"), GBP)
        allocation.allocatedVariableOverhead shouldBe Money(BigDecimal("4000.00"), GBP)
        allocation.totalAllocatedOverhead shouldBe Money(BigDecimal("12000.00"), GBP)
    }

    @Test
    fun `given actual production exactly at normal capacity, when allocated, then all fixed overhead is allocated and none is unallocated`() {
        val allocation = OverheadAllocation.calculate(
            normalCapacity = BigDecimal("1000"),
            actualProduction = BigDecimal("1000"),
            fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
            variableOverheadPool = Money(BigDecimal("4000.00"), GBP)
        )

        allocation.allocatedFixedOverhead shouldBe Money(BigDecimal("10000.00"), GBP)
        allocation.unallocatedFixedOverhead shouldBe Money(BigDecimal.ZERO, GBP)
        allocation.totalAllocatedOverhead shouldBe Money(BigDecimal("14000.00"), GBP)
    }

    @Test
    fun `given actual production above normal capacity, when allocated, then fixed overhead allocation is capped at the full pool with nothing unallocated`() {
        val allocation = OverheadAllocation.calculate(
            normalCapacity = BigDecimal("1000"),
            actualProduction = BigDecimal("1200"),
            fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
            variableOverheadPool = Money(BigDecimal("4800.00"), GBP)
        )

        allocation.allocatedFixedOverhead shouldBe Money(BigDecimal("10000.00"), GBP)
        allocation.unallocatedFixedOverhead shouldBe Money(BigDecimal.ZERO, GBP)
        allocation.allocatedVariableOverhead shouldBe Money(BigDecimal("4800.00"), GBP)
        allocation.totalAllocatedOverhead shouldBe Money(BigDecimal("14800.00"), GBP)
    }

    @Test
    fun `given zero actual production (idle plant), when allocated, then all fixed overhead is unallocated and none is capitalised`() {
        val allocation = OverheadAllocation.calculate(
            normalCapacity = BigDecimal("1000"),
            actualProduction = BigDecimal.ZERO,
            fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
            variableOverheadPool = Money(BigDecimal.ZERO, GBP)
        )

        allocation.allocatedFixedOverhead shouldBe Money(BigDecimal.ZERO, GBP)
        allocation.unallocatedFixedOverhead shouldBe Money(BigDecimal("10000.00"), GBP)
        allocation.totalAllocatedOverhead shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a non-positive normal capacity, when calculated, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            OverheadAllocation.calculate(
                normalCapacity = BigDecimal.ZERO,
                actualProduction = BigDecimal("500"),
                fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
                variableOverheadPool = Money(BigDecimal("4000.00"), GBP)
            )
        }
    }

    @Test
    fun `given a negative actual production, when calculated, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            OverheadAllocation.calculate(
                normalCapacity = BigDecimal("1000"),
                actualProduction = BigDecimal("-1"),
                fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
                variableOverheadPool = Money(BigDecimal("4000.00"), GBP)
            )
        }
    }

    @Test
    fun `given fixed and variable overhead pools in different currencies, when calculated, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            OverheadAllocation.calculate(
                normalCapacity = BigDecimal("1000"),
                actualProduction = BigDecimal("800"),
                fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
                variableOverheadPool = Money(BigDecimal("4000.00"), Currency.getInstance("USD"))
            )
        }
    }

    @Test
    fun `given a calculated allocation, when checked, then its currency matches the overhead pools`() {
        val allocation = OverheadAllocation.calculate(
            normalCapacity = BigDecimal("1000"),
            actualProduction = BigDecimal("800"),
            fixedOverheadPool = Money(BigDecimal("10000.00"), GBP),
            variableOverheadPool = Money(BigDecimal("4000.00"), GBP)
        )

        allocation.currency shouldBe GBP
    }
}
