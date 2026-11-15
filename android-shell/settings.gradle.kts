pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        // CI installs the locally patched qml4j into ~/.m2; mavenLocal() above
        // must win. An exclusiveContent rule would force JitPack and silently
        // substitute the upstream (unpatched) engine.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            mavenContent { releasesOnly() }
        }
        // Fallback when repo.maven.apache.org TLS fails (common on some mainland networks).
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
    }
}

rootProject.name = "qml4j-android-shell"
include(":app")
