/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.providers.impl.BridgeProvider

import org.jetbrains.kotlin.sir.SirNominalType
import org.jetbrains.kotlin.sir.SirVisibility
import org.jetbrains.kotlin.sir.builder.buildClass
import org.jetbrains.kotlin.sir.providers.SirSession
import org.jetbrains.kotlin.sir.providers.SirTypeNamer
import org.jetbrains.kotlin.sir.providers.source.KotlinRuntimeElement
import org.jetbrains.kotlin.sir.providers.utils.KotlinRuntimeSupportModule

internal class AsStringArray : BidirectionalBridge {
    override val swiftType = SirNominalType(declaration)
    override val kotlinType = KotlinType.KotlinObject
    override val cType = CType.Object

    override fun kotlinReverseParameterTypeFqName(typeNamer: SirTypeNamer): String = "kotlin.Array<kotlin.String>"

    override val inKotlinSources = object : ValueConversion {
        context(session: SirSession)
        override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String =
            "kotlinStringArrayConsumeReference($valueExpression)"

        context(session: SirSession)
        override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String =
            "kotlin.native.internal.ref.createRetainedExternalRCRef(KotlinStringArrayStorage($valueExpression))"
    }

    override val inSwiftSources = object : ValueConversion {
        context(session: SirSession)
        override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String =
            "$valueExpression.__copyExternalRCRef()"

        context(session: SirSession)
        override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String =
            "KotlinRuntimeSupport.KotlinStringArray(__externalRCRefUnsafe: $valueExpression)"
    }

    private companion object {
        val declaration = buildClass {
            name = "KotlinStringArray"
            origin = KotlinRuntimeElement()
            visibility = SirVisibility.PUBLIC
        }.apply {
            parent = KotlinRuntimeSupportModule
        }
    }
}
