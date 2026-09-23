package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeVatReturnResult
import com.theprodeogroup.fish.application.ComputeVatReturnUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.tax.VatFilingPeriod
import com.theprodeogroup.fish.domain.tax.VatReturn
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `POST /companies/{companyId}/vat-return` - the VAT MVP's actual
 * deliverable (docs/IE/IE_VAT_MVP_Design.md #5). Gated by
 * [ManagedModule.TAX], same as `taxRoutes`'s CIT endpoint - VAT and CIT
 * are both "Tax Management," one module, not split further.
 *
 * `POST`, not `GET`, for the same reason `taxRoutes`'s CIT endpoint is
 * `POST` - [ComputeVatReturnUseCase] persists a new `VatReturn` row on
 * every call (an audit record), a resource-creating action, not an
 * idempotent read.
 */
fun Route.vatReturnRoutes(
    computeVatReturnUseCase: ComputeVatReturnUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/vat-return") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)

        val company = companyRepository.findById(companyId)
        if (company == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            return@post
        }
        if (!call.verifyClaimedTenant(company.tenantId)) return@post
        call.authorizeTenantForModule(company.tenantId, company.id, ManagedModule.TAX, AccessLevel.WRITE) ?: return@post

        val request = call.receive<ComputeVatReturnRequestDto>()
        val vatControlAccountUuid = call.parseUuid(request.vatControlAccountId) ?: return@post
        val startDate = call.parseVatReturnDate(request.filingPeriodStartDate) ?: return@post
        val endDate = call.parseVatReturnDate(request.filingPeriodEndDate) ?: return@post
        val filingPeriod = try {
            VatFilingPeriod(startDate, endDate)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", e.message ?: "invalid filing period"))
            return@post
        }

        val result = computeVatReturnUseCase.execute(
            ComputeVatReturnUseCase.Request(companyId, filingPeriod, AccountId(vatControlAccountUuid), company.baseCurrency)
        )

        when (result) {
            is ComputeVatReturnResult.Success -> call.respond(HttpStatusCode.Created, result.vatReturn.toDto())
            is ComputeVatReturnResult.VatControlAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("vat_control_account_not_found", result.accountId.value.toString()))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.parseVatReturnDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }

private fun VatReturn.toDto() = VatReturnResponseDto(
    id = id.value.toString(),
    companyId = companyId.value.toString(),
    filingPeriodStartDate = filingPeriod.startDate.toString(),
    filingPeriodEndDate = filingPeriod.endDate.toString(),
    vatControlAccountId = vatControlAccountId.value.toString(),
    outputVat = outputVat.amount.toPlainString(),
    inputVat = inputVat.amount.toPlainString(),
    netVatDue = netVatDue.amount.toPlainString(),
    direction = direction.name,
    currency = outputVat.currency.currencyCode,
    computedAt = computedAt.toString()
)
