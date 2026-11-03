plugins { java }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation(fileTree(gradle.gradleHomeDir!!.resolve("lib")) {
        include("commons-compress-*.jar", "commons-io-*.jar", "commons-codec-*.jar", "commons-lang3-*.jar")
    })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.register("exportProducerClasspath") {
    dependsOn(tasks.classes)
    val destination = layout.buildDirectory.file("producer-classpath.txt")
    outputs.file(destination)
    doLast {
        destination.get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath)
    }
}
