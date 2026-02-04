# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Unified Dependency Manager (UDP) is an IntelliJ IDEA plugin for searching and managing dependencies across Maven Central, Local Maven, Nexus repositories, Gradle Plugin Portal, and NPM. It provides a tool window with tabs for package discovery and a NuGet-style dependency management UI.

**Plugin ID**: `com.maddrobot.plugins.udm`
**Compatible IDEs**: IntelliJ IDEA 2024.2+ (build 242+)
**GitHub**: https://github.com/maddrobot/udm

## Build Commands

```bash
./gradlew build              # Build the plugin
./gradlew runIde             # Run IDE with plugin for development
./gradlew test               # Run unit tests
./gradlew check              # Run all tests and verifications
./gradlew verifyPlugin       # Verify plugin compatibility
./gradlew publishPlugin      # Publish to JetBrains Marketplace (requires PUBLISH_TOKEN)
```

Pre-configured run configurations are available in `.run/` directory.

## Tech Stack

- **Language**: Kotlin 2.2.0, JVM 21 toolchain
- **Build**: Gradle 9.0.0 (Kotlin DSL) with version catalog (`gradle/libs.versions.toml`)
- **Platform**: IntelliJ Platform SDK 2024.2.5
- **Testing**: JUnit 4 with IntelliJ Platform Test Framework
- **Code Quality**: Qodana (JVM Community), Kover for coverage

## Architecture

### Source Layout (`src/main/kotlin/com.maddrobot.plugins.udm.`)

| Directory | Purpose |
|-----------|---------|
| `maven/` | Maven Central, local repo, and Nexus API integration |
| `gradle/` | Gradle Plugin Portal service |
| `gradle/manager/` | NuGet-style dependency management (scanner, modifier, services) |
| `gradle/manager/ui/` | Dependency manager UI components (tabs, dialogs, tables) |
| `npm/` | NPM registry integration |
| `ui/` | Shared UI components (paginated tables, column models) |
| `setting/` | Plugin settings/preferences |
| `action/` | IDE context menu actions |

### Key Components

**Entry Points**:
- `PackageFinderToolWindowFactory` - Main tool window creation
- `PackageFinderSettingWindow` - Settings page (Settings > Tools > Package Finder)
- `META-INF/plugin.xml` - Plugin manifest and extension registration

**Gradle Dependency Manager** (actively developed):
- `GradleDependencyScanner` - Parses both Groovy DSL and Kotlin DSL gradle files
- `GradleDependencyModifier` - Safe file modifications with preview diffs
- `GradleUpdateService` - Detects available version updates
- `InstalledTab` / `UpdatesTab` - Main UI tabs for dependency management

### Plugin Dependencies

The plugin requires these bundled IntelliJ plugins:
- `com.intellij.java`
- `com.intellij.gradle`
- `org.jetbrains.kotlin`
- `org.intellij.groovy`

## Development Notes

### Gradle File Parsing

The dependency manager supports both Groovy and Kotlin DSL gradle files. When modifying build files:
- Use file offset tracking for minimal, targeted edits
- Preserve existing formatting
- Support undo via IntelliJ write actions
- Show preview diffs before applying changes

### Multi-Module Support

Dependency operations must be module-aware:
- Scan all `build.gradle` / `build.gradle.kts` files in project
- Track which module owns each dependency
- Apply changes only to the correct module's build file

### Testing

Tests are in `src/test/kotlin/`. Test files follow `*Test.kt` naming convention.

```bash
./gradlew test                    # Run all tests
./gradlew test --tests "*.DependencyTest"  # Run specific test class
```

## Resources

- **i18n strings**: `src/main/resources/messages/PackageFinder.properties`
- **Icons**: `src/main/resources/icons/`
- **Feature spec**: `docs/feat - Nuget Like Dependancy Management.md`
