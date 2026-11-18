package nullableunsignedproof

public class NullableUnsignedValues(
    public var byteValue: UByte?,
    public var shortValue: UShort?,
    public var intValue: UInt?,
    public var longValue: ULong?,
) {
    public fun copyValues(
        byteValue: UByte?,
        shortValue: UShort?,
        intValue: UInt?,
        longValue: ULong?,
    ): NullableUnsignedValues = NullableUnsignedValues(byteValue, shortValue, intValue, longValue)

    public fun readUByte(): UByte? = byteValue

    public fun readUShort(): UShort? = shortValue

    public fun readUInt(): UInt? = intValue

    public fun readULong(): ULong? = longValue
}

public fun echoUByte(value: UByte?): UByte? = value

public fun echoUShort(value: UShort?): UShort? = value

public fun echoUInt(value: UInt?): UInt? = value

public fun echoULong(value: ULong?): ULong? = value
