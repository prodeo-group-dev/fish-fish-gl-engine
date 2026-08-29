package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecord
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecordRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * Lists a Company's recorded sales, newest first (2026-08-29, user
 * request: "a listing of sales (each timestamped) on the SOP screen") -
 * a thin read over [SalesInvoiceRecordRepository], same "Compute/List*UseCase"
 * shape as [ComputeInventoryScheduleUseCase].
 */
class ListSalesInvoicesUseCase(
    private val companyRepository: CompanyRepository,
    private val salesInvoiceRecordRepository: SalesInvoiceRecordRepository
) {
    sealed class Result {
        data class Success(val records: List<SalesInvoiceRecord>) : Result()
        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId): Result {
        companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        return Result.Success(salesInvoiceRecordRepository.findAllByCompany(companyId))
    }
}
