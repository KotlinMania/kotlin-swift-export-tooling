package reverseimportproof

public class Payload(public val text: String)

public interface ProviderFailure {
    public fun message(): String
}

public class Delivery(
    public val successful: Boolean,
    public val value: Payload?,
    public val failure: ProviderFailure?,
) {
    init {
        require(successful == (failure == null))
        require(successful || value == null)
    }
}

public interface PayloadCallback {
    public fun apply(value: Payload): Payload
}

public interface Provider {
    public fun deliver(value: Payload, callback: PayloadCallback): Delivery
}

public open class SwiftBase

public class Dispatcher internal constructor(private val provider: Provider) {
    public fun dispatch(value: Payload, callback: PayloadCallback): Delivery = provider.deliver(value, callback)
}

public expect fun createDispatcher(provider: Provider): Dispatcher

public fun echoPayload(value: Payload): Payload = value
