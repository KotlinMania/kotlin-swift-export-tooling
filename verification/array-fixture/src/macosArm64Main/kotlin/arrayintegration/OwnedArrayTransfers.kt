@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package arrayintegration

private var transferredValues: kotlin.native.ref.WeakReference<Array<String>>? = null

public fun makeTransferredArray(): Array<String> {
    val values = arrayOf("temporary")
    transferredValues = kotlin.native.ref.WeakReference(values)
    return values
}

public fun transferredArrayAlive(): Boolean = transferredValues?.value != null

public fun acceptNullableTransferredArray(values: Array<String>?): Boolean =
    values == null || values[0] == "temporary"

public fun acceptTransferredArrayCallback(callback: () -> Array<String>): Boolean =
    callback()[0] == "temporary"
