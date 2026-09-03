package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * Adds one entry to a Company's Fixed Asset Register - the subsidiary-
 * ledger counterpart to [CreateAccountUseCase], which deliberately left
 * "linking a Fixed-classified Asset account to an individual FixedAsset
 * register entry" out of scope (2026-09-03). This use case only creates
 * the register entry itself; it posts no `JournalEntry` - acquiring the
 * asset (debiting the Fixed Assets control account, code "1200") is a
 * separate posting via [RecordVendorObligationUseCase] or
 * [PostJournalEntryUseCase], the same "control account + subsidiary
 * ledger, tracked independently" shape [Creditor]/[Customer] already
 * established for AP/AR.
 */
class CreateFixedAssetUseCase(
    private val companyRepository: CompanyRepository,
    private val fixedAssetRepository: FixedAssetRepository
) {
    data class Request(
        val companyId: CompanyId,
        val name: String,
        val category: AssetCategory,
        val cost: Money,
        val acquisitionDate: LocalDate,
        val usefulLifeYears: Int? = null
    )

    sealed class Result {
        data class Success(val fixedAsset: FixedAsset) : Result()
        data object CompanyNotFound : Result()
        data class InvalidFixedAsset(val message: String?) : Result()
    }

    fun execute(request: Request): Result {
        companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound

        val fixedAsset = try {
            FixedAsset.create(
                companyId = request.companyId,
                name = request.name,
                category = request.category,
                cost = request.cost,
                acquisitionDate = request.acquisitionDate,
                usefulLifeYears = request.usefulLifeYears
            )
        } catch (e: IllegalArgumentException) {
            return Result.InvalidFixedAsset(e.message)
        }

        fixedAssetRepository.save(fixedAsset)
        return Result.Success(fixedAsset)
    }
}
