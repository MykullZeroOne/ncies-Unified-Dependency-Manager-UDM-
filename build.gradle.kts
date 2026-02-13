import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.services.RequestedIntelliJPlatform

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin

    // kotlinx.serialization
    alias(libs.plugins.kotlinSerialization)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mavenModel)

    // JUnit 4 for existing platform tests
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // JUnit 5 for Starter integration tests
    testImplementation(libs.junit5)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kodein)

    // IDE Starter + Driver for UI automation tests
    testImplementation(libs.ideStarterSquashed)
    testImplementation(libs.ideStarterJunit5)
    testImplementation(libs.ideStarterDriver)
    testImplementation(libs.driverClient)
    testImplementation(libs.driverSdk)
    testImplementation(libs.driverModel)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        // Platform test framework for BasePlatformTestCase unit tests
        testFramework(TestFrameworkType.Platform)

        // Starter test framework for IDE integration tests
        testFramework(TestFrameworkType.Starter)
    }
}


// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
//RequestedIntelliJPlatform( type = IntelliJPlatformType.IntellijIdeaUltimate)
    pluginConfiguration {

        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            // Use specific IDE versions for verification
            // IC (Community) is not available for 2025.3+, use IU (Ultimate) or limit to older versions
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
            // For 2025.3+, use IntelliJ IDEA (unified) instead of Community
            ide(IntelliJPlatformType.IntellijIdea, "2025.3.2")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    test {
        // Unit tests use JUnit 3/4 (BasePlatformTestCase) - exclude IDE Starter integration tests
        exclude("**/integration/**")
    }

    // Separate task for IDE Starter integration tests (JUnit 5 + Starter framework)
    register<Test>("integrationTest") {
        description = "Runs IDE Starter integration tests"
        group = "verification"

        // Only run integration test classes
        include("**/integration/**")

        // Ensure plugin is built before running integration tests
        dependsOn(buildPlugin)

        // Pass the built plugin path to integration tests
        systemProperty("path.to.build.plugin", buildPlugin.get().archiveFile.get().asFile.absolutePath)

        useJUnitPlatform()
    }
    withType<JavaExec>().configureEach {
        if (name.startsWith("runIde")) {
            jvmArgs(
                "-Xms512m",
                "-Xmx2048m",
                "-XX:MaxMetaspaceSize=768m",
                // Enable dev mode for licensing bypass during development
                "-Dudm.dev.mode=true",
            )
        }
    }

    // License key generator task
    register<JavaExec>("generateLicense") {
        group = "udm"
        description = "Generate a UDM license key. Usage: ./gradlew generateLicense --args=\"email@example.com 2027-12-31\""
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.maddrobot.plugins.udm.licensing.LicenseKeyGeneratorKt")
        // Default to showing usage if no args provided
        args = listOf("generate", project.findProperty("email")?.toString() ?: "")
    }

    // Increase heap for unit/integration tests (they can also start IDE components)
    withType<Test>().configureEach {
        minHeapSize = "512m"
        maxHeapSize = "2048m"
        jvmArgs(
            "-XX:MaxMetaspaceSize=768m",
        )
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
