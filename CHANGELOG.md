<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Unified Dependency Manager (UDP) Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/maddrobot/udm/compare/v0.27.0...HEAD
[0.27.0]: https://github.com/maddrobot/udm/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/maddrobot/udm/compare/v0.25.0...v0.26.0
[0.25.0]: https://github.com/maddrobot/udm/compare/v0.24.2...v0.25.0
[0.24.2]: https://github.com/maddrobot/udm/commits/v0.24.2
