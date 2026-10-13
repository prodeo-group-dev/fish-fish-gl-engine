package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * `GET /companies/{companyId}/open-period`: the Period a caller may post into, as a read of its own (2026-10-13, the
 * Fixed Assets test: WEB had no source for the `periodId` an asset create needs and was borrowing the inventory
 * posting context). It is the same Period every posting context returns: the Company's first Period that allows
 * posting. It exposes an id and dates the Company already holds; it grants nothing and changes who may do what by
 * nothing.
 */
class ComputeOpenPeriodUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository
) {
    sealed class Result {
        data class Success(val period: Period) : Result()
        data object CompanyNotFound : Result()
        data object NoOpenPeriod : Result()
    }

    fun execute(companyId: CompanyId): Result {
        companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val period = periodRepository.findAllByCompany(companyId).firstOrNull { it.allowsPosting() }
            ?: return Result.NoOpenPeriod
        return Result.Success(period)
    }
}
