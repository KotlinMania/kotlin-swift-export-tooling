package reverseimportproof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderTest {
    @Test
    fun callbackPreservesConcretePayloadIdentity() {
        var calls = 0
        val value = Payload("typed")
        val callback = object : PayloadCallback {
            override fun apply(value: Payload): Payload {
                calls += 1
                return echoPayload(value)
            }
        }
        val provider = object : Provider {
            override fun deliver(value: Payload, callback: PayloadCallback): Delivery =
                Delivery(true, callback.apply(value), null)
        }
        val result = createDispatcher(provider).dispatch(value, callback)
        assertTrue(result.successful)
        assertSame(value, result.value)
        assertNull(result.failure)
        assertEquals(1, calls)
    }

    @Test
    fun successfulNullRemainsSuccess() {
        val provider = object : Provider {
            override fun deliver(value: Payload, callback: PayloadCallback): Delivery = Delivery(true, null, null)
        }
        val result = createDispatcher(provider).dispatch(Payload("unused"), identityCallback())
        assertTrue(result.successful)
        assertNull(result.value)
        assertNull(result.failure)
    }

    @Test
    fun failurePreservesOriginalObject() {
        val failure = object : ProviderFailure {
            override fun message(): String = "original failure"
        }
        val provider = object : Provider {
            override fun deliver(value: Payload, callback: PayloadCallback): Delivery = Delivery(false, null, failure)
        }
        val result = createDispatcher(provider).dispatch(Payload("unused"), identityCallback())
        assertFalse(result.successful)
        assertNull(result.value)
        assertSame(failure, result.failure)
        assertEquals("original failure", result.failure?.message())
    }

    private fun identityCallback(): PayloadCallback = object : PayloadCallback {
        override fun apply(value: Payload): Payload = value
    }
}
