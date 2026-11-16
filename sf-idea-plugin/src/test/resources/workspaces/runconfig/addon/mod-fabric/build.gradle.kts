plugins { id("fabric-loom") version "1.9.+" }
dependencies {
    minecraft("com.mojang:minecraft:${rootProject.property("mc_version")}")
    modImplementation("net.fabricmc:fabric-loader:${rootProject.property("fabric_loader")}")
}
