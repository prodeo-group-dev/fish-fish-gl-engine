package com.theprodeogroup.fish.domain.tenancy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UserTest {

    @Test
    fun `given a valid email and name, when created, then fields are set`() {
        val user = User.create("jane@example.com", "Jane Doe")

        user.email shouldBe "jane@example.com"
        user.name shouldBe "Jane Doe"
    }

    @Test
    fun `given a blank email, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            User.create("   ", "Jane Doe")
        }
    }

    @Test
    fun `given an email with no @, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            User.create("not-an-email", "Jane Doe")
        }
    }
}
