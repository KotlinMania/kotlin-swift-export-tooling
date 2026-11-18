package original

class Box(var value: Int)
var invocations: Int = 0

@Deprecated("Use current callback API")
inline fun <T> originalCallback(block: () -> T): T = block()

@Deprecated("Use current mutation API")
fun mutate(box: Box, delta: Int): Box {
    invocations += 1
    box.value += delta
    return box
}

@Deprecated("Use current mutation API")
fun mutate(text: String, delta: Int): String = text + delta
