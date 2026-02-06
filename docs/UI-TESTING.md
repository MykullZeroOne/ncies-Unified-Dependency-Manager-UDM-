# UI Testing with IDE Starter + Driver

This plugin uses JetBrains IDE Starter + Driver framework for UI integration testing. This is the recommended approach for testing IntelliJ plugins (replaces the deprecated Remote Robot library).

## Overview

- **IDE Starter**: Downloads and manages IDE instances for testing
- **Driver SDK**: Provides APIs for interacting with the running IDE

## Running Tests

### Prerequisites

1. First build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```

2. Run all integration tests:
   ```bash
   ./gradlew test
   ```

### Running Specific Tests

```bash
# Run all integration tests
./gradlew test --tests "*.integration.*"

# Run specific test class
./gradlew test --tests "PluginIntegrationTest"

# Run specific test method
./gradlew test --tests "PluginIntegrationTest.plugin loads successfully"
```

## Test Files

- `src/test/kotlin/.../integration/PluginIntegrationTest.kt` - Basic plugin loading and tool window tests
- `src/test/kotlin/.../integration/UiFeatureTest.kt` - Feature-specific UI tests

## Writing New Tests

### Basic Test Structure

```kotlin
@Test
fun `my feature works`() {
    val pathToPlugin = System.getProperty("path.to.build.plugin")
        ?: error("Plugin path not provided")

    Starter.newContext(
        testName = "myFeatureWorks",
        testCase = TestCase(
            ideInfo = IdeProductProvider.IC,
            projectInfo = LocalProjectInfo(
                Path("src/test/resources/test-projects/simple-gradle")
            )
        ).withVersion("2024.3")
    ).apply {
        PluginConfigurator(this).installPluginFromPath(Path(pathToPlugin))
    }.runIdeWithDriver().useDriverAndCloseIde {
        // Wait for IDE initialization
        waitForIndicators(2.minutes)

        // Your test logic here
        invokeAction("YourActionId")
        Thread.sleep(1000)

        // Assertions...
    }
}
```

### Common Actions

```kotlin
// Open tool window
invokeAction("ActivateUnified Dependency Manager (UDP)ToolWindow")

// Open settings
invokeAction("ShowSettings")

// Refresh
invokeAction("Refresh")

// Open license dialog
invokeAction("UDM.ManageLicense")

// Close dialog
invokeAction("EditorEscape")
```

## Dependencies

The test framework dependencies are configured in:
- `gradle/libs.versions.toml` - Version definitions
- `build.gradle.kts` - Test dependencies

```toml
# libs.versions.toml
[versions]
ideStarter = "LATEST-EAP-SNAPSHOT"

[libraries]
ideStarterSquashed = { group = "com.jetbrains.intellij.tools", name = "ide-starter-squashed" }
ideStarterJunit5 = { group = "com.jetbrains.intellij.tools", name = "ide-starter-junit5" }
ideStarterDriver = { group = "com.jetbrains.intellij.tools", name = "ide-starter-driver" }
driverClient = { group = "com.jetbrains.intellij.driver", name = "driver-client" }
driverSdk = { group = "com.jetbrains.intellij.driver", name = "driver-sdk" }
driverModel = { group = "com.jetbrains.intellij.driver", name = "driver-model" }
```

## Notes

- First run downloads the IDE (~2GB), subsequent runs use cached version
- Tests run in isolated IDE instances with the plugin installed
- Development mode (`-Dudm.dev.mode=true`) is set during test runs
- Test projects are located in `src/test/resources/test-projects/`

## Resources

- [IDE Starter GitHub](https://github.com/JetBrains/intellij-ide-starter)
- [IntelliJ Platform Testing](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html)
