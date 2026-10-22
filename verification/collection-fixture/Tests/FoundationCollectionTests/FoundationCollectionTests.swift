import Foundation
import Testing
import FoundationCollectionProof

@inline(never)
private func temporaryHeaders() -> [String: [String]] {
    autoreleasepool {
        HeaderStore(initialHeaders: ["retained": ["value", "α"]]).headers
    }
}

@Suite
struct FoundationCollectionTests {
    @Test func readsKotlinHeadersWithoutLosingOrderOrUnicode() {
        let headers = kotlinHeaders()
        #expect(headers["X-Trace"] == ["one", "two", "two"])
        #expect(headers["Unicode"] == ["α", "東京"])
        #expect(headers.count == 3)
    }

    @Test func roundTripsSwiftHeaderDictionary() {
        let input = ["X-Custom": ["first", "second"], "Unicode": ["é", "東京"]]
        #expect(echoHeaders(headers: input) == input)
    }

    @Test func distinguishesEmptyValuesFromMissingKeys() {
        let headers = kotlinHeaders()
        #expect(headers["Empty"] == [])
        #expect(headers["Missing"] == nil)
        #expect(echoHeaders(headers: [:]).isEmpty)
    }

    @Test func optionalDictionaryDistinguishesNilFromEmpty() {
        #expect(echoOptionalHeaders(headers: nil) == nil)
        #expect(echoOptionalHeaders(headers: [:]) == [:])
        #expect(echoOptionalHeaders(headers: ["x": []]) == ["x": []])
    }

    @Test func recursivelyRoundTripsNestedHeaderDictionaries() {
        let input: [String: [String: [String]]] = [
            "request": ["Accept": ["application/json", "text/plain"]],
            "response": ["Empty": []],
            "absent": [:],
        ]
        #expect(echoNestedHeaders(headers: input) == input)
    }

    @Test func nestedHeaderListsKeepConcreteValues() {
        let input: [[String: [String]]] = [["x": ["a", "b"]], [:], ["empty": []]]
        #expect(echoHeaderLists(headers: input) == input)
    }

    @Test func mutationKeepsDictionaryValueSemantics() {
        let store = HeaderStore(initialHeaders: ["x": ["original"]])
        var snapshot = store.headers
        snapshot["x"]?.append("Swift")
        #expect(store.headers["x"] == ["original"])
        store.replaceHeader(name: "x", values: ["Native", "replacement"])
        #expect(store.headers["x"] == ["Native", "replacement"])
        #expect(snapshot["x"] == ["original", "Swift"])
    }

    @Test func temporaryOwnerValuesSurviveAutoreleasePool() {
        let headers = temporaryHeaders()
        #expect(headers == ["retained": ["value", "α"]])
    }

    @Test func synchronousDictionaryCallbackKeepsTypedValues() {
        let result = callWithHeaders(headers: ["x": ["one"]], transform: { input in
            var output = input
            output["callback"] = ["two", "three"]
            return output
        })
        #expect(result == ["x": ["one"], "callback": ["two", "three"]])
    }

    @Test func nullableDictionaryCallbackKeepsNilAndValuesDistinct() {
        #expect(callWithOptionalHeaders(headers: nil, transform: { $0 }) == nil)
        #expect(callWithOptionalHeaders(headers: [:], transform: { $0 }) == [:])
        let result = callWithOptionalHeaders(headers: ["x": ["one"]], transform: { input in
            input.map { $0.merging(["callback": []], uniquingKeysWith: { _, replacement in replacement }) }
        })
        #expect(result == ["x": ["one"], "callback": []])
    }

    @Test func invokesKotlinReturnedDictionaryClosure() {
        let transform = kotlinHeaderTransform()
        #expect(transform(["x": ["one"]]) == ["x": ["one"], "Native": ["added"]])
    }
}
