plugins {
    id("fabric-loom") version ("1.11-SNAPSHOT")
}
dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    // minecraft("com.mojang:minecraft:1.20.1")   <- commented out, must be ignored
    modImplementation("net.fabricmc:fabric-loader:0.17.2")
}
