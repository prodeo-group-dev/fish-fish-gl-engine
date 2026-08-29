package com.theprodeogroup.fish.domain.tenancy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AccessLevelTest {

    @Test
    fun `given the same level, when checked atLeast itself, then it is true`() {
        AccessLevel.WRITE.atLeast(AccessLevel.WRITE) shouldBe true
    }

    @Test
    fun `given a higher level, when checked atLeast a lower one, then it is true`() {
        AccessLevel.ADMIN.atLeast(AccessLevel.READ) shouldBe true
    }

    @Test
    fun `given a lower level, when checked atLeast a higher one, then it is false`() {
        AccessLevel.READ.atLeast(AccessLevel.WRITE) shouldBe false
    }

    @Test
    fun `given NONE, when checked atLeast READ, then it is false`() {
        AccessLevel.NONE.atLeast(AccessLevel.READ) shouldBe false
    }

    @Test
    fun `given the full escalation order, when compared pairwise, then NONE lt READ lt WRITE lt APPROVE lt ADMIN`() {
        val order = listOf(AccessLevel.NONE, AccessLevel.READ, AccessLevel.WRITE, AccessLevel.APPROVE, AccessLevel.ADMIN)
        for (i in order.indices) {
            for (j in order.indices) {
                order[i].atLeast(order[j]) shouldBe (i >= j)
            }
        }
    }
}
