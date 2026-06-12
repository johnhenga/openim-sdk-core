plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.wire)
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "OpenIMCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.wire.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    namespace = "io.openim.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

wire {
    // Protos vendored from openimsdk/protocol at the version pinned by the
    // Go SDK (see ../protocol/README.md). Messages only — the SDK talks to
    // existing endpoints, so no RPC stubs are generated.
    sourcePath {
        srcDir(rootProject.file("protocol"))
    }
    kotlin {
        // Generate into commonMain for all targets.
    }
}

sqldelight {
    databases {
        create("OpenIMDatabase") {
            packageName.set("io.openim.core.db")
            // Schema creation is NOT driven by the generated Schema.create().
            // Fresh databases are created from the verbatim Go SDK DDL
            // (GoSdkSchema.kt) so that new installs and upgraded installs
            // have byte-identical schemas. The .sq table definitions exist
            // for compile-time query typing only and must stay
            // affinity-equivalent to docs/schema/go-sdk-schema.sql.
        }
    }
}
