<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Unified Dependency Manager (UDP) Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/maddrobot/udm/compare/v0.28.0...HEAD
[0.28.0]: https://github.com/maddrobot/udm/compare/v0.27.0...v0.28.0
[0.27.0]: https://github.com/maddrobot/udm/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/maddrobot/udm/compare/v0.25.0...v0.26.0
[0.25.0]: https://github.com/maddrobot/udm/compare/v0.24.2...v0.25.0
[0.24.2]: https://github.com/maddrobot/udm/commits/v0.24.2
