package arrayintegration

public class ArrayMapKey(public val name: String)

public class NestedStringArrays {
    private val key: ArrayMapKey = ArrayMapKey("key")
    private val values: Array<String> = arrayOf("left", "right")
    private val empty: Array<String> = emptyArray()

    public fun key(): ArrayMapKey = key
    public fun objectKeys(): Map<ArrayMapKey, Array<String>> = mapOf(key to values)
    public fun listKeys(): Map<List<String>, Array<String>> = mapOf(listOf("key") to values)
    public fun carrierKeys(): Map<Array<String>, List<String>> = mapOf(values to listOf("one", "two"))
    public fun callbackObjectKeys(callback: (Map<ArrayMapKey, Array<String>>) -> Map<ArrayMapKey, Array<String>>): Boolean = callback(objectKeys())[key] === values
    public fun callbackListKeys(callback: (Map<List<String>, Array<String>>) -> Map<List<String>, Array<String>>): Boolean = callback(listKeys())[listOf("key")] === values
    public fun callbackCarrierKeys(callback: (Map<Array<String>, List<String>>) -> Map<Array<String>, List<String>>): Boolean = callback(carrierKeys())[values] == listOf("one", "two")
    public fun nestedSets(): Set<List<Array<String>>> = setOf(listOf(values), listOf(empty))
    public fun callbackNestedSets(callback: (Set<List<Array<String>>>) -> Set<List<Array<String>>>): Boolean {
        val returned = callback(nestedSets())
        return returned.size == 2 && returned.any { it.single() === values } && returned.any { it.single() === empty }
    }
    public fun objectValues(): Map<Array<String>, ArrayMapKey> = mapOf(values to key)
    public fun callbackObjectValues(callback: (Map<Array<String>, ArrayMapKey>) -> Map<Array<String>, ArrayMapKey>): Boolean = callback(objectValues())[values] === key
    public fun first(): Array<String> = values
    public fun empty(): Array<String> = empty
    public fun original(index: Int): String = values[index]
    public fun lists(): List<Array<String>> = listOf(values, empty, values)
    public fun nullableLists(): List<Array<String>?> = listOf(null, empty, values)
    public fun deepLists(): List<List<Array<String>>?> = listOf(null, listOf(values))
    public fun sets(): Set<Array<String>> = setOf(values, empty)
    public fun maps(): Map<String, Array<String>> = mapOf("value" to values, "empty" to empty)
    public fun arrayKeys(): Map<Array<String>, String> = mapOf(values to "value", empty to "empty")
    public fun strings(): List<String> = listOf("first", "last")
    public fun acceptLists(values: List<Array<String>>): Boolean = values.size == 3 && values[0] === this.values && values[1].isEmpty() && values[2] === this.values
    public fun acceptNullableLists(values: List<Array<String>?>): Boolean = values.size == 3 && values[0] == null && values[1]?.isEmpty() == true && values[2] === this.values
    public fun acceptDeepLists(values: List<List<Array<String>>?>): Boolean = values.size == 2 && values[0] == null && values[1]?.single() === this.values
    public fun acceptSets(values: Set<Array<String>>): Boolean = values.size == 2 && values.contains(this.values) && values.contains(this.empty)
    public fun acceptMaps(values: Map<String, Array<String>>): Boolean = values["value"] === this.values && values["empty"] === this.empty
    public fun acceptArrayKeys(values: Map<Array<String>, String>): Boolean = values[this.values] == "value" && values[this.empty] == "empty"
    public fun acceptVariadic(vararg values: Array<String>): Boolean = values.size == 2 && values[0] === this.values && values[1] === this.empty
    public fun acceptVariadicLists(vararg values: List<Array<String>>): Boolean = values.size == 2 && values[0].single() === this.values && values[1].single() === this.empty
    public fun callbackLists(callback: (List<Array<String>>) -> List<Array<String>>): Boolean = acceptLists(callback(lists()))
    public fun callbackNullableLists(callback: (List<Array<String>>?) -> List<Array<String>>?): Boolean {
        if (callback(null) != null) return false
        val returned = callback(lists()) ?: return false
        return acceptLists(returned)
    }
}

public open class ArraySink {
    public open fun accept(vararg values: Array<String>): Boolean = values.size == 2
}

public fun dispatchArraySink(sink: ArraySink, values: List<Array<String>>): Boolean = sink.accept(*values.toTypedArray())
