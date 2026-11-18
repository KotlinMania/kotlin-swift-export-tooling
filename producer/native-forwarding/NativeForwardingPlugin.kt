@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.kotlinmania.swiftexport.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.registerExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class NativeForwardingPlugin : CompilerPluginRegistrar() {
    override val pluginId: String = "io.github.kotlinmania.swift-export-native-forwarding"
    override val supportsK2: Boolean = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(NativeForwardingExtension())
    }
}

private const val MARKER_PREFIX = "__KotlinSwiftExportTarget_"
private class NativeForwardingExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val replacements = mutableMapOf<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>()
        val markers = mutableSetOf<IrClass>()
        for (file in moduleFragment.files) {
            for (binding in file.declarations.filterIsInstance<IrSimpleFunction>()) {
                val marker = binding.annotations.singleOrNull {
                    it.classSymbol.owner.name.asString().startsWith(MARKER_PREFIX)
                } ?: continue
                val markerClass = marker.classSymbol.owner
                require(markerClass.parent === file && markerClass.visibility == DescriptorVisibilities.PRIVATE)
                require(binding.visibility == DescriptorVisibilities.PRIVATE && binding.isExternal && binding.body == null)
                require(!binding.isSuspend && binding.typeParameters.none { it.isReified })
                require(binding.parameters.all { it.kind == IrParameterKind.Regular && it.varargElementType == null })
                val packageName = marker.stringArgument("packageName")
                val functionName = marker.stringArgument("functionName")
                val callableId = CallableId(FqName(packageName), Name.identifier(functionName))
                val targets = pluginContext.finderForSource(file).findFunctions(callableId)
                    .filter { target -> matches(binding, target.owner) }
                require(targets.size == 1) { "Swift Export binding ${binding.name} requires exactly one matching target: $callableId; found ${targets.size}" }
                replacements[binding.symbol] = targets.single()
                markers += markerClass
            }
        }
        if (replacements.isEmpty()) return
        val uses = replacements.keys.associateWith { 0 }.toMutableMap()
        moduleFragment.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val target = replacements[expression.symbol] ?: return expression
                require(expression.superQualifierSymbol == null)
                require(expression.typeArguments.size == target.owner.typeParameters.size)
                require(expression.arguments.size == target.owner.parameters.size && expression.arguments.none { it == null })
                val callTypes = expression.typeArguments.map { requireNotNull(it) }
                val substitutor = IrTypeSubstitutor(target.owner.typeParameters.map { it.symbol }, callTypes)
                require(substitutor.substitute(target.owner.returnType) == expression.type) {
                    "Swift Export binding call return type differs from original target"
                }
                uses[expression.symbol] = uses.getValue(expression.symbol) + 1
                return irCall(expression, target)
            }
        })
        require(uses.values.all { it > 0 }) { "Unused Swift Export target binding" }
        for (file in moduleFragment.files) {
            file.declarations.removeAll { declaration ->
                declaration in markers || declaration is IrSimpleFunction && declaration.symbol in replacements
            }
        }
        val removedClasses = markers.map { it.symbol }.toSet()
        fun checkType(type: IrType) {
            if (type is IrSimpleType) {
                require(type.classifier !in removedClasses) { "Removed Swift Export marker used as a type" }
                for (argument in type.arguments) if (argument is IrTypeProjection) checkType(argument.type)
            }
            require(type.annotations.none { it.classSymbol in removedClasses }) { "Removed marker used as a type annotation" }
        }
        moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrAnnotation) {
                    require(element.classSymbol !in removedClasses) { "Removed Swift Export marker annotation remains" }
                } else if (element is IrDeclarationReference) {
                    require(element.symbol !in replacements && element.symbol !in removedClasses) {
                        "Unresolved Swift Export target binding reference"
                    }
                    if (element is IrConstructorCall) require(element.symbol.owner.parent !in markers)
                }
                if (element is IrFunctionReference) require(element.reflectionTarget !in replacements)
                if (element is IrRichFunctionReference) {
                    require(element.reflectionTargetSymbol !in replacements && element.overriddenFunctionSymbol !in replacements)
                }
                if (element is IrExpression) checkType(element.type)
                if (element is IrValueDeclaration) checkType(element.type)
                if (element is IrField) checkType(element.type)
                if (element is IrFunction) checkType(element.returnType)
                if (element is IrClass) element.superTypes.forEach(::checkType)
                if (element is IrTypeParameter) element.superTypes.forEach(::checkType)
                if (element is IrTypeOperatorCall) checkType(element.typeOperand)
                if (element is IrClassReference) checkType(element.classType)
                if (element is IrFunctionAccessExpression) element.typeArguments.filterNotNull().forEach(::checkType)
                if (element is IrAnnotationContainer) require(element.annotations.none { it.classSymbol in removedClasses })
                element.acceptChildrenVoid(this)
            }
        })
    }

    private fun matches(binding: IrSimpleFunction, target: IrSimpleFunction): Boolean {
        if (target.parent !is IrPackageFragment || target.visibility != DescriptorVisibilities.PUBLIC) return false
        if (target.isSuspend || target.typeParameters.any { it.isReified }) return false
        if (binding.typeParameters.size != target.typeParameters.size || binding.parameters.size != target.parameters.size) return false
        val substitutor = IrTypeSubstitutor(target.typeParameters.map { it.symbol }, binding.typeParameters.map { it.defaultType })
        if (substitutor.substitute(target.returnType) != binding.returnType) return false
        if (!target.typeParameters.zip(binding.typeParameters).all { (original, generated) ->
            original.variance == generated.variance && original.superTypes.map(substitutor::substitute) == generated.superTypes
        }) return false
        return target.parameters.zip(binding.parameters).all { (original, generated) ->
            original.kind == generated.kind && original.kind == IrParameterKind.Regular &&
                original.varargElementType == null && generated.varargElementType == null &&
                substitutor.substitute(original.type) == generated.type
        }
    }
}


private fun IrAnnotation.stringArgument(name: String): String {
    return (argumentMapping[Name.identifier(name)] as? IrConst)?.value as? String
        ?: error("Swift Export binding requires constant string $name")
}
