/*
 * Copyright 2010-2025 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.phaser.PhaseDescription
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

@PhaseDescription(name = "WhenConditionOptimiserLowering")
class WhenConditionOptimiserLowering(val context: JvmBackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        irBody.transformChildrenVoid(WhenConditionOptimiserTransformer(context))
    }
}

private class WhenConditionOptimiserTransformer(val context: JvmBackendContext) : IrElementTransformerVoidWithContext() {
    override fun visitWhen(expression: IrWhen): IrExpression {
        if (expression.origin == IrStatementOrigin.WHEN)
            expression.transformInPlace(context)
        return expression
    }
}

private fun IrWhen.transformInPlace(context: JvmBackendContext) {
    for (branch in branches) {
        if (branch is IrElseBranch) continue
        val condition = branch.condition as? IrCall ?: continue
        if (condition.symbol != context.irBuiltIns.eqeqSymbol) continue

        val arg1 = condition.arguments[1]
        val const = if (arg1 is IrCall) {
            arg1.constDispatchReceiver() ?: continue
        } else (arg1 as? IrGetValue)?.constInitializer() ?: continue

        val value = when (const.value) {
            is Byte -> (const.value as Byte).toInt()
            is Short -> (const.value as Short).toInt()
            is Char -> (const.value as Char).code
            else -> continue
        }

        condition.arguments[1] = IrConstImpl.int(
            const.startOffset,
            const.endOffset,
            context.irBuiltIns.intType,
            value
        )
    }
}

private fun IrCall.constDispatchReceiver(): IrConst? {
//    if (origin == IrStatementOrigin.GET_PROPERTY) {
//        return constInitializer()
//    }

    val fqName = symbol.owner.kotlinFqName.asString()
    if (fqName != "kotlin.Byte.toInt" && fqName != "kotlin.Short.toInt") return null
    val arg = arguments[0]
    return (arg as? IrCall)?.constInitializer() ?: (arg as? IrGetValue)?.constInitializer()
//    return when (dispatchReceiver) {
//        is IrCall -> {
//            val receiver = dispatchReceiver as IrCall
//            if (receiver.origin == IrStatementOrigin.GET_PROPERTY) {
//                receiver.constInitializer()
//            } else {
//                null
//            }
//        }
//        is IrGetValue -> {
//            ((((dispatchReceiver as? IrGetValue)?.symbol?.owner) as? IrVariable)?.initializer as? IrConst)
//        }
//        else -> null
//    }
}

private fun IrCall.constInitializer(): IrConst? {
    val irReturn = symbol.owner.body?.statements[0] as? IrReturn ?: return null
    val getField = irReturn.value as? IrGetField ?: return null
    return getField.symbol.owner.initializer?.expression as? IrConst
}

private fun IrGetValue.constInitializer(): IrConst? {
    return ((symbol.owner) as? IrVariable)?.initializer as? IrConst
}