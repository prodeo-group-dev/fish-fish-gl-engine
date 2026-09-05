package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.application.InviteStaffMemberUseCase
import com.theprodeogroup.fish.application.OnboardTenantUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.ModuleManagementPreference
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationResult
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.Currency

/**
 * `POST /tenants` (Section 9.2) and `POST /tenants/{tenantId}/companies`
 * (Section 9.1) - the onboarding flow's first HTTP exposure. Both use
 * cases existed at the application layer well before this file (see
 * their own KDoc); neither had a route until now.
 *
 * **Different auth for each, deliberately**: `/tenants` sits under
 * [fishOnboarding] ([FISH_JWT_ONBOARDING_AUTH_NAME]) - the caller has no
 * Membership yet, since creating their first one is the whole point.
 * `/tenants/{tenantId}/companies` sits under the normal [fishAuthenticated]
 * + [authorizeTenantForWrite] - by definition the Tenant, and the
 * caller's Membership in it, already exist.
 *
 * **No idempotency-key support here**, unlike every financial-posting
 * route in this package - [respondIdempotently] scopes its dedup key by
 * `TenantId`, which doesn't exist yet for `/tenants` (the very thing
 * being created). A retried onboarding POST can currently create a
 * second Tenant rather than being deduplicated - a real, known gap,
 * flagged rather than worked around with a fabricated placeholder
 * TenantId that would risk colliding across genuinely different
 * onboarding attempts.
 *
 * **Deliberately two separate functions, not one** - each needs to be
 * nested under a *different* `authenticate(...)` block ([fishOnboarding]
 * vs. [fishAuthenticated]) at the call site (see `Application.kt`), so a
 * single combined function taking both use cases would either apply the
 * wrong auth to one of them or need its own internal (and easy to get
 * wrong) auth branching.
 */
fun Route.tenantRoutesOnboarding(onboardTenantUseCase: OnboardTenantUseCase) {
    post("/tenants") {
        val identity = call.principal<VerifiedIdentity>()
        if (identity == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
            return@post
        }

        val request = call.receive<OnboardTenantRequestDto>()

        val tenantSegment = try {
            TenantSegment.valueOf(request.tenantSegment)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.tenantSegment}' is not a valid tenantSegment"))
            return@post
        }
        val clientType = try {
            ClientType.valueOf(request.clientType)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.clientType}' is not a valid clientType"))
            return@post
        }
        val tenantBaseCurrency = try {
            Currency.getInstance(request.tenantBaseCurrency)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.tenantBaseCurrency}' is not a valid ISO currency code"))
            return@post
        }
        val companyBaseCurrency = try {
            Currency.getInstance(request.companyBaseCurrency)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.companyBaseCurrency}' is not a valid ISO currency code"))
            return@post
        }
        val openingCashBalance = request.openingCashBalance?.let {
            it.toBigDecimalOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$it' is not a valid openingCashBalance"))
                return@post
            }
        }
        if (request.fiscalYearStartMonth !in 1..12) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "fiscalYearStartMonth must be between 1 (January) and 12 (December)"))
            return@post
        }
        val moduleManagementPreferences = mutableListOf<ModuleManagementPreference>()
        for (preferenceDto in request.moduleManagementPreferences) {
            val module = try {
                ManagedModule.valueOf(preferenceDto.module)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${preferenceDto.module}' is not a valid module"))
                return@post
            }
            if (preferenceDto.selfManaged) {
                moduleManagementPreferences.add(ModuleManagementPreference.selfManaged(module))
            } else {
                val delegateName = preferenceDto.delegateName
                val delegateEmail = preferenceDto.delegateEmail
                if (delegateName.isNullOrBlank() || delegateEmail.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponseDto("bad_request", "delegateName and delegateEmail are required when selfManaged is false (module '${preferenceDto.module}')")
                    )
                    return@post
                }
                moduleManagementPreferences.add(ModuleManagementPreference.delegatedTo(module, delegateName, delegateEmail))
            }
        }

        val result = onboardTenantUseCase.execute(
            OnboardTenantUseCase.Request(
                tenantName = request.tenantName,
                tenantSegment = tenantSegment,
                tenantBaseCurrency = tenantBaseCurrency,
                companyName = request.companyName,
                clientType = clientType,
                jurisdiction = request.jurisdiction,
                companyBaseCurrency = companyBaseCurrency,
                fiscalYearStartMonth = request.fiscalYearStartMonth,
                adminEmail = identity.email,
                adminName = request.adminName,
                openingCashBalance = openingCashBalance,
                moduleManagementPreferences = moduleManagementPreferences
            )
        )

        call.respond(
            HttpStatusCode.Created,
            OnboardTenantResponseDto(
                tenantId = result.tenant.id.value.toString(),
                companyId = result.company.id.value.toString(),
                adminUserId = result.adminUser.id.value.toString(),
                adminMembershipId = result.adminMembership.id.value.toString(),
                openingBalanceJournalEntryId = result.openingBalanceEntry?.id?.value?.toString()
            )
        )
    }
}

fun Route.tenantRoutesAuthenticated(
    addCompanyToTenantUseCase: AddCompanyToTenantUseCase,
    inviteStaffMemberUseCase: InviteStaffMemberUseCase,
    tenantRepository: TenantRepository,
    userRepository: UserRepository,
    membershipRepository: MembershipRepository
) {
    post("/tenants/{tenantId}/companies") {
        val tenantIdRaw = call.parameters["tenantId"]
        if (tenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "tenantId path parameter is required"))
            return@post
        }
        val tenantUuid = call.parseUuid(tenantIdRaw) ?: return@post
        val tenantId = TenantId(tenantUuid)

        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<AddCompanyToTenantRequestDto>()
        val clientType = try {
            ClientType.valueOf(request.clientType)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.clientType}' is not a valid clientType"))
            return@post
        }
        val companyBaseCurrency = try {
            Currency.getInstance(request.companyBaseCurrency)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.companyBaseCurrency}' is not a valid ISO currency code"))
            return@post
        }
        val openingCashBalance = request.openingCashBalance?.let {
            it.toBigDecimalOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$it' is not a valid openingCashBalance"))
                return@post
            }
        }
        if (request.fiscalYearStartMonth !in 1..12) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "fiscalYearStartMonth must be between 1 (January) and 12 (December)"))
            return@post
        }

        val result = addCompanyToTenantUseCase.execute(
            AddCompanyToTenantUseCase.Request(
                tenantId = tenantId,
                companyName = request.companyName,
                clientType = clientType,
                jurisdiction = request.jurisdiction,
                companyBaseCurrency = companyBaseCurrency,
                fiscalYearStartMonth = request.fiscalYearStartMonth,
                openingCashBalance = openingCashBalance
            )
        )
        if (result == null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponseDto("tenant_not_found_or_closed", "Tenant not found, or not open to new Companies"))
            return@post
        }

        call.respond(
            HttpStatusCode.Created,
            AddCompanyToTenantResponseDto(
                tenantId = result.tenant.id.value.toString(),
                companyId = result.company.id.value.toString(),
                openingBalanceJournalEntryId = result.openingBalanceEntry?.id?.value?.toString()
            )
        )
    }

    post("/tenants/{tenantId}/memberships") {
        val tenantIdRaw = call.parameters["tenantId"]
        if (tenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "tenantId path parameter is required"))
            return@post
        }
        val tenantUuid = call.parseUuid(tenantIdRaw) ?: return@post
        val tenantId = TenantId(tenantUuid)

        val caller = call.authorizeTenantForAdmin(tenantId) ?: return@post

        val request = call.receive<InviteStaffMemberRequestDto>()
        val role = try {
            Role.valueOf(request.role)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.role}' is not a valid role"))
            return@post
        }
        val modules = try {
            request.modules.map { ManagedModule.valueOf(it) }.toSet()
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.modules}' contains an invalid module"))
            return@post
        }

        val result = inviteStaffMemberUseCase.execute(
            InviteStaffMemberUseCase.Request(
                tenantId = tenantId,
                email = request.email,
                name = request.name,
                role = role,
                inviterName = caller.name,
                modules = modules
            )
        )

        when (result) {
            is InviteStaffMemberUseCase.Result.TenantNotFound -> {
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Tenant not found"))
            }
            is InviteStaffMemberUseCase.Result.Invited -> {
                call.respond(
                    HttpStatusCode.Created,
                    InviteStaffMemberResponseDto(
                        userId = result.user.id.value.toString(),
                        membershipId = result.membership.id.value.toString(),
                        role = result.membership.role.name,
                        alreadyMember = result.alreadyMember,
                        notificationSent = result.notification is StaffInviteNotificationResult.Success,
                        grantedModules = result.membership.grantedModules.map { it.name }
                    )
                )
            }
        }
    }

    get("/tenants/{tenantId}/memberships") {
        val tenantIdRaw = call.parameters["tenantId"]
        if (tenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "tenantId path parameter is required"))
            return@get
        }
        val tenantUuid = call.parseUuid(tenantIdRaw) ?: return@get
        val tenantId = TenantId(tenantUuid)

        call.authorizeTenantForRead(tenantId) ?: return@get

        val memberships = membershipRepository.findAllByTenant(tenantId)
        val members = memberships.map { membership ->
            val user = userRepository.findById(membership.userId)
            MembershipDto(
                membershipId = membership.id.value.toString(),
                userId = membership.userId.value.toString(),
                name = user?.name ?: "",
                email = user?.email ?: "",
                role = membership.role.name,
                status = membership.status.name,
                grantedModules = membership.grantedModules.map { it.name }
            )
        }
        call.respond(HttpStatusCode.OK, members)
    }
}
