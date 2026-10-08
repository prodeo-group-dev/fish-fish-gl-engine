package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/**
 * RBAC finding F9 / T19: a posting use case must load the Period its caller
 * names through `PeriodRepository.findOwnedBy(periodId, companyId)`, never a
 * raw `findById` - otherwise a caller authorized at Company A can name
 * Company B's Period. This scan fails the build when a future use case
 * forgets.
 */
class PeriodOwnershipGuardTest {

    /**
     * Use cases that legitimately load a Period without a caller-supplied Company:
     * - ClosePeriod: authorization already happens against the Period's own Company at the route.
     * - ReverseJournalEntry: the Period comes from the original entry, not the request.
     * - ComputeTax: compares `period.companyId` with the request's companyId itself (the pattern F9 copied).
     */
    private val allowlist = setOf("ClosePeriodUseCase.kt", "ReverseJournalEntryUseCase.kt", "ComputeTaxUseCase.kt")

    @Test
    fun `no application use case loads a caller-named Period without the owning Company check`() {
        val dir = File("src/main/kotlin/com/theprodeogroup/fish/application")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
            .filter { Regex("""periodRepository\.findById\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()

        offenders.shouldBeEmpty()
    }

    @Test
    fun `findOwnedBy returns the Period only for its own Company`() {
        val repo = FakePeriodRepository()
        val a = CompanyId.generate()
        val b = CompanyId.generate()
        val period = Period.create(a, PeriodType.MONTH, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 20)).also { repo.save(it) }

        repo.findOwnedBy(period.id, a)?.id shouldBe period.id
        repo.findOwnedBy(period.id, b).shouldBeNull()
    }
}
