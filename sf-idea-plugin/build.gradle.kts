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
            <p><b>0.3.0</b>: Fabric 1.21.1, everything a running JVM allows (a long-lived base while other versions and
            loaders come next).</p>
            <ul>
              <li>Startup code in the running game: new blocks, items and entries of every registry, a changed
                  <code>onInitialize</code>/<code>onInitializeClient</code> (listeners, commands, key bindings, renderers,
                  screens, payloads, model loading plugins replaced, never added twice), new enum constants, new mixins,
                  changed settings of existing blocks and items, hierarchy changes between the project's own types. Loot
                  tables, commands and tags reload the server's data; world data reopens the world.</li>
              <li>Access widener entries added while the game runs apply at once, and can be taken out again.</li>
              <li>Safety: the world is snapshotted before init code runs again, with a restart into it if that fails; a
                  class file that a Gradle build is rewriting is waited for instead of crashing the game; callers of a
                  changed method or field are compiled too, and the swap is refused if they break.</li>
              <li>Coding agents (Claude Code, Codex, ...): the <code>sf</code> command in <code>~/.sulfurframe/bin</code>
                  checks changes in the running game from a terminal (<code>sf wait</code>, <code>cmd</code>,
                  <code>log</code>, <code>shot</code>, <code>restart</code>...), <code>sf run</code> starts the game
                  without IntelliJ (Java 25 and an IntelliJ installation needed), <code>sf feedback</code> and
                  <code>sf relay</code> collect reports for the developers. Local files only; it can be turned off in
                  Settings.</li>
              <li>Tested on Yarn and Mojang-mapped projects and on a large multi-project mod. Run configurations are made
                  for every client found; Java 21+ games no longer log a warning on every agent attach.</li>
              <li>Embedded game on Windows: releasing the mouse (menus, overlays shown while a key is held) no longer fakes
                  a focus loss, so held keys stay held, and the cursor stays in the middle instead of jumping back to where
                  you clicked into the game.</li>
              <li>Still a restart: removing a registered entry, an enum constant, a mixin or an access widener entry that
                  was there at startup; a JDK or Minecraft type in a class's hierarchy; <code>fabric.mod.json</code> and
                  build files (restarted without Gradle when nothing else changed).</li>
            </ul>
            <p><b>0.2.1</b></p>
            <ul>
              <li>The first quick-play launch in a new run folder joins the world: the options.txt SulfurFrame prepares
                  now carries the game's <code>version:</code>. Without it Minecraft read the file as a very old one, reset
                  every option and showed the first-launch accessibility screen, so "Restart into the Same World" landed
                  on the title screen. Files left without it by 0.2.0 are repaired on the next start.</li>
            </ul>
            <p><b>0.2.0</b></p>
            <ul>
              <li>Restarts skip Gradle while the build, JBR, mod metadata and access wideners are unchanged: saved code and
                  resources are compiled and copied first, then the game starts straight from its last command line.</li>
              <li>Starts in the background on Windows and Linux (X11): launching the game no longer takes the keyboard focus
                  or the mouse cursor from the window you are in.</li>
              <li>One-click JetBrains Runtime download when no matching JBR is found in <code>~/.jdks</code>, so added or
                  removed methods and fields can hotswap live too.</li>
              <li>Files edited outside the IDE refresh in open editor tabs as soon as they are hotswapped.</li>
              <li>Builds that choose their own Gradle JVM (a daemon toolchain or <code>org.gradle.java.home</code>) start
                  even when <code>JAVA_HOME</code> is Java 8: <code>gradlew</code> itself gets a Java 17+ JDK.</li>
              <li>Multi-project and Gradle 9 parallel builds: each subproject warms its compile classpaths under its own
                  lock, custom <code>runDir</code> settings and all mod IDs across multi-module runs are picked up,
                  subproject build script edits refresh the class path cache, and Java 8 source sets keep higher-Java
                  outputs off their compile class path.</li>
            </ul>
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
    "profile.ProfileSelfTest" to "src/test/resources/profiles",
    "profile.WorkspaceSelfTest" to "src/test/resources/workspaces",
    "OptionsFileSelfTest" to null,
    "MetadataTemplateSelfTest" to null,
    "StartupCodeSelfTest" to null,
).map { (name, fixtures) ->
    tasks.register<JavaExec>("run${name.substringAfterLast('.')}") {
        group = "verification"
        classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath   // + the IDE's jars (ASM, Gson)
        mainClass = "dev.sulfurframe.idea.$name"
        if (fixtures != null) args(file(fixtures).absolutePath)
    }
}
tasks.check { dependsOn(selfTests) }
