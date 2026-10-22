package com.theprodeogroup.fish

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/**
 * The guard against a silent loss of tests (found 2026-10-18: 22 IM unit tests had never run; GL copied the guard 2026-10-22, GL-FR-NFR-6 / GL-T-21). JUnit 5 does NOT discover a `@Test` method
 * that returns a value, and Kotlin makes that easy to do by accident: `fun x() = runBlocking { ...; result shouldBe y }` or
 * `= testApplication { ... }` ending in a matcher gets the matcher's return type. Such a test compiles, is never run, and never fails.
 *
 * This test reads every compiled test class (unit and integration) and fails, naming each offender, if any `@Test` method has a return type
 * other than void. The fix is `runBlocking<Unit> { ... }`, a block body, or ending the test with a statement that returns Unit.
 * (Written with a block body itself, on purpose.)
 */
class TestMethodsReturnUnitGuardTest {

    @Test
    fun `every Test method returns Unit, so JUnit discovers and runs it`() {
        val here = File(TestMethodsReturnUnitGuardTest::class.java.protectionDomain.codeSource.location.toURI())
        val classesRoot = generateSequence(here) { it.parentFile }.firstOrNull { it.name == "kotlin" && it.parentFile?.name == "classes" }
        (classesRoot != null) shouldBe true
        val roots = listOf("test", "integrationTest").map { File(classesRoot!!, it) }.filter { it.isDirectory }
        (roots.isNotEmpty()) shouldBe true

        val loader = URLClassLoader(roots.map { it.toURI().toURL() }.toTypedArray(), TestMethodsReturnUnitGuardTest::class.java.classLoader)
        val offenders = mutableListOf<String>()
        var testsSeen = 0
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
                val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                val clazz = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
                clazz.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.forEach { method ->
                    testsSeen++
                    if (method.returnType != Void.TYPE) offenders += "${clazz.name}.${method.name} returns ${method.returnType.simpleName}"
                }
            }
        }

        (testsSeen > 1000) shouldBe true // the scan really looked at the suites
        offenders shouldBe emptyList()
    }
}
