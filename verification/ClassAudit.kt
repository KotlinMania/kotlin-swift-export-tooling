import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.jetbrains.org.objectweb.asm.Attribute
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes

internal fun runtimeClass(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(0)
    val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
        private var sourceDebugAnnotations = 0
        override fun visitAttribute(attribute: Attribute) {
            error("Unsupported class attribute: ${attribute.type}")
        }
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor != "Lkotlin/jvm/internal/SourceDebugExtension;") {
                return super.visitAnnotation(descriptor, visible)
            }
            check(!visible && sourceDebugAnnotations++ == 0)
            var arrays = 0
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) { error("Unexpected source debug member") }
                override fun visitEnum(name: String?, descriptor: String?, value: String?) { error("Unexpected source debug enum") }
                override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor = error("Unexpected nested source debug annotation")
                override fun visitArray(name: String?): AnnotationVisitor {
                    check(name == "value" && arrays++ == 0)
                    var values = 0
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            check(name == null && values++ == 0)
                            check(value is String && value.startsWith("SMAP\n") && value.endsWith("*E\n"))
                            check(value.lineSequence().drop(1).first() in setOf(
                                "TypeBridging.kt", "SirAsSwiftSourcesPrinter.kt", "SirEnumFromKtSymbol.kt", "SirBridgeProviderImpl.kt",
                                "AsStringArray.kt", "AsStringArrayObjC.kt", "Types.kt", "SirCustomTypeTranslatorImpl.kt", "SirSession.kt",
                            ))
                        }
                        override fun visitEnum(name: String?, descriptor: String?, value: String?) { error("Unexpected source debug array enum") }
                        override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor = error("Unexpected source debug array annotation")
                        override fun visitArray(name: String?): AnnotationVisitor = error("Unexpected nested source debug array")
                        override fun visitEnd() { check(values == 1) }
                    }
                }
                override fun visitEnd() { check(arrays == 1) }
            }
        }
    }
    ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG)
    return writer.toByteArray()
}

fun main(arguments: Array<String>) {
    require(arguments.size >= 3)
    val input = Path(arguments[0])
    val output = Path(arguments[1])
    for (entry in arguments.drop(2)) {
        val target = output.resolve(entry)
        target.parent.createDirectories()
        target.writeBytes(runtimeClass(input.resolve(entry).readBytes()))
        println(entry)
    }
}
