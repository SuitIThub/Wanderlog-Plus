plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass = "de.wanderlogplus.importer.CliKt" }

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test { useJUnitPlatform() }
