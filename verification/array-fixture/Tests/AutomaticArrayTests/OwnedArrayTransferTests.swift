import Foundation
import Testing
import ArrayIntegration

@Test func ownedArrayTransfersReleaseAfterDirectOptionalAndCallbackCalls() {
    autoreleasepool {
        let accepted = StringArrays().accept(values: makeTransferredArray())
        #expect(!accepted)
    }
    collectNativeGarbage()
    #expect(!transferredArrayAlive())
    autoreleasepool {
        #expect(acceptNullableTransferredArray(values: nil))
        #expect(acceptNullableTransferredArray(values: makeTransferredArray()))
    }
    collectNativeGarbage()
    #expect(!transferredArrayAlive())
    autoreleasepool {
        let accepted = acceptTransferredArrayCallback(callback: { makeTransferredArray() })
        #expect(accepted)
    }
    collectNativeGarbage()
    #expect(!transferredArrayAlive())
}
