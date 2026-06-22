plugins {
    id("java-library")
}

group = "com.melon.ShadowForge"
version = "0.0.1-SNAPSHOT"

val lwjglNatives = project.properties["lwjglNatives"] as? String ?: "windows"
val lwjglUseMaven = project.properties["lwjglUseMaven"] == "true"
val lwjglVersion = "3.4.1"
val imguiVersion = "1.92.0"

repositories {
    mavenCentral()
}

dependencies {

    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    api("org.lwjgl", "lwjgl")
    api("org.lwjgl", "lwjgl-glfw")
    api("org.lwjgl", "lwjgl-opengl")
    api("org.lwjgl", "lwjgl-stb")
    runtimeOnly("org.lwjgl", "lwjgl",          classifier = "natives-$lwjglNatives")
    runtimeOnly("org.lwjgl", "lwjgl-glfw",     classifier = "natives-$lwjglNatives")
    runtimeOnly("org.lwjgl", "lwjgl-opengl",   classifier = "natives-$lwjglNatives")
    runtimeOnly("org.lwjgl", "lwjgl-stb",      classifier = "natives-$lwjglNatives")

    implementation(files("C:/Users/melon_444/Documents/java_work/foolsEngine/build/libs/foolsEngine-0.0.6.jar"))
    implementation("org.joml:joml:1.10.5")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion"){
        exclude(group = "org.lwjgl")
    }
    testRuntimeOnly("io.github.spair:imgui-java-natives-$lwjglNatives:$imguiVersion")

}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("run") {
    dependsOn(tasks.compileTestJava)
    group = "application"
    description = "Runs ShadowForge terrain renderer"
    mainClass.set("com.melon.ShadowForge.ShadowForge")
    classpath = sourceSets["test"].runtimeClasspath
}