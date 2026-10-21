import Foundation
import ObjectiveC
import Testing
import ArrayIntegration
import KotlinRuntimeSupport

@Test func carrierObjectAndAssociatedWitnessDeallocate() {
    let key = UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1)
    defer { key.deallocate() }
    weak var weakView: KotlinStringArray?
    weak var weakWitness: NSObject?
    autoreleasepool {
        let view = NestedStringArrays().lists()[0]
        let witness = NSObject()
        weakView = view
        weakWitness = witness
        objc_setAssociatedObject(view, key, witness, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        #expect(view.get(index: 0) == "left")
    }
    #expect(weakView == nil)
    #expect(weakWitness == nil)
}
