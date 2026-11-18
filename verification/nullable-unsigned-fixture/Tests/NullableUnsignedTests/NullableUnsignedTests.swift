import Testing
import NullableUnsignedProof

struct NullableUnsignedTests {
    @Test func nullableUBytePreservesEveryBoundary() {
        let boundaries: [UInt8?] = [nil, 0, 127, 128, 255]
        for value in boundaries {
            let original = NullableUnsignedValues(byteValue: value, shortValue: nil, intValue: nil, longValue: nil)
            let constructed: UInt8? = original.byteValue
            #expect(constructed == value)
            let returned: UInt8? = original.readUByte()
            #expect(returned == value)
            let echoed: UInt8? = echoUByte(value: value)
            #expect(echoed == value)

            let copied = original.copyValues(byteValue: value, shortValue: nil, intValue: nil, longValue: nil)
            #expect(copied !== original)
            #expect(copied.byteValue == value)
            #expect(copied.readUByte() == value)

            original.byteValue = nil
            #expect(original.byteValue == nil)
            #expect(original.readUByte() == nil)
            original.byteValue = value
            #expect(original.byteValue == value)
            #expect(original.readUByte() == value)
            original.byteValue = UInt8.max
            #expect(original.readUByte() == UInt8.max)
            #expect(copied.byteValue == value)
            original.byteValue = nil
            #expect(original.readUByte() == nil)
        }
    }

    @Test func nullableUShortPreservesEveryBoundary() {
        let boundaries: [UInt16?] = [nil, 0, 32767, 32768, 65535]
        for value in boundaries {
            let original = NullableUnsignedValues(byteValue: nil, shortValue: value, intValue: nil, longValue: nil)
            let constructed: UInt16? = original.shortValue
            #expect(constructed == value)
            let returned: UInt16? = original.readUShort()
            #expect(returned == value)
            let echoed: UInt16? = echoUShort(value: value)
            #expect(echoed == value)

            let copied = original.copyValues(byteValue: nil, shortValue: value, intValue: nil, longValue: nil)
            #expect(copied !== original)
            #expect(copied.shortValue == value)
            #expect(copied.readUShort() == value)

            original.shortValue = nil
            #expect(original.shortValue == nil)
            #expect(original.readUShort() == nil)
            original.shortValue = value
            #expect(original.shortValue == value)
            #expect(original.readUShort() == value)
            original.shortValue = UInt16.max
            #expect(original.readUShort() == UInt16.max)
            #expect(copied.shortValue == value)
            original.shortValue = nil
            #expect(original.readUShort() == nil)
        }
    }

    @Test func nullableUIntPreservesEveryBoundary() {
        let boundaries: [UInt32?] = [nil, 0, 2147483647, 2147483648, 4294967295]
        for value in boundaries {
            let original = NullableUnsignedValues(byteValue: nil, shortValue: nil, intValue: value, longValue: nil)
            let constructed: UInt32? = original.intValue
            #expect(constructed == value)
            let returned: UInt32? = original.readUInt()
            #expect(returned == value)
            let echoed: UInt32? = echoUInt(value: value)
            #expect(echoed == value)

            let copied = original.copyValues(byteValue: nil, shortValue: nil, intValue: value, longValue: nil)
            #expect(copied !== original)
            #expect(copied.intValue == value)
            #expect(copied.readUInt() == value)

            original.intValue = nil
            #expect(original.intValue == nil)
            #expect(original.readUInt() == nil)
            original.intValue = value
            #expect(original.intValue == value)
            #expect(original.readUInt() == value)
            original.intValue = UInt32.max
            #expect(original.readUInt() == UInt32.max)
            #expect(copied.intValue == value)
            original.intValue = nil
            #expect(original.readUInt() == nil)
        }
    }

    @Test func nullableULongPreservesEveryBoundary() {
        let boundaries: [UInt64?] = [nil, 0, 9223372036854775807, 9223372036854775808, 18446744073709551615]
        for value in boundaries {
            let original = NullableUnsignedValues(byteValue: nil, shortValue: nil, intValue: nil, longValue: value)
            let constructed: UInt64? = original.longValue
            #expect(constructed == value)
            let returned: UInt64? = original.readULong()
            #expect(returned == value)
            let echoed: UInt64? = echoULong(value: value)
            #expect(echoed == value)

            let copied = original.copyValues(byteValue: nil, shortValue: nil, intValue: nil, longValue: value)
            #expect(copied !== original)
            #expect(copied.longValue == value)
            #expect(copied.readULong() == value)

            original.longValue = nil
            #expect(original.longValue == nil)
            #expect(original.readULong() == nil)
            original.longValue = value
            #expect(original.longValue == value)
            #expect(original.readULong() == value)
            original.longValue = UInt64.max
            #expect(original.readULong() == UInt64.max)
            #expect(copied.longValue == value)
            original.longValue = nil
            #expect(original.readULong() == nil)
        }
    }

    @Test func mixedUnsignedWidthsRemainIndependent() {
        let value = NullableUnsignedValues(byteValue: 128, shortValue: 32768, intValue: 2147483648, longValue: 18446744073709551615)
        #expect(value.readUByte() == 128)
        #expect(value.readUShort() == 32768)
        #expect(value.readUInt() == 2147483648)
        #expect(value.readULong() == 18446744073709551615)
        let copied = value.copyValues(byteValue: nil, shortValue: 65535, intValue: nil, longValue: 9223372036854775808)
        #expect(copied.readUByte() == nil)
        #expect(copied.readUShort() == 65535)
        #expect(copied.readUInt() == nil)
        #expect(copied.readULong() == 9223372036854775808)
        #expect(value.readUByte() == 128)
        #expect(value.readUShort() == 32768)
        #expect(value.readUInt() == 2147483648)
        #expect(value.readULong() == 18446744073709551615)
    }
}
