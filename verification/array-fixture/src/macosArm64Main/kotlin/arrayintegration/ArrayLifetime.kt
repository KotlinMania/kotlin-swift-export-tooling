@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package arrayintegration

private var ephemeralValues: kotlin.native.ref.WeakReference<Array<String>>? = null

public fun makeEphemeralArrayList(): List<Array<String>> {
    val values = arrayOf("temporary")
    ephemeralValues = kotlin.native.ref.WeakReference(values)
    return listOf(values)
}

public fun ephemeralArrayAlive(): Boolean = ephemeralValues?.value != null
