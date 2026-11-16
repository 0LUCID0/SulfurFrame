plugins { id("fabric-loom") version "1.9.+" }
dependencies {
    minecraft("com.mojang:minecraft:${rootProject.property("mc_version")}")
    modImplementation("net.fabricmc:fabric-loader:${rootProject.property("fabric_loader")}")
}
// the client with every module's mod (BuildPali's layout): the default choice, before each module's own runClient
loom {
    runConfigs.create("allModsClient") { inherit(runConfigs.getByName("client")) }
}
