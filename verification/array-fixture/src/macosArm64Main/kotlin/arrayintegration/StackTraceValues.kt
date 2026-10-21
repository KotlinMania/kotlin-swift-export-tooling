package arrayintegration

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
public fun stackTraceValues(): Array<String> = IllegalStateException("array witness").getStackTrace()

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
public fun collectNativeGarbage() { kotlin.native.runtime.GC.collect() }
