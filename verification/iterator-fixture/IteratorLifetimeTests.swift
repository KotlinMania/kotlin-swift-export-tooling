import Foundation
import ObjectiveC
import Testing
import ArrayIntegration
import KotlinRuntimeSupport

@Test func iteratorRetainsArrayAndReleasesItsNativeAndObjCRoots() {
    let key = UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1)
    defer { key.deallocate() }
    var iterator: KotlinStringArrayIterator?
    weak var weakWitness: NSObject?
    autoreleasepool {
        let created = makeTransferredArray().iterator()
        let witness = NSObject()
        weakWitness = witness
        objc_setAssociatedObject(created, key, witness, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        iterator = created
    }
    collectNativeGarbage()
    #expect(transferredArrayAlive())
    let value = autoreleasepool { iterator?.next() }
    #expect(value == "temporary")
    autoreleasepool { iterator = nil }
    collectNativeGarbage()
    #expect(!transferredArrayAlive())
    #expect(weakWitness == nil)
}
