---
description: Repository Information Overview
alwaysApply: true
---

# Unified Dependency Manager (UDP) Information

## Summary
Unified Dependency Manager (UDP) is an IntelliJ IDEA plugin designed to facilitate searching and managing dependencies across multiple ecosystems, including Maven Central, Local Maven Repository, Private Nexus Repositories, Gradle Plugin Portal, and NPM. It provides an integrated tool window for exploring packages and copying dependency declarations or commands.

**GitHub**: https://github.com/maddrobot/udm

## Structure
- **src/main/kotlin**: Contains the core logic for package discovery and UI implementation.
    - `com.maddrobot.plugins.udm.maven`: Maven Central, local repository, and Nexus integration.
    - `com.maddrobot.plugins.udm.npm`: NPM registry integration and model.
    - `com.maddrobot.plugins.udm.gradle`: Gradle Plugin Portal integration.
    - `com.maddrobot.plugins.udm.ui`: Tool window, tables, and custom UI components.
    - `com.maddrobot.plugins.udm.action`: IDE actions (e.g., Download Artifact, Open Folder).
- **src/main/resources**: Configuration and UI resources.
    - `META-INF/plugin.xml`: Plugin entry point and component registration.
    - `messages/PackageFinder.properties`: Localization and display strings.
- **src/test/kotlin**: Testing suite utilizing the IntelliJ Platform Testing framework.
- **gradle/**: Gradle wrapper and version catalog (`libs.versions.toml`).
- **.run/**: Pre-defined IntelliJ IDEA run configurations for development and testing.

## Language & Runtime
**Language**: Kotlin
**Version**: Kotlin 2.2.0, JVM 21 (Toolchain)
**Build System**: Gradle 9.0.0 (Kotlin DSL)
**Package Manager**: Gradle with Version Catalog

## Dependencies
**Main Dependencies**:
- **IntelliJ Platform**: IDE SDK for plugin development (2024.2.5).
- **jsoup**: Used for parsing HTML responses (e.g., from Gradle Plugin Portal).
- **kotlinx-serialization-json**: JSON handling for registry APIs.
- **maven-model**: Processing Maven POM files and models.

**Development Dependencies**:
- **JUnit**: For unit testing (4.13.2).
- **IntelliJ Platform Testing**: Specialized framework for IDE plugin integration tests.

## Build & Installation
```bash
# Build the plugin
./gradlew build

# Run the IDE with the plugin installed for development
./gradlew runIde

# Verify plugin compatibility
./gradlew verifyPlugin

# Run all tests
./gradlew test
```

## Testing
**Framework**: JUnit 4 / IntelliJ Platform Test Framework
**Test Location**: `src/test/kotlin`
**Naming Convention**: Files ending in `Test.kt`
**Configuration**: Handled via `intellijPlatformTesting` in `build.gradle.kts`.

**Run Command**:
```bash
./gradlew test
```

## Main Files & Resources
- **Plugin Manifest**: `src/main/resources/META-INF/plugin.xml`
- **Tool Window Factory**: `com.maddrobot.plugins.udm.PackageFinderToolWindowFactory`
- **Settings Configuration**: `com.maddrobot.plugins.udm.setting.PackageFinderSettingWindow`
- **Icons**: `src/main/resources/icons/`
