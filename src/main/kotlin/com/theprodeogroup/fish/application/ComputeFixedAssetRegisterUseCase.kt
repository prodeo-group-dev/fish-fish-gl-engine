package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRegister
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * The GL page's Fixed Asset Register report - every [com.theprodeogroup.fish.domain.fixedassets.FixedAsset]
 * a Company holds, at cost/accumulated depreciation/accumulated
 * impairment/net book value/carrying amount. Same thin-read,
 * "Compute*UseCase, sealed Result" shape as [ComputeBalanceSheetUseCase],
 * built alongside [CreateFixedAssetUseCase]/[RecordFixedAssetDepreciationUseCase]/
 * [AssessFixedAssetImpairmentUseCase]/[DisposeFixedAssetUseCase] as the
 * first end-to-end wiring of `FixedAsset` (domain-only since 2026-08-12).
 * A Company with no assets is an **empty register**, not an error
 * (2026-10-13, found by the Fixed Assets test: it used to answer 409
 * `no_fixed_assets`, which treated a normal state as a failure and broke
 * the screen): `Success` with no lines and zero totals, in the Company's
 * own currency.
 */
class ComputeFixedAssetRegisterUseCase(
    private val companyRepository: CompanyRepository,
    private val fixedAssetRepository: FixedAssetRepository
) {
    sealed class Result {
        data class Success(val register: FixedAssetRegister) : Result()
        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val assets = fixedAssetRepository.findAllByCompany(companyId)

        return Result.Success(FixedAssetRegister.of(assets, company.baseCurrency))
    }
}
