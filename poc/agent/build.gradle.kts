// sf-agent.jar: SfAgent + SfHooks + the options sync core, Java 8 bytecode (runs on Java 8..25), with a nested
// sf-impl.jar (the transformer + a private copy of ASM) that SfAgent extracts and loads in its own class loader.
// Same layout as build.sh.
plugins { java }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories { mavenCentral() }

val asm = configurations.create("asm") { isTransitive = false }
dependencies {
    asm("org.ow2.asm:asm:9.11.1")           // keep it the latest
    asm("org.ow2.asm:asm-commons:9.11.1")   // ClassRemapper (runtime registration's shadow copy); same version
}

sourceSets {
    main { java.setSrcDirs(listOf("src/agent", "../profile/src")) }
    create("impl") {
        java.setSrcDirs(listOf("src/impl"))
        compileClasspath += asm
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:-options"))
}

val implJar = tasks.register<Jar>("implJar") {
    archiveFileName = "sf-impl.jar"
    destinationDirectory = layout.buildDirectory.dir("impl")
    from(sourceSets["impl"].output)
    from({ asm.map { zipTree(it) } }) { exclude("META-INF/**", "module-info.class") }
}

tasks.jar {
    archiveFileName = "sf-agent.jar"
    from(implJar)
    manifest {
        attributes(
            "Premain-Class" to "dev.sulfurframe.agent.SfAgent",
            "Agent-Class" to "dev.sulfurframe.agent.SfAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
