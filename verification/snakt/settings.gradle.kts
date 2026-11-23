pluginManagement {
    repositories {
        mavenLocal {
            content {
                includeGroup("org.jetbrains.kotlin.formver")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "node-snakt"
