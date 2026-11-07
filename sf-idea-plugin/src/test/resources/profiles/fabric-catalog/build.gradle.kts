plugins { alias(libs.plugins.loom) ; id("fabric-loom") }
dependencies { minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}") }
