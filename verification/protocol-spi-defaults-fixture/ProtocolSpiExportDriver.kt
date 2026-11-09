import java.nio.file.Path
import kotlin.system.exitProcess
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.library.metadata.KlibInputModule
import org.jetbrains.kotlin.swiftexport.standalone.ErrorTypeStrategy
import org.jetbrains.kotlin.swiftexport.standalone.UnsupportedDeclarationReporterKind
import org.jetbrains.kotlin.swiftexport.standalone.config.SwiftExportConfig
import org.jetbrains.kotlin.swiftexport.standalone.config.SwiftModuleConfig
import org.jetbrains.kotlin.swiftexport.standalone.createDummyLogger
import org.jetbrains.kotlin.swiftexport.standalone.runSwiftExport

fun main(args: Array<String>) {
    try {
        require(args.size == 4) { "Expected <fixtureKlib> <serializationKlib> <nativeHome> <outputDir>" }
        val fixtureKlib = Path.of(args[0]).toAbsolutePath().normalize()
        val serializationKlib = Path.of(args[1]).toAbsolutePath().normalize()
        val nativeHome = Path.of(args[2]).toAbsolutePath().normalize()
        val outputDir = Path.of(args[3]).toAbsolutePath().normalize()

        val modules = linkedSetOf(
            KlibInputModule(
                name = "ProtocolSpiDefaults",
                path = fixtureKlib,
                config = SwiftModuleConfig(
                    bridgeModuleName = "SharedBridge",
                    rootPackage = "spidefaults",
                    unsupportedDeclarationReporterKind = UnsupportedDeclarationReporterKind.Silent,
                    experimentalFeatures = emptyMap(),
                    shouldBeFullyExported = true,
                ),
            ),
            KlibInputModule(
                name = "OrgJetbrainsKotlinxKotlinxSerializationCore",
                path = serializationKlib,
                config = SwiftModuleConfig(
                    bridgeModuleName = "SharedBridge",
                    rootPackage = null,
                    unsupportedDeclarationReporterKind = UnsupportedDeclarationReporterKind.Silent,
                    experimentalFeatures = emptyMap(),
                    shouldBeFullyExported = false,
                ),
            ),
        )

        val config = SwiftExportConfig(
            outputPath = outputDir,
            stableDeclarationsOrder = true,
            renderDocComments = false,
            enableCoroutinesSupport = false,
            distribution = Distribution(nativeHome.toString(), false, null, emptyMap(), null),
            konanTarget = KonanTarget.MACOS_ARM64,
            errorTypeStrategy = ErrorTypeStrategy.SpecialType,
            unsupportedTypeStrategy = ErrorTypeStrategy.SpecialType,
            logger = createDummyLogger(),
        )

        val exported = runSwiftExport(modules, config).getOrThrow()
        println("EXPORTED_MODULES=" + exported.map { it.name }.sorted().joinToString(","))
        exitProcess(0)
    } catch (t: Throwable) {
        t.printStackTrace()
        exitProcess(1)
    }
}
