@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)

package bytearrayproof

private var observed: kotlin.native.ref.WeakReference<ByteArray>? = null

public fun observeBytes(value: ByteArray) {
    observed = kotlin.native.ref.WeakReference(value)
}

public fun observedBytesAlive(): Boolean = observed?.value != null

public fun collectByteArrayGarbage() {
    kotlin.native.runtime.GC.collect()
}
