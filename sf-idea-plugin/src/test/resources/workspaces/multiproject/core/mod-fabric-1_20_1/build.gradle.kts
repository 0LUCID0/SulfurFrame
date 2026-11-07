plugins { id("fabric-loom") version "1.9.+" }
val mcVersion = rootProject.property("mc_1_20_1_version").toString()
val loaderVersion = rootProject.property("mc_1_20_1_fabric_loader").toString()
dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
}
