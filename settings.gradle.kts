pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Flat directory for the CI-built libtailscale.aar (app/libs).
        // Declared here as documentation; the app module consumes the
        // AAR via a files() dependency so a missing AAR (local stub
        // builds) does not fail dependency resolution.
    }
}
rootProject.name = "moka-vpn"
include(":app")
