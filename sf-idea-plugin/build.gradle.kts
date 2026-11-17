import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.sulfurframe"
version = providers.gradleProperty("pluginVersion").get()

// FFM (java.lang.foreign) without preview flags: the IDE runs on JBR 25
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// an installed IDE instead of a downloaded one: -PideaHome=... or IDEA_HOME
val ideaHome: String? = providers.gradleProperty("ideaHome").orElse(providers.environmentVariable("IDEA_HOME")).orNull

val agent = configurations.create("agent") { isCanBeConsumed = false }

dependencies {
    intellijPlatform {
        if (ideaHome != null) local(ideaHome) else intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
    }
    agent(project(":agent"))
}

sourceSets {
    main { java.setSrcDirs(listOf("src/main/java")) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:-restricted", "-nowarn"))
}

// the game-side agent rides inside the plugin jar; SulfurPanel extracts /sf-agent.jar at launch
tasks.processResources {
    from(agent) { rename { "sf-agent.jar" } }
}

intellijPlatform {
    projectName = "SulfurFrame"   // plugin folder and zip name (SulfurFrame/lib/...), as with build.sh
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <p><b>0.1.2</b></p>
            <ul>
              <li>No deprecated or experimental IntelliJ API use: the editor tab opens through the stable navigation API.</li>
              <li>Self-test checks the world you point it at.</li>
            </ul>
            <p><b>0.1.1</b></p>
            <ul>
              <li>SulfurFrame keeps its own log of swaps, reloads and restarts in ~/.sulfurframe/logs (14 days, local
                  only, nothing is sent), so you can look back at your sessions.</li>
              <li>Self-test game runs are silent, and your options.txt volumes are left alone.</li>
              <li>The description now shows how much waiting it saves, measured on real projects.</li>
            </ul>
            <p><b>0.1.0</b>: first public release.</p>
            <ul>
              <li>Runs a Fabric 1.21.1 dev client inside the IDE (Windows and Linux/X11), throttled while you type.</li>
              <li>Hotswap on save, including files changed outside the IDE; restart into the same world.</li>
              <li>F11 fullscreen leaves the IDE and comes back embedded.</li>
            </ul>
        """.trimIndent()
    }
    // optional: author signing (Marketplace also signs uploads). Values come from the environment only.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            if (ideaHome != null) local(file(ideaHome))
            else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
    }
}

// The fixture self-tests are plain main() programs (no JUnit): run them as part of `check`.
tasks.test { enabled = false }
val selfTests = listOf(
    "ProfileSelfTest" to "src/test/resources/profiles",
    "WorkspaceSelfTest" to "src/test/resources/workspaces",
).map { (name, fixtures) ->
    tasks.register<JavaExec>("run$name") {
        group = "verification"
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "dev.sulfurframe.idea.profile.$name"
        args(file(fixtures).absolutePath)
    }
}
tasks.check { dependsOn(selfTests) }
