@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
private annotation class __KotlinSwiftExportTarget_callback(val packageName: String, val functionName: String)

@__KotlinSwiftExportTarget_callback("original", "originalCallback")
private external fun <T> callbackTarget(block: () -> T): T

@__KotlinSwiftExportTarget_callback("original", "mutate")
private external fun mutateTarget(box: original.Box, delta: Int): original.Box

fun <T> callbackBridge(block: () -> T): T = callbackTarget(block)
fun mutationBridge(box: original.Box, delta: Int): original.Box = mutateTarget(box, delta)

fun main() {
    var calls = 0
    val box = original.Box(7)
    check(callbackBridge { calls += 1; box } === box)
    check(calls == 1)
    check(callbackBridge<Any?> { null } == null)
    val sentinel = IllegalStateException("same exception")
    try {
        callbackBridge<Unit> { throw sentinel }
        error("Expected sentinel")
    } catch (failure: IllegalStateException) {
        check(failure === sentinel)
    }
    check(mutationBridge(box, 4) === box)
    check(box.value == 11)
    check(original.invocations == 1)
    println("PASS generic identity, nullable result, callback once, exception identity, mutation, overload")
}

private val residual: (() -> Int) -> Int = ::callbackTarget
