import Foundation
import Testing
import ByteArrayProof
import ExportedKotlinPackages

private final class InitializationProbe {
    let value: Int8 = 7
}

private final class WeakByteArray {
    weak var value: ExportedKotlinPackages.kotlin.ByteArray?

    init(_ value: ExportedKotlinPackages.kotlin.ByteArray) {
        self.value = value
    }
}

@inline(never)
private func exerciseRetainedArray() -> WeakByteArray {
    var value: ExportedKotlinPackages.kotlin.ByteArray? = ExportedKotlinPackages.kotlin.ByteArray(size: 1)
    let weak = WeakByteArray(value!)
    observeBytes(value: value!)
    collectByteArrayGarbage()
    #expect(observedBytesAlive())
    value?[0] = 19
    #expect(value?[0] == 19)
    value = nil
    return weak
}

@Suite(.serialized)
struct ByteArrayTests {
    @Test func sizeConstructorInitializesZerosAndEmptyArray() {
        let value = ExportedKotlinPackages.kotlin.ByteArray(size: 3)
        #expect(value.size == 3)
        #expect(value[0] == 0)
        #expect(value[1] == 0)
        #expect(value[2] == 0)
        #expect(ExportedKotlinPackages.kotlin.ByteArray(size: 0).size == 0)
    }

    @Test func initializerRunsOncePerIndexAndKeepsSignedBytes() {
        var calls: [Int32] = []
        let value = ExportedKotlinPackages.kotlin.ByteArray(size: 3, init: { index in
            calls.append(index)
            return Int8(index) - 1
        })
        #expect(calls == [0, 1, 2])
        #expect(value[0] == -1)
        #expect(value[1] == 0)
        #expect(value[2] == 1)
    }

    @Test func nativeAliasObservesSwiftMutation() {
        let value = ExportedKotlinPackages.kotlin.ByteArray(size: 2)
        let owner = ByteArrayOwner()
        owner.retain(value: value)
        #expect(owner.same(value: value))
        value[0] = -128
        value[1] = 127
        #expect(owner.read(index: 0) == -128)
        #expect(owner.read(index: 1) == 127)
        #expect(owner.current()?[0] == -128)
        owner.clear()
        #expect(value[1] == 127)
    }

    @Test func retainedSwiftArrayKeepsNativeRootAndThenReleasesIt() {
        let weak = autoreleasepool { exerciseRetainedArray() }
        for _ in 0..<5 { autoreleasepool { collectByteArrayGarbage() } }
        #expect(weak.value == nil)
        #expect(!observedBytesAlive())
    }

    @Test func arrayDoesNotRetainCompletedInitializerCapture() {
        weak var observed: InitializationProbe?
        var value: ExportedKotlinPackages.kotlin.ByteArray?
        autoreleasepool {
            let probe = InitializationProbe()
            observed = probe
            value = ExportedKotlinPackages.kotlin.ByteArray(size: 2, init: { [probe] _ in probe.value })
        }
        for _ in 0..<5 { autoreleasepool { collectByteArrayGarbage() } }
        #expect(observed == nil)
        #expect(value?[0] == 7)
        #expect(value?[1] == 7)
        value = nil
        collectByteArrayGarbage()
    }

    @Test func temporaryConstructedArraySurvivesNativeCall() {
        #expect(sumBytes(value: ExportedKotlinPackages.kotlin.ByteArray(size: 3, init: { _ in -2 })) == -6)
    }
}
