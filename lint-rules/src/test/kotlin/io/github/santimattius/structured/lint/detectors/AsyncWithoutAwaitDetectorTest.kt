/**
 * Copyright 2026 Santiago Mattiauda
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.santimattius.structured.lint.detectors

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask
import io.github.santimattius.structured.lint.LintTestStubs
import org.junit.Test

class AsyncWithoutAwaitDetectorTest {

    private val awaitStub = """
        package kotlinx.coroutines
        suspend fun <T> awaitAll(vararg deferreds: Deferred<T>): List<T> = error("stub")
        suspend fun <T> Collection<Deferred<T>>.awaitAll(): List<T> = error("stub")
        fun Job.cancel(): Unit = error("stub")
    """.trimIndent()

    private fun lint(body: String): TestLintResult {
        val code = "package test\n\nimport kotlinx.coroutines.*\n\n" + body.trimIndent()
        return TestLintTask.lint()
            .files(
                *LintTestStubs.coroutinesOnly().toTypedArray(),
                TestFiles.kotlin(awaitStub).indented(),
                TestFiles.kotlin(code).indented(),
            )
            .issues(AsyncWithoutAwaitDetector.ISSUE)
            .allowMissingSdk()
            .run()
    }

    @Test
    fun `reports async whose result is never used`() {
        lint(
            """
            fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
            }
            """
        ).expectErrorCount(1)
    }

    @Test
    fun `reports async that is only cancelled`() {
        lint(
            """
            fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
                d.cancel()
            }
            """
        ).expectErrorCount(1)
    }

    @Test
    fun `does not report async awaited on the variable`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
                d.await()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async passed to awaitAll`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope) {
                val a = scope.async { 1 }
                val b = scope.async { 2 }
                awaitAll(a, b)
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report list of async awaited through the variable`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope, xs: List<Int>) {
                val defs = xs.map { scope.async { it } }
                defs.awaitAll()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited into another val`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
                val r = d.await()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited in a return`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope): Int {
                val d = scope.async { 1 }
                return d.await()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited through a chain in a val initializer`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope, xs: List<Int>) {
                val r = xs.map { scope.async { it } }.awaitAll()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited directly in a val initializer`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope) {
                val r = scope.async { 1 }.await()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited inside a launch assigned to a val`() {
        lint(
            """
            fun test(scope: CoroutineScope, xs: List<Int>) {
                val job = scope.launch { xs.map { async { it } }.awaitAll() }
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async held in a val that is the awaited lambda result`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope, xs: List<Int>) {
                xs.map { val d = scope.async { it }; d }.awaitAll()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited through an alias`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
                val e = d
                e.await()
            }
            """
        ).expectClean()
    }

    @Test
    fun `does not report async awaited inside a collection`() {
        lint(
            """
            suspend fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
                listOf(d).awaitAll()
            }
            """
        ).expectClean()
    }

    @Test
    fun `reports async put in a collection that is never awaited`() {
        lint(
            """
            fun test(scope: CoroutineScope) {
                val d = scope.async { 1 }
                listOf(d)
            }
            """
        ).expectErrorCount(1)
    }

    @Test
    fun `reports async returned from a lambda whose result is never awaited`() {
        lint(
            """
            fun test(scope: CoroutineScope, xs: List<Int>) {
                val defs = xs.map { val d = scope.async { it }; d }
            }
            """
        ).expectErrorCount(1)
    }
}
