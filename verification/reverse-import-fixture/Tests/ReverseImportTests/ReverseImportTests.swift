import Foundation
import Testing
import ReverseImportProof
import SwiftProvider

@Suite(.serialized)
struct ReverseImportTests {
    @Test func explicitExportNamesPreservePropertiesAndCanonicalMethods() {
        let color = ColorChannels(red: 17, green: 34, blue: 51, paletteIndex: 68)
        #expect(color.rValue == 17)
        #expect(color.r() == color.rValue)
        #expect(color.gValue == 34)
        #expect(color.g() == color.gValue)
        #expect(color.bValue == 51)
        #expect(color.b() == color.bValue)
        #expect(color.indexValue == 68)
        #expect(color.index() == color.indexValue)
    }

    @Test func swiftProviderCallsThroughKotlinAndBackWithTypedIdentity() {
        let provider = NativeProvider(mode: .echo)
        let callback = NativePayloadCallback()
        let value = Payload(text: "typed identity")
        let result = createDispatcher(provider: provider).dispatch(value: value, callback: callback)
        #expect(result.successful)
        #expect(result.value === value)
        #expect(result.failure == nil)
        #expect(provider.calls == 1)
        #expect(callback.calls == 1)
    }

    @Test func swiftSuccessfulNullIsNotFailure() {
        let provider = NativeProvider(mode: .successfulNull)
        let result = createDispatcher(provider: provider).dispatch(
            value: Payload(text: "null"), callback: NativePayloadCallback())
        #expect(result.successful)
        #expect(result.value == nil)
        #expect(result.failure == nil)
        #expect(provider.calls == 1)
    }

    @Test func independentSwiftFailureKeepsOriginalIdentity() throws {
        let failure = NativeProviderFailure(text: "original Swift object")
        let provider = NativeProvider(mode: .failure, failure: failure)
        let result = createDispatcher(provider: provider).dispatch(
            value: Payload(text: "failure"), callback: NativePayloadCallback())
        #expect(!result.successful)
        #expect(result.value == nil)
        let returned = try #require(result.failure as? NativeProviderFailure)
        #expect(returned === failure)
        #expect(returned.message() == "original Swift object")
    }

    @Test func kotlinOwnerRetainsProviderBeyondSwiftLocalScope() {
        weak var observed: NativeProvider?
        var dispatcher: Dispatcher?
        autoreleasepool {
            let provider = NativeProvider(mode: .echo)
            observed = provider
            observeProvider(provider: provider)
            dispatcher = createDispatcher(provider: provider)
        }
        collectProviderGarbage()
        #expect(observed != nil)
        #expect(observedProviderAlive())
        autoreleasepool {
            let value = Payload(text: "retained")
            let result = dispatcher?.dispatch(value: value, callback: NativePayloadCallback())
            #expect(result?.value === value)
        }
        dispatcher = nil
        for _ in 0..<5 { autoreleasepool { collectProviderGarbage() } }
        #expect(observed == nil)
        #expect(!observedProviderAlive())
    }
}
