package com.theprodeogroup.fish.domain.tenancy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModuleManagementPreferenceTest {

    @Test
    fun `given a self-managed module, when created, then delegateName and delegateEmail are both null`() {
        val preference = ModuleManagementPreference.selfManaged(ManagedModule.GL)

        preference.module shouldBe ManagedModule.GL
        preference.selfManaged shouldBe true
        preference.delegateName shouldBe null
        preference.delegateEmail shouldBe null
    }

    @Test
    fun `given a delegated module with a name and email, when created, then it stores both`() {
        val preference = ModuleManagementPreference.delegatedTo(ManagedModule.HR, "Jane Doe", "jane@example.com")

        preference.module shouldBe ManagedModule.HR
        preference.selfManaged shouldBe false
        preference.delegateName shouldBe "Jane Doe"
        preference.delegateEmail shouldBe "jane@example.com"
    }

    @Test
    fun `given a blank delegateName, when delegating a module, then it throws`() {
        shouldThrow<IllegalArgumentException> {
            ModuleManagementPreference.delegatedTo(ManagedModule.SOP, "  ", "jane@example.com")
        }
    }

    @Test
    fun `given a blank delegateEmail, when delegating a module, then it throws`() {
        shouldThrow<IllegalArgumentException> {
            ModuleManagementPreference.delegatedTo(ManagedModule.POP, "Jane Doe", "")
        }
    }
}
