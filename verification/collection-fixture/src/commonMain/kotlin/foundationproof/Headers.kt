package foundationproof

class HeaderStore(initialHeaders: Map<String, List<String>>) {
    private var contents: Map<String, List<String>> = initialHeaders.mapValues { (_, values) -> values.toList() }

    val headers: Map<String, List<String>>
        get() = contents

    fun replaceHeader(name: String, values: List<String>) {
        contents = contents + (name to values.toList())
    }
}

fun kotlinHeaders(): Map<String, List<String>> = linkedMapOf(
    "X-Trace" to listOf("one", "two", "two"),
    "Empty" to emptyList(),
    "Unicode" to listOf("α", "東京"),
)

fun echoHeaders(headers: Map<String, List<String>>): Map<String, List<String>> = headers

fun echoOptionalHeaders(headers: Map<String, List<String>>?): Map<String, List<String>>? = headers

fun echoNestedHeaders(
    headers: Map<String, Map<String, List<String>>>,
): Map<String, Map<String, List<String>>> = headers

fun echoHeaderLists(headers: List<Map<String, List<String>>>): List<Map<String, List<String>>> = headers

fun callWithHeaders(
    headers: Map<String, List<String>>,
    transform: (Map<String, List<String>>) -> Map<String, List<String>>,
): Map<String, List<String>> = transform(headers)

fun callWithOptionalHeaders(
    headers: Map<String, List<String>>?,
    transform: (Map<String, List<String>>?) -> Map<String, List<String>>?,
): Map<String, List<String>>? = transform(headers)

fun kotlinHeaderTransform(): (Map<String, List<String>>) -> Map<String, List<String>> = { headers ->
    headers + ("Native" to listOf("added"))
}
