package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.PhoneNumber
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.VerificationStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

class RecordAdminPhoneNumberUseCaseTest {

    private val tenantRepository = FakeTenantRepository()
    private val phoneVerificationChecker = FakeAdminPhoneVerificationChecker()
    private val useCase = RecordAdminPhoneNumberUseCase(tenantRepository, phoneVerificationChecker)

    private fun activatedTenant(): Tenant {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        tenant.addCompany(CompanyId.generate())
        tenant.addAdminMembership(MembershipId.generate())
        tenant.activate()
        tenantRepository.save(tenant)
        return tenant
    }

    @Test
    fun `given a phone number Cognito confirms is verified, when recorded, then the Tenant's admin phone is Verified`() {
        val tenant = activatedTenant()

        val result = useCase.execute(tenant.id, "founder@example.com", PhoneNumber("+15550123456"))

        result.shouldBeInstanceOf<RecordAdminPhoneNumberUseCase.Result.Success>()
        val success = result as RecordAdminPhoneNumberUseCase.Result.Success
        success.tenant.adminPhoneNumber shouldBe PhoneNumber("+15550123456")
        success.tenant.adminPhoneVerificationStatus shouldBe VerificationStatus.VERIFIED
        tenantRepository.findById(tenant.id)?.adminPhoneVerificationStatus shouldBe VerificationStatus.VERIFIED
    }

    @Test
    fun `given a nonexistent Tenant, when recorded, then it returns TenantNotFound`() {
        val result = useCase.execute(TenantId.generate(), "founder@example.com", PhoneNumber("+15550123456"))

        result shouldBe RecordAdminPhoneNumberUseCase.Result.TenantNotFound
    }

    @Test
    fun `given Cognito has not actually verified this phone number, when recorded, then it returns NotVerifiedByCognito and the Tenant is untouched`() {
        val tenant = activatedTenant()
        phoneVerificationChecker.alwaysReject()

        val result = useCase.execute(tenant.id, "founder@example.com", PhoneNumber("+15550123456"))

        result shouldBe RecordAdminPhoneNumberUseCase.Result.NotVerifiedByCognito
        tenantRepository.findById(tenant.id)?.adminPhoneVerificationStatus shouldBe VerificationStatus.PENDING
        tenantRepository.findById(tenant.id)?.adminPhoneNumber shouldBe null
    }
}
