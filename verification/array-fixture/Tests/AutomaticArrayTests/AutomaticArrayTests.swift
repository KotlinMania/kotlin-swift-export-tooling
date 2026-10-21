import Testing
import ArrayIntegration

@Test func readsGeneratedStringArray() {
    let arrays = StringArrays()
    #expect(arrays.first().size == 2)
    let value: Swift.String = arrays.first().get(index: 0)
    #expect(value == "first")
}

@Test func mutatesOriginalArray() {
    let arrays = StringArrays()
    arrays.first().set(index: 0, value: "changed")
    #expect(arrays.alias().get(index: 0) == "changed")
    #expect(arrays.original(index: 0) == "changed")
}

@Test func iteratorObservesAliasedMutation() {
    let arrays = StringArrays()
    let iterator = arrays.first().iterator()
    arrays.alias().set(index: 0, value: "updated")
    #expect(iterator.hasNext())
    #expect(iterator.next() == "updated")
    #expect(iterator.hasNext())
    #expect(iterator.next() == "last")
    #expect(!iterator.hasNext())
}

@Test func passesTypedArrayBackToKotlin() {
    let arrays = StringArrays()
    #expect(arrays.accept(values: arrays.first()))
    #expect(!arrays.accept(values: StringArrays().first()))
}

@Test func preservesOptionalArray() {
    let arrays = StringArrays()
    #expect(arrays.optional(present: false) == nil)
    #expect(arrays.optional(present: true)?.get(index: 1) == "last")
}

@Test func readsRealThrowableStackTrace() {
    let trace = stackTraceValues()
    #expect(trace.size > 0)
    let first: Swift.String = trace.get(index: 0)
    #expect(!first.isEmpty)
}
