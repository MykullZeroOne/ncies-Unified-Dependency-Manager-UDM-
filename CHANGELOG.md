<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Unified Dependency Manager (UDM) Changelog

## [Unreleased]

## [1.0.0] - 2026-02-13

### Added

- Exclusion suggestion engine with bundled rules for known problematic dependencies
- Dynamic conflict detection across transitive dependencies
- License management section in Settings panel (activate/deactivate direct keys)
- Dependency resolve support (mvn dependency:resolve / gradlew dependencies) from exclusion dialog
- New tests for GradleDependencyModifier, GradleDependencyScanner, RepositoryConfigWriter

### Changed

- All UI strings externalized to i18n resource bundle (100+ keys added)
- Plugin.xml action tags now use bundle keys instead of hardcoded text/description
- Removed obsolete Maven/Nexus settings from Settings panel (handled by Repositories tab)
- Platform compatibility upgraded to IntelliJ 2025.3.2
- Plugin name corrected from UDP to UDM throughout

### Fixed

- Eliminated all non-null assertions (!!) from production code
- ModalityState handling for callbacks in modal dialogs

## [0.28.0] - 2026-02-06

### Added

- Freemium licensing system with FREE and PREMIUM tiers
- Hybrid license support: JetBrains Marketplace + direct license keys
- License key generator for beta, student, friend, and internal licenses
- Development mode bypass for testing without license
- Premium feature gates for bulk upgrade, version consolidation, vulnerability scanning
- License management dialog accessible from Tools menu
- IDE Starter + Driver framework for UI integration testing
- Comprehensive UI test suite

### Changed

- Upgraded dependencies for UI testing framework

## [0.27.0] - 2026-02-04

### Added

- Package caching system to reduce network calls and improve performance
- Version lookups cached for 1 hour, search results for 5 minutes
- New Caches tab with cache statistics and management controls
- Buttons to clear version cache, search cache, or all caches

### Fixed

- Timeout errors when checking latest versions now handled gracefully

## [0.26.0] - 2026-02-04

### Fixed

- Filter out invalid/placeholder repository URLs from search operations
- Prevents connection errors from misconfigured mirror repositories (e.g., 0.0.0.0)

## [0.25.0] - 2025-02-03

### Changed

- Rebranded from Package Finder to Unified Dependency Manager (UDP)
- Updated plugin ID to `com.maddrobot.plugins.udm`
- New plugin icons

## [0.24.2] - 2025-07-21

### Changed

- Log local POM parsing failure as warning

### Fixed

- Pagination buttons in Gradle plugin search

[Unreleased]: https://github.com/maddrobot/udm/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/maddrobot/udm/compare/v0.28.0...v1.0.0
[0.28.0]: https://github.com/maddrobot/udm/compare/v0.27.0...v0.28.0
[0.27.0]: https://github.com/maddrobot/udm/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/maddrobot/udm/compare/v0.25.0...v0.26.0
[0.25.0]: https://github.com/maddrobot/udm/compare/v0.24.2...v0.25.0
[0.24.2]: https://github.com/maddrobot/udm/commits/v0.24.2
