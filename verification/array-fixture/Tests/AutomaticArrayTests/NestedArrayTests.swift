import Foundation
import Testing
import ArrayIntegration
import KotlinRuntimeSupport

@Test func nestedListsPreserveMutationAndAlias() {
    let arrays = NestedStringArrays()
    let outer = arrays.lists()
    #expect(outer.count == 3)
    #expect(outer[1].size == 0)
    outer[0].set(index: 0, value: "nested changed")
    #expect(outer[2].get(index: 0) == "nested changed")
    #expect(arrays.original(index: 0) == "nested changed")
    #expect(arrays.acceptLists(values: outer))
}

@Test func nullableElementsDistinguishNullAndEmpty() {
    let arrays = NestedStringArrays()
    let outer = arrays.nullableLists()
    #expect(outer.count == 3)
    #expect(outer[0] == nil)
    #expect(outer[1]?.size == 0)
    #expect(outer[2]?.get(index: 0) == "left")
    #expect(arrays.acceptNullableLists(values: outer))
}

@Test func optionalNestedListsRoundTrip() {
    let arrays = NestedStringArrays()
    let outer = arrays.deepLists()
    #expect(outer[0] == nil)
    #expect(outer[1]?[0].get(index: 1) == "right")
    #expect(arrays.acceptDeepLists(values: outer))
}

@Test func swiftConstructedNestedListsRoundTrip() {
    let arrays = NestedStringArrays()
    #expect(arrays.acceptLists(values: [arrays.first(), arrays.empty(), arrays.first()]))
    #expect(arrays.acceptNullableLists(values: [nil, arrays.empty(), arrays.first()]))
    #expect(arrays.acceptDeepLists(values: [nil, [arrays.first()]]))
}

@Test func nestedSetsPreserveArrayIdentity() {
    let arrays = NestedStringArrays()
    let values = arrays.sets()
    #expect(values.count == 2)
    #expect(values.contains(arrays.first()))
    #expect(!values.contains(NestedStringArrays().first()))
    arrays.first().set(index: 0, value: "set mutation")
    #expect(values.contains(arrays.first()))
    #expect(arrays.acceptSets(values: values))
    #expect(arrays.acceptSets(values: Set([arrays.first(), arrays.empty(), arrays.first()])))
}

@Test func nestedMapValuesPreserveMutation() throws {
    let arrays = NestedStringArrays()
    let values = arrays.maps()
    let inner = try #require(values["value"])
    inner.set(index: 1, value: "map changed")
    #expect(arrays.original(index: 1) == "map changed")
    #expect(arrays.acceptMaps(values: values))
    #expect(arrays.acceptMaps(values: ["value": arrays.first(), "empty": arrays.empty()]))
}

@Test func nestedArrayKeysPreserveIdentity() {
    let arrays = NestedStringArrays()
    let values = arrays.arrayKeys()
    #expect(values[arrays.first()] == "value")
    #expect(values[NestedStringArrays().first()] == nil)
    arrays.first().set(index: 0, value: "key mutation")
    #expect(values[arrays.first()] == "value")
    #expect(arrays.acceptArrayKeys(values: values))
    #expect(arrays.acceptArrayKeys(values: [arrays.first(): "value", arrays.empty(): "empty"]))
}

@Test func retainedInnerSurvivesOuterReleaseAndCollection() {
    var inner: KotlinStringArray?
    autoreleasepool {
        let arrays = NestedStringArrays()
        inner = arrays.lists()[0]
    }
    collectNativeGarbage()
    #expect(inner?.get(index: 0) == "left")
    inner?.set(index: 1, value: "still retained")
    collectNativeGarbage()
    #expect(inner?.get(index: 1) == "still retained")
    inner = nil
    collectNativeGarbage()
}

@Test func nativeStringListsKeepConcreteSwiftStrings() {
    let values: [Swift.String] = NestedStringArrays().strings()
    #expect(values == ["first", "last"])
}

@Test func variadicArrayInputsPreserveTypedIdentity() {
    let arrays = NestedStringArrays()
    #expect(arrays.acceptVariadic(values: arrays.first(), arrays.empty()))
    #expect(arrays.acceptVariadicLists(values: [arrays.first()], [arrays.empty()]))
}

@Test func synchronousCollectionCallbackRoundTrips() {
    let arrays = NestedStringArrays()
    #expect(arrays.callbackLists(callback: { values in
        values[0].set(index: 0, value: "callback changed")
        return values
    }))
    #expect(arrays.original(index: 0) == "callback changed")
}

private final class SwiftArraySink: ArraySink {
    override func accept(values: KotlinStringArray...) -> Swift.Bool {
        if values.count != 2 { return false }
        #expect(values[0].size == 2)
        values[0].set(index: 1, value: "reverse vararg changed")
        return values[1].size == 0
    }
}

@Test func nativeVirtualVariadicCallbackUsesTypedArrays() {
    let arrays = NestedStringArrays()
    let sink = SwiftArraySink()
    #expect(dispatchArraySink(sink: sink, values: [arrays.first(), arrays.empty()]))
    #expect(arrays.original(index: 1) == "reverse vararg changed")
}

@Test func nullableCollectionCallbackPreservesNilAndPayload() {
    let arrays = NestedStringArrays()
    #expect(arrays.callbackNullableLists(callback: { values in
        guard let present = values else { return nil }
        present[0].set(index: 0, value: "nullable callback changed")
        return present
    }))
    #expect(arrays.original(index: 0) == "nullable callback changed")
}

@Test func mixedObjectKeyCallbackPreservesTypedIdentity() {
    let arrays = NestedStringArrays()
    #expect(arrays.objectKeys()[arrays.key()]?.get(index: 0) == "left")
    #expect(arrays.callbackObjectKeys(callback: { values in
        values[arrays.key()]?.set(index: 0, value: "object key changed")
        return values
    }))
    #expect(arrays.original(index: 0) == "object key changed")
}

@Test func mixedListKeyCallbackPreservesTypedPayload() {
    let arrays = NestedStringArrays()
    #expect(arrays.listKeys()[["key"]]?.get(index: 0) == "left")
    #expect(arrays.callbackListKeys(callback: { values in
        values[["key"]]?.set(index: 0, value: "list key changed")
        return values
    }))
    #expect(arrays.original(index: 0) == "list key changed")
}

@Test func carrierKeyCallbackKeepsConcreteStringListValues() {
    let arrays = NestedStringArrays()
    #expect(arrays.carrierKeys()[arrays.first()] == ["one", "two"])
    let accepted = arrays.callbackCarrierKeys(callback: { values in
        #expect(values[arrays.first()] == ["one", "two"])
        return values
    })
    #expect(accepted)
}

@Test func nestedSetCallbackPreservesArrayAliases() {
    let arrays = NestedStringArrays()
    #expect(arrays.nestedSets().contains([arrays.first()]))
    #expect(arrays.callbackNestedSets(callback: { values in
        for inner in values where inner[0].size == 2 {
            inner[0].set(index: 0, value: "nested set changed")
        }
        return values
    }))
    #expect(arrays.original(index: 0) == "nested set changed")
}

@Test func mixedObjectValueCallbackPreservesReferent() {
    let arrays = NestedStringArrays()
    #expect(arrays.objectValues()[arrays.first()] === arrays.key())
    let accepted = arrays.callbackObjectValues(callback: { values in
        #expect(values[arrays.first()] === arrays.key())
        return values
    })
    #expect(accepted)
}

@Test func boxedArrayRetainsAndReleasesNativeReferent() {
    var retained: KotlinStringArray?
    autoreleasepool {
        retained = makeEphemeralArrayList()[0]
        retained?.set(index: 0, value: "retained temporary")
    }
    collectNativeGarbage()
    let retainedValue = retained?.get(index: 0)
    #expect(retainedValue == "retained temporary")
    #expect(ephemeralArrayAlive())
    autoreleasepool { retained = nil }
    collectNativeGarbage()
    #expect(!ephemeralArrayAlive())
}
