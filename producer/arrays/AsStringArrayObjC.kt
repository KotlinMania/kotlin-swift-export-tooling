/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.providers.impl.BridgeProvider

import org.jetbrains.kotlin.sir.SirOptionalType
import org.jetbrains.kotlin.sir.SirType
import org.jetbrains.kotlin.sir.providers.SirSession
import org.jetbrains.kotlin.sir.providers.SirTypeNamer
import org.jetbrains.kotlin.sir.providers.impl.BridgeProvider.Bridge.AsObjCBridged

internal class AsStringArrayObjC(type: SirType) : AsObjCBridged(type, CType.NSObjectClass) {
    override val requiresNativeCarrier: Boolean get() = true
    override fun nativeValueType(typeNamer: SirTypeNamer): String = "kotlin.Array<kotlin.String>"
    override fun nativeStoredType(typeNamer: SirTypeNamer): String = "KotlinStringArrayStorage"
    override fun storedSwiftType(typeNamer: SirTypeNamer): String = "NSObject"
    override fun toNativeStored(typeNamer: SirTypeNamer, value: String): String = "KotlinStringArrayStorage($value)"
    override fun fromNativeStored(typeNamer: SirTypeNamer, value: String): String = "$value.values"

    override val inSwiftSources = object : ValueConversion {
        context(session: SirSession)
        override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String =
            "$valueExpression.__asObjC()"

        context(session: SirSession)
        override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String =
            "KotlinRuntimeSupport.KotlinStringArray.__fromObjC($valueExpression)"
    }
}

internal class AsNullableObjCCarrier(private val wrapped: AsObjCBridged) : AsObjCBridged(SirOptionalType(wrapped.swiftType), CType.NSObjectClass) {
    override val requiresNativeCarrier: Boolean get() = true
    override fun nativeValueType(typeNamer: SirTypeNamer): String = wrapped.nativeValueType(typeNamer) + "?"
    override fun nativeStoredType(typeNamer: SirTypeNamer): String = wrapped.nativeStoredType(typeNamer) + "?"
    override fun storedSwiftType(typeNamer: SirTypeNamer): String = "NSObject"
    override fun toNativeStored(typeNamer: SirTypeNamer, value: String): String =
        "$value?.let { __present -> ${wrapped.toNativeStored(typeNamer, "__present")} }"
    override fun fromNativeStored(typeNamer: SirTypeNamer, value: String): String =
        "$value?.let { __present -> ${wrapped.fromNativeStored(typeNamer, "__present")} }"

    override val inSwiftSources = object : ValueConversion {
        context(session: SirSession)
        override fun swiftToKotlin(typeNamer: SirTypeNamer, valueExpression: String): String {
            val conversion = wrapped.inSwiftSources.swiftToKotlin(typeNamer, "__present")
            val stored = if (wrapped.objcFoundationClass == "NSObject") conversion else "($conversion as ${wrapped.objcFoundationClass})"
            return "$valueExpression.map { __present -> NSObject in $stored } ?? NSNull()"
        }

        context(session: SirSession)
        override fun kotlinToSwift(typeNamer: SirTypeNamer, valueExpression: String): String {
            val value = if (wrapped.storedSwiftType(typeNamer) == "NSObject") valueExpression else "($valueExpression as! ${wrapped.storedSwiftType(typeNamer)})"
            val conversion = wrapped.inSwiftSources.kotlinToSwift(typeNamer, value)
            return "($valueExpression is NSNull ? nil : $conversion)"
        }
    }
}
