# Checked-in Swift provider contract proof

This fixture tests the interface plus open-base reverse-import pattern documented for Kotlin 2.4.20. It is an independent verification project, not a replacement for a product's generic result API.

The dependency direction is Kotlin contract/exported module, then authored Swift provider module, then consumer composition. The Kotlin Apple `actual` factory receives the provider; Swift source does not satisfy a Kotlin `actual` declaration. Runtime calls can travel in both directions without cyclic module imports.

`Provider`, `PayloadCallback`, `Payload`, `ProviderFailure` and `Delivery` use concrete payload operations. The independent Swift implementation inherits the exported `SwiftBase` and implements the generated interface. A dispatcher stores that implementation and calls it from Kotlin. The Swift implementation invokes a Swift callback which calls the exported Kotlin identity operation. Tests distinguish a successful null payload from failure and check the original Swift failure object by reference identity. That object implements the application failure protocol, not `Swift.Error`; no Swift catch or typed-throws identity claim follows.

The lifecycle case exits the original Swift provider scope, collects Native garbage, calls the provider through its retained Kotlin owner, releases that owner, and checks both Swift and Native weak references. The provider, callback and failure are authored in a separate Swift target using language mode 6. Generated dependency targets use mode 5. Swift and generated Native compilation keep warnings as errors.

Run Debug and Release in separate output directories with `scripts/reverse_import_consumer.py`. Each invocation checks four named Swift cases exactly once and records actual frontend modes, Native archive hash, complete worker inputs and the unchanged SDK inventory. The optional host-test gate checks three named common-contract cases in each of JVM, macOS Native, JS Node, WasmJS Node and WASI Node XML reports. These Kotlin test runtimes are not independent JavaScript/TypeScript export consumers. WASI has no implied JavaScript export contract.

The fixture neither recovers erased generic parameters nor proves arbitrary abstract-class overrides, actor isolation, asynchronous dispatch, cancellation, or an unconstrained error type compatible with `Swift.Result`. Genuine generic relationships require their own representation and compiler proof. No product API or published tooling artifact is changed by this verification project.

Primary sources:

- https://kotlinlang.org/docs/whatsnew2420.html#cross-language-inheritance-in-swift-export
- https://kotlinlang.org/docs/native-swift-export.html#cross-language-inheritance
