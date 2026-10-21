import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes

private fun auditFixture(extra: (AnnotationVisitor) -> Unit = {}, repeat: Boolean = false, arrayExtra: (AnnotationVisitor) -> Unit = {}, debug: String = "SMAP\nTypeBridging.kt\nKotlin\n*E\n", field: String = "value"): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "AuditFixture", null, "java/lang/Object", null)
    writer.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd()
    val annotation = writer.visitAnnotation("Lkotlin/jvm/internal/SourceDebugExtension;", false)
    val array = annotation.visitArray("value")
    array.visit(null, debug)
    arrayExtra(array)
    array.visitEnd()
    extra(annotation)
    annotation.visitEnd()
    if (repeat) writer.visitAnnotation("Lkotlin/jvm/internal/SourceDebugExtension;", false).visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
}

fun main() {
    val valid = runtimeClass(auditFixture())
    check(valid.contentEquals(runtimeClass(auditFixture(debug = "SMAP\nTypeBridging.kt\nKotlin\n*L\n1#1:1\n*E\n"))))
    check(!valid.contentEquals(runtimeClass(auditFixture(field = "different"))))
    val invalid = listOf(
        auditFixture(extra = { it.visit("unexpected", "value") }),
        auditFixture(extra = { it.visitEnum("unexpected", "LValue;", "X") }),
        auditFixture(extra = { it.visitAnnotation("unexpected", "LValue;").visitEnd() }),
        auditFixture(extra = { it.visitArray("value").visitEnd() }),
        auditFixture(repeat = true),
        auditFixture(arrayExtra = { it.visitEnum(null, "LValue;", "X") }),
        auditFixture(arrayExtra = { it.visitAnnotation(null, "LValue;").visitEnd() }),
        auditFixture(arrayExtra = { it.visitArray(null).visitEnd() }),
        auditFixture(debug = "not a source map"),
    )
    invalid.forEach { check(runCatching { runtimeClass(it) }.isFailure) }
    listOf("AsStringArray.kt", "AsStringArrayObjC.kt", "Types.kt", "SirCustomTypeTranslatorImpl.kt", "SirSession.kt", "SirInitFromKtSymbol.kt", "lazyWithSessions.kt").forEach { source ->
        check(valid.contentEquals(runtimeClass(auditFixture(debug = "SMAP\n$source\nKotlin\n*E\n"))))
    }
    check(runCatching { runtimeClass(auditFixture(debug = "SMAP\nUnknown.kt\nKotlin\n*E\n")) }.isFailure)
    println("Nineteen class audit checks passed")
}
