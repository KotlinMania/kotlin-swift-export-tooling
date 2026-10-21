@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package reverseimportproof

private var observedProvider: kotlin.native.ref.WeakReference<Provider>? = null

public fun observeProvider(provider: Provider) {
    observedProvider = kotlin.native.ref.WeakReference(provider)
}

public fun observedProviderAlive(): Boolean = observedProvider?.value != null

public fun collectProviderGarbage() {
    kotlin.native.runtime.GC.collect()
}
