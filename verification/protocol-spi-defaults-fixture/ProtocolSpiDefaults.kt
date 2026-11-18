@file:OptIn(
    ExperimentalSpiMarker::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package spidefaults

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalSpiMarker

public interface SpiDefaultProtocol {
    @ExperimentalSpiMarker
    public fun abstractSpiFlag(): Boolean

    @ExperimentalSpiMarker
    public fun abstractSpiWithParams(label: String, index: Int): Int

    @ExperimentalSpiMarker
    public fun abstractSpiVoid()

    @ExperimentalSpiMarker
    public fun defaultSpiFlag(): Boolean = true

    @ExperimentalSpiMarker
    public fun defaultSpiWithParams(label: String, index: Int): Boolean = label.length == index

    @ExperimentalSpiMarker
    public fun defaultSpiVoid() {}

    @ExperimentalSpiMarker
    public val abstractSpiProp: Int

    @ExperimentalSpiMarker
    public val defaultSpiProp: String
        get() = "default-prop"

    public fun regularLabel(): String = "regular-label"
}

public class MultiBoundOwner<T> where T : CharSequence, T : Comparable<T>

public class KotlinSpiImpl(
    private val baseOffset: Int,
) : SpiDefaultProtocol {
    private var voidCounter: Int = 0
    public var unsupportedModifier: MultiBoundOwner<String> = MultiBoundOwner()

    @Deprecated("use observedVoidCalls() instead")
    public var deprecatedSkip: Boolean = false

    @Deprecated("use observedVoidCalls() instead")
    public fun deprecatedSetSkip(skip: Boolean): KotlinSpiImpl {
        if (skip) voidCounter += 1
        return this
    }

    override fun abstractSpiFlag(): Boolean = baseOffset > 0

    override fun abstractSpiWithParams(label: String, index: Int): Int = baseOffset + label.length + index

    override fun abstractSpiVoid() {
        voidCounter += 1
    }

    override val abstractSpiProp: Int
        get() = baseOffset * 10 + voidCounter

    public fun observedVoidCalls(): Int = voidCounter
}

public fun createSpiDefaultProtocol(baseOffset: Int): SpiDefaultProtocol = KotlinSpiImpl(baseOffset)

public fun createKotlinSpiImpl(baseOffset: Int): KotlinSpiImpl = KotlinSpiImpl(baseOffset)

public fun touchSerializationProtocols(
    encoder: Encoder,
    decoder: Decoder,
    compositeEncoder: CompositeEncoder,
    compositeDecoder: CompositeDecoder,
    descriptor: SerialDescriptor,
): SerialDescriptor = descriptor
