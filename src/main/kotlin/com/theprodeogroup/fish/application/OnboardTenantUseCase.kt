package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import java.util.Currency

/**
 * Application service for docs/DDD_Design.md Section 9.2 - the first use
 * case in the repo's new `application` package (Section 5). Orchestrates
 * four separate aggregates (`Tenant`, `Company`, `User`, `Membership`)
 * through their existing repository interfaces; deliberately depends on
 * nothing from `infrastructure` - only `domain` types - keeping this
 * layer framework-agnostic, per Section 5's split.
 *
 * Covers steps 1-5, 7, and 8 of Section 9.2 in one call: capture identity
 * (1), create the Tenant (2), create the first Company (3), create the
 * first admin User + Membership (4), leave KYB/KYC at their default
 * Pending (5 - FiSH doesn't perform screening itself, so there's nothing
 * to record yet at onboarding time), activate (7), and return the
 * `TenantActivated`/`TenantOnboarded` events `Tenant.activate()` already
 * raises (8). Step 6 (issuing an API key) is confirmed optional/deferrable
 * and not built here. `AddCompanyToTenantUseCase` (Section 9.1, the
 * lighter "add another jurisdiction to an existing Tenant" flow) is a
 * separate, not-yet-built use case - out of scope for this one.
 *
 * **No cross-repository atomicity** - each repository's `save()` commits
 * independently (a deliberate, discussed tradeoff, not an oversight). A
 * failure partway through this flow leaves whatever was already saved in
 * place; worst case is a Draft-status `Tenant` with no `Company`/`Membership`
 * linked yet, or a `Company`/`User`/`Membership` saved but not yet
 * reachable from the `Tenant`'s own join tables - both are inspectable,
 * valid states already representable in the domain model (a Tenant
 * legitimately sits in Draft mid-onboarding), not corrupt data. If a
 * later use case needs real atomicity across repositories, that's a
 * `UnitOfWork`-shaped abstraction to add then, not preemptively here.
 *
 * **Saves `Tenant` twice, not once - a real ordering constraint, not
 * redundancy.** `companies.tenant_id`/`memberships.tenant_id` are real
 * foreign keys to `tenants(id)` (Section 10.3), so the Tenant row must
 * exist before `Company`/`Membership` can be saved at all. But
 * `Tenant`'s own join tables (`tenant_companies`/`tenant_admin_memberships`,
 * also Section 10.3) carry real foreign keys the other direction, to
 * `companies(id)`/`memberships(id)` - so those rows can't be written
 * until `Company`/`Membership` already exist. Two saves resolve the
 * cycle: first a bare Tenant (Draft, empty ID sets) to satisfy the first
 * direction, then `Company`/`User`/`Membership`, then a second full
 * Tenant save (now Active, both ID sets populated) to satisfy the second.
 */
class OnboardTenantUseCase(
    private val tenantRepository: TenantRepository,
    private val companyRepository: CompanyRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository
) {
    data class Request(
        val tenantName: String,
        val tenantSegment: TenantSegment,
        val tenantBaseCurrency: Currency,
        val companyName: String,
        val clientType: ClientType,
        val jurisdiction: String,
        val companyBaseCurrency: Currency,
        val adminEmail: String,
        val adminName: String
    )

    data class Result(
        val tenant: Tenant,
        val company: Company,
        val adminUser: User,
        val adminMembership: Membership,
        val events: List<DomainEvent>
    )

    fun execute(request: Request): Result {
        val tenant = Tenant.onboard(request.tenantName, request.tenantSegment, request.tenantBaseCurrency)
        tenantRepository.save(tenant)

        val company = Company.create(
            tenant.id, request.companyName, request.clientType, request.jurisdiction, request.companyBaseCurrency
        )
        companyRepository.save(company)

        val adminUser = User.create(request.adminEmail, request.adminName)
        userRepository.save(adminUser)

        val adminMembership = Membership.grant(adminUser.id, tenant.id, Role.OWNER_ADMIN)
        membershipRepository.save(adminMembership)

        tenant.addCompany(company.id)
        tenant.addAdminMembership(adminMembership.id)
        val activation = tenant.activate()
        check(activation.isValid) {
            "OnboardTenantUseCase built a Tenant that failed its own activation preconditions: " +
                activation.errors.joinToString()
        }

        tenantRepository.save(tenant)

        return Result(tenant, company, adminUser, adminMembership, tenant.pullDomainEvents())
    }
}
