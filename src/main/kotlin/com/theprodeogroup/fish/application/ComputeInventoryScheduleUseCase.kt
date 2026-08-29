package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.inventory.InventorySchedule
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * Computes the [InventorySchedule] report for a Company's SOP dashboard
 * tab (2026-08-29) - same "Compute*UseCase, thin wrapper over a report
 * class" shape as [ComputeMoneyVelocityUseCase]/[ComputeSalesToExpenseRatioUseCase].
 */
class ComputeInventoryScheduleUseCase(
    private val companyRepository: CompanyRepository,
    private val stockItemRepository: StockItemRepository
) {
    sealed class Result {
        data class Success(val schedule: InventorySchedule) : Result()
        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId, asOf: LocalDate = LocalDate.now()): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val stockItems = stockItemRepository.findAllByCompany(companyId)
        return Result.Success(InventorySchedule.of(stockItems, asOf, company.baseCurrency))
    }
}
