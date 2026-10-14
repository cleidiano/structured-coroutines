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

import com.android.tools.lint.detector.api.*
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Severity
import io.github.santimattius.structured.lint.utils.LintDocUrl
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Detects async calls whose Deferred result is never awaited.
 * 
 * Best Practice 1.2: Using async as if it were launch (and never calling await)
 * 
 * Using async just "to launch things" and not consuming the Deferred is confusing 
 * for code readers and can hide exceptions that remain hanging inside the Deferred.
 * 
 * Example:
 * ```kotlin
 * // ❌ BAD - async without await
 * val deferred = scope.async { computeValue() }
 * // deferred never used - exception may be hidden
 * 
 * // ✅ GOOD - async with await
 * val deferred = scope.async { computeValue() }
 * val result = deferred.await()
 * 
 * // ✅ GOOD - async with awaitAll
 * val deferred1 = scope.async { computeValue1() }
 * val deferred2 = scope.async { computeValue2() }
 * val results = awaitAll(deferred1, deferred2)
 * 
 * // ✅ GOOD - Use launch if no result needed
 * scope.launch { doWork() }  // No result needed
 * ```
 */
class AsyncWithoutAwaitDetector : Detector(), SourceCodeScanner {
    
    companion object {
        val ISSUE = Issue.create(
            id = "AsyncWithoutAwait",
            briefDescription = "async without await",
            explanation = """
                [SCOPE_002] Using async without calling await() is confusing and can hide exceptions
                that remain hanging inside the Deferred. If you don't need a result,
                use launch instead. Always call .await() or awaitAll() on Deferred values.

                See: ${LintDocUrl.buildDocLink("12-scope_002--using-async-without-calling-await")}
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                AsyncWithoutAwaitDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            )
        )
        
        private val ASYNC_NAME = "async"
        private val AWAIT_NAME = "await"
        private val AWAIT_ALL_NAME = "awaitAll"
        private val AWAIT_CALLS = setOf(AWAIT_NAME, AWAIT_ALL_NAME)
    }
    
    override fun getApplicableMethodNames(): List<String> {
        return listOf(ASYNC_NAME)
    }
    
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: com.intellij.psi.PsiMethod
    ) {
        if (node.methodName != ASYNC_NAME) return
        
        // Find the containing function/block
        val containingBlock = findContainingBlock(node) ?: return
        
        // Check if this async is part of a variable assignment
        val variableName = findVariableNameFromAsyncCall(node, containingBlock) ?: return

        val ktCall = node.sourcePsi as? KtCallExpression
        if (ktCall != null && flowsToAwait(ktCall)) return

        // Check if await() is called on this variable in the same block
        if (!hasAwaitCall(containingBlock, variableName)) {
            context.report(
                ISSUE,
                node,
                context.getLocation(node),
                "async result is never awaited. Call .await() or awaitAll() on the Deferred, or use launch if no result is needed"
            )
        }
    }
    
    /**
     * Finds the containing block (function body) for the async call.
     */
    private fun findContainingBlock(call: UCallExpression): UExpression? {
        var current: UElement? = call
        while (current != null) {
            // UMethod represents both Java methods and Kotlin functions
            if (current is UMethod) {
                return current.uastBody
            }
            if (current is UBlockExpression) {
                return current
            }
            current = current.uastParent
        }
        return null
    }
    
    /**
     * Finds the variable name from an async call assignment.
     * Handles direct assignment (val d = async { }) and async inside initializer (val defs = xs.map { async { } }).
     */
    private fun findVariableNameFromAsyncCall(
        asyncCall: UCallExpression,
        block: UExpression
    ): String? {
        var current: UElement? = asyncCall
        while (current != null) {
            // Check for variable declaration: val x = async { } or val defs = xs.map { async { } }
            if (current is UVariable) {
                val initializer = current.uastInitializer
                if (initializer == asyncCall ||
                    (initializer is UCallExpression &&
                     initializer.sourcePsi == asyncCall.sourcePsi)) {
                    return current.name
                }
                // Async is inside initializer (e.g. val defs = xs.map { async { } })
                if (initializer != null && isDescendantOf(asyncCall, initializer)) {
                    return current.name
                }
            }

            // Check for binary assignment: x = async { }
            if (current is UBinaryExpression) {
                val operatorText = current.operator.text
                if (operatorText == "=") {
                    val right = current.rightOperand
                    if (right == asyncCall || right.sourcePsi == asyncCall.sourcePsi) {
                        val left = current.leftOperand
                        if (left is UReferenceExpression) {
                            return left.asSourceString()
                        }
                    }
                }
            }

            current = current.uastParent
        }

        return null
    }

    private fun isDescendantOf(element: UElement, ancestor: UElement): Boolean {
        var p: UElement? = element
        while (p != null) {
            if (p == ancestor) return true
            p = p.uastParent
        }
        return false
    }
    
    /**
     * Checks if await() or awaitAll() is called on the given variable in the block.
     * Handles variable.await(), variable.awaitAll(), awaitAll(variable, ...), and list.awaitAll().
     */
    private fun hasAwaitCall(block: UExpression, variableName: String): Boolean {
        var foundAwait = false

        block.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val methodName = node.methodName

                // Check for variable.await() or variable.awaitAll() (receiver is our variable)
                if (methodName == AWAIT_NAME || methodName == AWAIT_ALL_NAME) {
                    val receiver = node.receiver
                    val receiverName = when (receiver) {
                        is UReferenceExpression -> receiver.asSourceString()
                        is UQualifiedReferenceExpression -> receiver.receiver.asSourceString()
                        else -> null
                    }
                    if (receiverName == variableName || (receiverName != null && receiverName.endsWith(".$variableName"))) {
                        foundAwait = true
                        return false
                    }
                }

                // Check for awaitAll(deferred1, deferred2, ...)
                if (methodName == AWAIT_ALL_NAME) {
                    for (arg in node.valueArguments) {
                        val argSource = arg.asSourceString()
                        if (argSource == variableName || argSource.endsWith(".$variableName")) {
                            foundAwait = true
                            return false
                        }
                    }
                }

                return super.visitCallExpression(node)
            }
        })

        return foundAwait
    }

    // Climbs from the async call while its value keeps flowing outward (call chains, call arguments,
    // lambda results, local vals) and stops at the first await/awaitAll. Intermediate call names
    // don't matter: types guarantee that whatever reaches awaitAll() is a collection of Deferreds.
    private fun flowsToAwait(start: KtExpression): Boolean {
        var current: KtExpression = start
        while (true) {
            val parent = current.parent
            current = when {
                parent is KtQualifiedExpression && parent.selectorExpression == current -> parent
                parent is KtQualifiedExpression && parent.receiverExpression == current -> {
                    val selector = parent.selectorExpression as? KtCallExpression
                    if (selector?.calleeExpression?.text in AWAIT_CALLS) return true
                    parent
                }
                parent is KtParenthesizedExpression -> parent
                parent is KtValueArgument -> {
                    val call = parent.getParentOfType<KtCallExpression>(strict = true) ?: return false
                    if (call.calleeExpression?.text == AWAIT_ALL_NAME) return true
                    call
                }
                parent is KtBlockExpression && parent.statements.lastOrNull() == current ->
                    lambdaOwnerCall(parent) ?: return false
                parent is KtProperty && parent.initializer == current -> return variableFlowsToAwait(parent)
                else -> return false
            }
        }
    }

    // Name-based like hasAwaitCall; terminates because each hop moves to a later declaration.
    private fun variableFlowsToAwait(property: KtProperty): Boolean {
        val name = property.name ?: return false
        val scope = property.parent as? KtBlockExpression ?: return false
        return scope.collectDescendantsOfType<KtNameReferenceExpression> {
            it.getReferencedName() == name && it.textOffset > property.textOffset
        }.any { flowsToAwait(it) }
    }

    private fun lambdaOwnerCall(lambdaBody: KtBlockExpression): KtCallExpression? {
        val lambda = (lambdaBody.parent as? KtFunctionLiteral)?.parent as? KtLambdaExpression ?: return null
        return when (val holder = lambda.parent) {
            is KtLambdaArgument -> holder.parent as? KtCallExpression
            is KtValueArgument -> holder.parent?.parent as? KtCallExpression
            else -> null
        }
    }
}
