plugins {
    id("java-library")
    id("application")
}

group = "com.melon.ShadowForge"
version = "0.0.1"

val lwjglNatives = project.properties["lwjglNatives"] as? String ?: "windows"
val lwjglVersion = "3.4.1"
val imguiVersion = "1.92.0"
val foolsEnginePath = project.properties["foolsEngineJar"] as? String
    ?: "C:/Users/melon_444/Documents/java_work/foolsEngine/build/libs/foolsEngine-0.0.7.jar"

application {
    mainClass.set("com.melon.ShadowForge.ShadowForge")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-stb")
    runtimeOnly("org.lwjgl", "lwjgl",          classifier = "natives-$lwjglNatives")
    runtimeOnly("org.lwjgl", "lwjgl-glfw",     classifier = "natives-$lwjglNatives")
    runtimeOnly("org.lwjgl", "lwjgl-opengl",   classifier = "natives-$lwjglNatives")
    runtimeOnly("org.lwjgl", "lwjgl-stb",      classifier = "natives-$lwjglNatives")

    implementation(files(foolsEnginePath))
    implementation("org.joml:joml:1.10.5")

    api("io.github.spair:imgui-java-binding:$imguiVersion")
    api("io.github.spair:imgui-java-lwjgl3:$imguiVersion") {
        exclude(group = "org.lwjgl")
    }
    runtimeOnly("io.github.spair:imgui-java-natives-$lwjglNatives:$imguiVersion")
}

tasks.register<Copy>("copyShaders") {
    from("src/main/resources/shader")
    into(layout.buildDirectory.dir("dist/shader"))
}

tasks.register<Copy>("copyDepthMaps") {
    from("depth_maps")
    into(layout.buildDirectory.dir("dist/depth_maps"))
    doFirst {
        val dir = file("depth_maps")
        if (!dir.exists()) dir.mkdirs()
    }
}

tasks.register<Jar>("fatJar") {
    dependsOn(tasks.named("copyShaders"), tasks.named("copyDepthMaps"))
    group = "build"
    description = "Builds a fat JAR with all dependencies for exe4j"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    from({
        configurations.runtimeClasspath.get().filter { it.exists() && it.name.endsWith(".jar") }.map { zipTree(it) }
    })

    manifest {
        attributes["Main-Class"] = "com.melon.ShadowForge.ShadowForge"
    }
}

tasks.register("dist") {
    dependsOn("fatJar")
    group = "build"
    description = "Builds fat JAR + copies shaders and depth_maps for distribution"
}
