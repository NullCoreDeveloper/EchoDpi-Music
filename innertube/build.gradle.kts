plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        java.srcDirs("external/java-extractor", "external/java-timeago", "src/main/java")
    }
}

wire {
    sourcePath {
        srcDir("src/main/proto")
    }
    java {
        out = "src/main/java"
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
    implementation(libs.wire.runtime)

    testImplementation(libs.junit)
}
