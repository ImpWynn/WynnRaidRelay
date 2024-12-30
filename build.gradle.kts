plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

group = "at.cath"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-server-core:${property("ktor_version")}")
    implementation("io.ktor:ktor-server-netty:${property("ktor_version")}")
    implementation("io.ktor:ktor-client-core:${property("ktor_version")}")
    implementation("io.ktor:ktor-client-cio:${property("ktor_version")}")
    implementation("io.ktor:ktor-client-content-negotiation:${property("ktor_version")}")
    implementation("io.ktor:ktor-server-content-negotiation:${property("ktor_version")}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${property("ktor_version")}")
    implementation("ch.qos.logback:logback-classic:${property("logback_version")}")
}

tasks.test {
    useJUnitPlatform()
}

tasks.create("buildFatJar", Jar::class) {
    group = "build"
    description = "Creates a fat JAR of the application"
    manifest.attributes["Main-Class"] = "at.cath.RaidRelayKt"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    with(tasks.jar.get())
}

kotlin {
    jvmToolchain(21) // Ensure compatibility with your environment
}
