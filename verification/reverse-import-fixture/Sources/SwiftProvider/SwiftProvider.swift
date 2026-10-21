import ReverseImportProof

public final class NativeProviderFailure: SwiftBase, ProviderFailure {
    public let text: String

    public init(text: String) {
        self.text = text
        super.init()
    }

    public func message() -> String { text }
}

public final class NativeProvider: SwiftBase, Provider {
    public enum Mode {
        case echo
        case successfulNull
        case failure
    }

    public let mode: Mode
    public let originalFailure: NativeProviderFailure?
    public private(set) var calls = 0

    public init(mode: Mode, failure: NativeProviderFailure? = nil) {
        precondition((mode == .failure) == (failure != nil))
        self.mode = mode
        self.originalFailure = failure
        super.init()
    }

    public func deliver(value: Payload, callback: any PayloadCallback) -> Delivery {
        calls += 1
        switch mode {
        case .echo:
            return Delivery(successful: true, value: callback.apply(value: value), failure: nil)
        case .successfulNull:
            return Delivery(successful: true, value: nil, failure: nil)
        case .failure:
            return Delivery(successful: false, value: nil, failure: originalFailure)
        }
    }
}

public final class NativePayloadCallback: SwiftBase, PayloadCallback {
    public private(set) var calls = 0

    public func apply(value: Payload) -> Payload {
        calls += 1
        return echoPayload(value: value)
    }
}
