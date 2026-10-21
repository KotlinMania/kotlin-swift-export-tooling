package bytearrayproof

public class ByteArrayOwner {
    private var bytes: ByteArray? = null

    public fun retain(value: ByteArray) {
        bytes = value
    }

    public fun clear() {
        bytes = null
    }

    public fun read(index: Int): Byte = checkNotNull(bytes)[index]

    public fun same(value: ByteArray): Boolean = bytes === value

    public fun current(): ByteArray? = bytes
}

public fun sumBytes(value: ByteArray): Int = value.sumOf { it.toInt() }
