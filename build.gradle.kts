import org.gradle.plugins.signing.Sign
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.GenerateMavenPom

plugins {
    base
    `maven-publish`
    signing
}

group = "io.github.kotlinmania"
version = "2.4.20-km.4"
val releaseCoordinate = "${project.group}:swift-export-embeddable:${project.version}"

val distributionDirectory = providers.gradleProperty("distributionDirectory")
    .map { file(it) }.getOrElse(layout.buildDirectory.dir("distribution-a").get().asFile)

repositories {
    maven { url = uri(providers.gradleProperty("releaseRepository").getOrElse("build/resolution-repository")) }
    mavenCentral()
}

val releaseWorker = configurations.create("releaseWorker") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies {
    add(releaseWorker.name, releaseCoordinate)
}

tasks.register("verifyWorkerPom") {
    description = "Resolves the actual release POM and verifies the complete sixteen-component worker closure."
    doLast {
        val lock = JsonSlurper().parse(file("inputs.lock.json")) as Map<*, *>
        val rows = (lock["tools"] as List<*>).map { it as Map<*, *> }.filter { it["scope"] == "worker" }
        val expected = rows.associate { row ->
            val own = row["artifact"] == "swift-export-embeddable"
            val coordinate = if (own) releaseCoordinate else "${row["group"]}:${row["artifact"]}:${row["version"]}"
            val hash = if (own) {
                val release = JsonSlurper().parse(file("verification/release-artifacts.json")) as Map<*, *>
                release["main_component_sha256"].toString()
            } else row["sha256"].toString()
            coordinate to hash
        }
        val actual = releaseWorker.resolvedConfiguration.resolvedArtifacts.associate { artifact ->
            artifact.moduleVersion.id.toString() to MessageDigest.getInstance("SHA-256")
                .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
        }
        require(actual == expected) { "Resolved release worker dependency identities or hashes differ: $actual" }
        val output = layout.buildDirectory.file("resolved-worker.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(actual)) + "\n")
        logger.lifecycle("Verified all ${actual.size} actual release worker components")
    }
}

fun digest(algorithm: String, bytes: ByteArray): String =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

val distributionPrefix = "swift-export-embeddable-${project.version}"

val inputsLockFile = file("inputs.lock.json")
val frozenReceiptFile = file("verification/release-artifacts.json")
val releaseVersion = project.version.toString()

val retrieveDistribution = tasks.register("retrieveDistribution") {
    description = "Retrieves the published release artifacts from Maven Central and verifies them against the frozen fingerprints when no local distribution exists."
    onlyIf { !distributionDirectory.resolve("distribution.json").isFile }
    doLast {
        val frozen = JsonSlurper().parse(frozenReceiptFile) as Map<*, *>
        val files = frozen["files"] as Map<*, *>
        distributionDirectory.mkdirs()
        for ((rawName, rawHash) in files) {
            val name = rawName.toString()
            val connection = java.net.URI(
                "https://repo.maven.apache.org/maven2/io/github/kotlinmania/swift-export-embeddable/$releaseVersion/$name"
            ).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            val bytes = connection.getInputStream().use { it.readBytes() }
            require(digest("SHA-256", bytes) == rawHash.toString()) { "Retrieved artifact differs from the frozen fingerprint: $name" }
            distributionDirectory.resolve(name).writeBytes(bytes)
        }
        distributionDirectory.resolve("distribution.json").writeText(frozenReceiptFile.readText())
        logger.lifecycle("Retrieved and verified ${files.size} published release artifacts into ${distributionDirectory.absolutePath}")
    }
}

val verifyDistribution = tasks.register("verifyDistribution") {
    description = "Verifies the distribution receipt, every release byte and the POM identity against the frozen fingerprints."
    dependsOn(retrieveDistribution)
    doLast {
        val receiptFile = distributionDirectory.resolve("distribution.json")
        require(receiptFile.isFile) {
            "No verified distribution at ${distributionDirectory.absolutePath}. Produce one first " +
                "(python3 scripts/build.py --output build/reproduction-a --cache build/inputs, then " +
                "python3 scripts/distribution.py --build build/reproduction-a --output build/distribution-a), " +
                "or pass -PdistributionDirectory pointing at a verified distribution; see verification/PUBLISHING.md."
        }
        val lock = JsonSlurper().parse(inputsLockFile) as Map<*, *>
        val frozen = JsonSlurper().parse(frozenReceiptFile) as Map<*, *>
        val receipt = JsonSlurper().parse(receiptFile) as Map<*, *>
        require(receipt == frozen) { "Distribution differs from the frozen real-consumer candidate" }
        val files = frozen["files"] as Map<*, *>
        val names = files.keys.map { it.toString() }
        for (name in names) {
            val path = distributionDirectory.resolve(name)
            require(path.isFile) { "Missing distribution file: $name" }
            require(digest("SHA-256", path.readBytes()) == files[name].toString()) { "Checksum differs: $name" }
        }
        val pomName = names.single { it.endsWith(".pom") }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val project = factory.newDocumentBuilder().parse(distributionDirectory.resolve(pomName)).documentElement
        fun childText(parent: org.w3c.dom.Element, tag: String): String? {
            val children = parent.childNodes
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE && node.localName == tag) {
                    return node.textContent.trim()
                }
            }
            return null
        }
        val licenses = project.getElementsByTagName("licenses").item(0) as org.w3c.dom.Element
        val license = licenses.getElementsByTagName("license").item(0) as org.w3c.dom.Element
        require(childText(project, "groupId") == "io.github.kotlinmania") { "POM group differs" }
        require(childText(project, "version") == lock["version"].toString()) { "POM version differs" }
        require(childText(license, "name") == "Apache License, Version 2.0") { "POM license differs" }
        logger.lifecycle("Verified ${names.size} distribution files against the frozen release fingerprints")
    }
}

publishing {
    publications {
        create<MavenPublication>("swiftExportEmbeddable") {
            groupId = project.group.toString()
            artifactId = "swift-export-embeddable"
            version = project.version.toString()
            artifact(distributionDirectory.resolve("$distributionPrefix.jar"))
            artifact(distributionDirectory.resolve("$distributionPrefix-sources.jar")) { classifier = "sources" }
            artifact(distributionDirectory.resolve("$distributionPrefix-javadoc.jar")) { classifier = "javadoc" }
            artifact(distributionDirectory.resolve("$distributionPrefix-native-sdk-helper.zip")) {
                classifier = "native-sdk-helper"
                extension = "zip"
            }
            artifact(distributionDirectory.resolve("$distributionPrefix-sbom.json")) {
                classifier = "sbom"
                extension = "json"
            }
        }
    }
}

// The publication's generated POM is replaced with the frozen verified POM bytes
// so a local install is byte-identical to the audited release; the sha256 gate
// runs first so the copied bytes are the verified bytes.
tasks.withType<GenerateMavenPom>().configureEach {
    dependsOn(verifyDistribution)
    outputs.upToDateWhen { false }
    doLast {
        val pom = distributionDirectory.listFiles { candidate -> candidate.name.endsWith(".pom") }!!.single()
        destination.writeBytes(pom.readBytes())
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(verifyDistribution)
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    if (key != null) {
        useInMemoryPgpKeys(
            providers.environmentVariable("SIGNING_KEY_ID").orNull,
            key,
            providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull,
        )
    }
}

tasks.register<Sign>("signRelease") {
    description = "Signs verified release files using an existing key held in memory."
    val artifacts = fileTree(distributionDirectory) {
        include("*.jar", "*.pom", "*-native-sdk-helper.zip", "*-sbom.json")
    }
    sign(*artifacts.files.toTypedArray())
    doFirst {
        require(artifacts.files.size == 6) { "Expected exactly six verified release artifacts" }
        require(providers.environmentVariable("SIGNING_KEY").isPresent) { "An existing signing key is required" }
    }
}
