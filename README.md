<div align="center">
    <a href="https://github.com/maddrobot/udm">
        <img src="./src/main/resources/META-INF/pluginIcon.svg" width="200" height="200" alt="logo"/>
    </a>
</div>
<h1 align="center">Unified Dependency Manager (UDP)</h1>
<p align="center">IntelliJ plugin for package/dependency search and management (Maven, Gradle, NPM)</p>

<p align="center">
<a href="https://github.com/maddrobot/udm"><img src="https://img.shields.io/badge/GitHub-maddrobot%2Fudm-blue?style=flat-square&logo=github" alt="GitHub"></a>
<a href="https://github.com/maddrobot/udm/releases"><img src="https://img.shields.io/github/v/release/maddrobot/udm?style=flat-square" alt="release"></a>
</p>
<br>


- [Installation](#Installation)
- [Search from Maven Central](#search-from-maven-central)
- [Explore Local Maven Repository](#explore-local-maven-repository)
- [Search Private Nexus Repository](#search-private-nexus-repository)
- [Gradle Plugin Search](#gradle-plugin-search)
- [NPM Package Search](#npm-package-search)

<!-- Plugin description -->

Search and manage packages across multiple repositories with ease.

**Features**

* Maven Dependency Search
    * Search dependencies in Maven Central.
    * Explore local Maven repository.
    * Support Nexus private repository search.
* Search Gradle plugins.
* Search NPM packages.
* NuGet-style dependency management UI.

<!-- Plugin description end -->

**Compatible with version: 2024.2 or later**

## Installation

Install from [GitHub Releases](https://github.com/maddrobot/udm/releases):

> Download the ZIP package from the [Releases](https://github.com/maddrobot/udm/releases) page
> and install it manually via **Plugins > Install Plugin from Disk...**.

## Search from Maven Central

- Search by artifact, group, or group:artifact format
- **Double-click** to copy ready-to-use dependency declaration
- **Right-click** to download JAR/sources/POM

search by artifact name

![search by artifact](screenshots/Screenshot_20250718_143725.png)

search by group

![search by group](screenshots/Screenshot_20250718_144400.png)

search by group and artifact

![search by group and artifact](screenshots/Screenshot_20250718_144454.png)

## Explore Local Maven Repository

- Switch to **Local Repository** tab
- **Double-click** to copy dependency
- **Right-click > Show in Explorer**

![](screenshots/Screenshot_20250718_144540.png)

## Search Private Nexus Repository

- Configure Nexus URL in plugin's settings: <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Unified Dependency Manager</kbd>
- Search your private repository *(requires Nexus server to allow anonymous search API access)*

![](screenshots/Screenshot_20250721_153735.png)
![](screenshots/Screenshot_20250718_144819.png)
![](screenshots/Screenshot_20250718_144842.png)

## Gradle Plugin Search

Search by tag or keywords

![](screenshots/Screenshot_20250718_145254.png)

## NPM Package Search

- Search npm packages
- **Double-click** to copy install command (supports npm/yarn/pnpm)

![](screenshots/Screenshot_20250718_145149.png)

## Change log

Please see [CHANGELOG](CHANGELOG.md) for more information what has changed recently.

## Compatibility Notes

- Supported: IntelliJ IDEA 242.0 - 252.*
- Incompatible:
    - Versions <= 241 (Missing `HttpConnectionUtils` class)
    - Versions <= 233 (API changes in `TextComponentEmptyText` and `HttpConnectionUtils`)

## License

Please see [LICENSE](LICENSE) for details.

## Links

- **GitHub**: https://github.com/maddrobot/udm
- **Issues**: https://github.com/maddrobot/udm/issues
- **Releases**: https://github.com/maddrobot/udm/releases
