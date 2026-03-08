plugins {
    alias(libs.plugins.kotlin.serialization)
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        java.srcDirs("external/java-extractor", "external/java-timeago")
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    
    // Extractor dependencies (local source)
    implementation(libs.nanojson)
    implementation(libs.jsoup)
    implementation(libs.java.websocket)
    implementation(libs.spotbugs.annotations)
    implementation(libs.autolink)
    implementation(libs.protobuf.java)
    implementation(libs.apache.lang3)
    implementation(libs.json)
    implementation(libs.cache2k.api)
    implementation(libs.cache2k.core)
    implementation(libs.rhino)
    implementation("commons-codec:commons-codec:1.16.0")

    testImplementation(libs.junit)
}
