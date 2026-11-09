import ExportedKotlinPackages
@_spi(kotlinx$serialization$ExperimentalSerializationApi) import OrgJetbrainsKotlinxKotlinxSerializationCore
@_spi(spidefaults$ExperimentalSpiMarker) import ProtocolSpiDefaults

func verifySerializationProtocolMethodResolution(
    decoder: (any ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeDecoder)?,
    encoder: (any ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeEncoder)?,
    valueEncoder: (any ExportedKotlinPackages.kotlinx.serialization.encoding.Encoder)?,
    descriptor: (any ExportedKotlinPackages.kotlinx.serialization.descriptors.SerialDescriptor)?
) {
    if let decoder {
        _ = decoder.decodeSequentially()
    }
    if let encoder, let descriptor {
        _ = encoder.shouldEncodeElementDefault(descriptor: descriptor, index: 0)
    }
    if let valueEncoder {
        valueEncoder.encodeNotNullMark()
    }
}

let proto: any SpiDefaultProtocol = createSpiDefaultProtocol(baseOffset: 7)
precondition(proto.regularLabel() == "regular-label", "regularLabel mismatch")
precondition(proto.defaultSpiFlag() == true, "defaultSpiFlag should dispatch to Kotlin default")
precondition(proto.defaultSpiWithParams(label: "abcd", index: 4) == true, "defaultSpiWithParams positive mismatch")
precondition(proto.defaultSpiWithParams(label: "abcd", index: 3) == false, "defaultSpiWithParams negative mismatch")
proto.defaultSpiVoid()
precondition(proto.defaultSpiProp == "default-prop", "defaultSpiProp should dispatch to Kotlin default")

precondition(proto.abstractSpiFlag() == true, "abstractSpiFlag mismatch")
precondition(proto.abstractSpiWithParams(label: "hi", index: 5) == 14, "abstractSpiWithParams mismatch")
precondition(proto.abstractSpiProp == 70, "initial abstractSpiProp mismatch")
proto.abstractSpiVoid()
precondition(proto.abstractSpiProp == 71, "updated abstractSpiProp mismatch")

let impl: KotlinSpiImpl = createKotlinSpiImpl(baseOffset: 3)
precondition(impl.defaultSpiFlag() == true, "impl defaultSpiFlag mismatch")
precondition(impl.defaultSpiWithParams(label: "xyz", index: 3) == true, "impl defaultSpiWithParams mismatch")
impl.defaultSpiVoid()
precondition(impl.defaultSpiProp == "default-prop", "impl defaultSpiProp mismatch")
impl.abstractSpiVoid()
precondition(impl.observedVoidCalls() == 1, "impl observedVoidCalls mismatch")

verifySerializationProtocolMethodResolution(decoder: nil, encoder: nil, valueEncoder: nil, descriptor: nil)
print("PASS protocol-spi-defaults runtime dispatch and serialization protocol resolution")
