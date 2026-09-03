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
 * `NoFixedAssetsForCompany` mirrors `ComputeBalanceSheetUseCase.Result.NoAccountsForCompany` -
 * an empty register is a distinct, callable-out state, not folded into
 * an empty-list `Success`.
 */
class ComputeFixedAssetRegisterUseCase(
    private val companyRepository: CompanyRepository,
    private val fixedAssetRepository: FixedAssetRepository
) {
    sealed class Result {
        data class Success(val register: FixedAssetRegister) : Result()
        data object CompanyNotFound : Result()
        data object NoFixedAssetsForCompany : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val assets = fixedAssetRepository.findAllByCompany(companyId)
        if (assets.isEmpty()) return Result.NoFixedAssetsForCompany

        return Result.Success(FixedAssetRegister.of(assets, company.baseCurrency))
    }
}
