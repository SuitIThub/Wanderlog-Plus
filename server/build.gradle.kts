plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass = "de.wanderlogplus.server.MainKt" }

dependencies {
    implementation(project(":shared"))
    implementation(project(":importer"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repoRoot", rootDir.path)
}
