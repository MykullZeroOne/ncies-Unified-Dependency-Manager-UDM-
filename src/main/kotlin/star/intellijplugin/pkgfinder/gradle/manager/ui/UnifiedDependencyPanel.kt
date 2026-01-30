package star.intellijplugin.pkgfinder.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import star.intellijplugin.pkgfinder.gradle.manager.*
import star.intellijplugin.pkgfinder.gradle.manager.model.PackageAdapters
import star.intellijplugin.pkgfinder.gradle.manager.model.PackageMetadata
import star.intellijplugin.pkgfinder.gradle.manager.model.PackageSource
import star.intellijplugin.pkgfinder.gradle.manager.model.UnifiedPackage
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryConfig
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryDiscoveryService
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryType
import star.intellijplugin.pkgfinder.maven.DependencyService
import star.intellijplugin.pkgfinder.maven.manager.MavenDependencyModifier
import star.intellijplugin.pkgfinder.maven.manager.MavenDependencyScanner
import star.intellijplugin.pkgfinder.maven.manager.MavenInstalledDependency
import star.intellijplugin.pkgfinder.npm.NpmRegistryService
import star.intellijplugin.pkgfinder.setting.PackageFinderSetting
import star.intellijplugin.pkgfinder.maven.MavenRepositorySource
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * Main three-panel unified dependency manager container.
 * Provides a NuGet-style interface for managing dependencies across all sources.
 */
class UnifiedDependencyPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) {
    private val log = Logger.getInstance(javaClass)

    // Gradle Services
    private val gradleDependencyService = GradleDependencyManagerService.getInstance(project)
    private val gradleScanner = GradleDependencyScanner(project)
    private val gradleModifier = GradleDependencyModifier(project)

    // Maven Services
    private val mavenScanner = MavenDependencyScanner(project)
    private val mavenModifier = MavenDependencyModifier(project)

    // Repository Discovery Service
    private val repoDiscoveryService = RepositoryDiscoveryService.getInstance(project)

    // Project type detection
    private val isMavenProject: Boolean by lazy { mavenScanner.isMavenProject() }
    private val isGradleProject: Boolean by lazy { gradleScanner.getModuleBuildFiles().isNotEmpty() }

    // Cached Maven dependencies
    private var mavenInstalledDependencies: List<MavenInstalledDependency> = emptyList()

    // UI Components
    private val listPanel = PackageListPanel(project, parentDisposable)
    private val detailsPanel = PackageDetailsPanel(project, parentDisposable)

    // Module selector
    private val moduleSelector = JComboBox<String>()
    private var availableModules: List<String> = emptyList()

    // Repository selector for search (Source dropdown)
    private val searchRepoSelector = JComboBox<RepositoryConfig>()
    private var searchableRepos: List<RepositoryConfig> = emptyList()

    // Pre-release checkbox
    private val preReleaseCheckbox = JCheckBox(message("unified.filter.prerelease"))

    // Main content panel
    val contentPanel: JPanel

    // Content panel without header (for use in MainToolWindowPanel)
    private val mainContentPanel: JPanel

    init {
        mainContentPanel = createMainContentPanel()
        contentPanel = createMainPanel()
        setupCallbacks()
        setupMessageBusSubscription()
        loadInitialData()
    }

    /**
     * Get the module selector for use in the main header.
     */
    fun getModuleSelector(): JComboBox<String> = moduleSelector

    /**
     * Get the content panel without the header (for embedding in MainToolWindowPanel).
     */
    fun getContentWithoutHeader(): JPanel = mainContentPanel

    /**
     * Refresh all dependency data.
     */
    fun refresh() {
        if (isGradleProject) {
            gradleDependencyService.refresh()
        }
        if (isMavenProject) {
            refreshMavenDependencies()
        }
    }

    private fun createMainPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            // Header with module selector (legacy, for standalone use)
            add(createHeaderPanel(), BorderLayout.NORTH)
            add(mainContentPanel, BorderLayout.CENTER)
        }
    }

    private fun createMainContentPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            // Filter bar with search, source dropdown, and pre-release checkbox
            add(createFilterBar(), BorderLayout.NORTH)

            // Main content: List panel (left) + Details panel (right)
            val splitter = JBSplitter(false, 0.65f).apply {
                firstComponent = listPanel.component
                secondComponent = detailsPanel.component
                dividerWidth = 1
            }
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun createFilterBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 8, 8)

            // Left side: Search field (handled by listPanel)
            // The search field is already in PackageListPanel

            // Right side: Source dropdown and Pre-release checkbox
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
                add(JBLabel(message("unified.filter.source")))
                add(searchRepoSelector.apply {
                    preferredSize = java.awt.Dimension(140, preferredSize.height)
                    renderer = RepositoryComboRenderer()
                })
                add(preReleaseCheckbox)
            }

            add(rightPanel, BorderLayout.EAST)
        }
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 4, 8)

            // Left: Title
            val titleLabel = JBLabel(message("unified.panel.title")).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }

            // Right: Search Repo selector + Module selector + Settings button
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                // Search repository selector
                add(JBLabel(message("unified.panel.search.repo")))
                add(searchRepoSelector.apply {
                    preferredSize = java.awt.Dimension(180, preferredSize.height)
                    renderer = RepositoryComboRenderer()
                })

                add(Box.createHorizontalStrut(12))

                // Module selector
                add(JBLabel(message("unified.panel.module")))
                add(moduleSelector.apply {
                    preferredSize = java.awt.Dimension(150, preferredSize.height)
                    addItem(message("unified.panel.module.all"))
                })
                add(createSettingsButton())
            }

            add(titleLabel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    /**
     * Custom renderer for repository combo box.
     */
    private inner class RepositoryComboRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is RepositoryConfig) {
                text = value.name
            }
            return this
        }
    }

    private fun createSettingsButton(): JButton {
        return JButton(AllIcons.General.Settings).apply {
            toolTipText = message("unified.panel.settings")
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener {
                showRepositoryManager()
            }
        }
    }

    private fun setupCallbacks() {
        // List panel callbacks
        listPanel.onSelectionChanged = { pkg ->
            detailsPanel.setPackage(pkg)
        }

        listPanel.onFilterModeChanged = { mode ->
            loadDataForMode(mode)
        }

        listPanel.onSearchRequested = { query ->
            if (query.isNotBlank()) {
                performSearch(query)
            }
        }

        listPanel.onRefreshRequested = {
            if (isGradleProject) {
                gradleDependencyService.refresh()
            }
            if (isMavenProject) {
                refreshMavenDependencies()
            }
        }

        // Module selector
        moduleSelector.addActionListener {
            val selectedModule = moduleSelector.selectedItem as? String
            val moduleFilter = if (selectedModule == message("unified.panel.module.all")) null else selectedModule
            listPanel.setModuleFilter(moduleFilter)
        }

        // Details panel callbacks
        detailsPanel.onInstallRequested = { pkg, version, module, configuration ->
            performInstall(pkg, version, module, configuration)
        }

        detailsPanel.onUpdateRequested = { pkg, newVersion ->
            performUpdate(pkg, newVersion)
        }

        detailsPanel.onDowngradeRequested = { pkg, newVersion ->
            performUpdate(pkg, newVersion) // Downgrade uses same logic as update
        }

        detailsPanel.onUninstallRequested = { pkg ->
            performUninstall(pkg)
        }

        detailsPanel.onVersionsNeeded = { pkg, callback ->
            fetchAvailableVersions(pkg, callback)
        }
    }

    private fun setupMessageBusSubscription() {
        project.messageBus.connect(parentDisposable).subscribe(
            GradleDependencyManagerService.DEPENDENCY_CHANGE_TOPIC,
            object : GradleDependencyManagerService.DependencyChangeListener {
                override fun onDependenciesChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        updateModuleSelector()
                        loadDataForMode(listPanel.getFilterMode())
                    }
                }
            }
        )
    }

    private fun loadInitialData() {
        // Populate search repository selector
        populateSearchRepoSelector()

        // Refresh Gradle dependencies if it's a Gradle project
        if (isGradleProject) {
            gradleDependencyService.refresh()
        }

        // Scan Maven dependencies if it's a Maven project
        if (isMavenProject) {
            refreshMavenDependencies()
        }
    }

    private fun populateSearchRepoSelector() {
        searchRepoSelector.removeAllItems()

        // Get all searchable repositories (exclude NPM and Gradle Plugin Portal for now)
        searchableRepos = repoDiscoveryService.getConfiguredRepositories()
            .filter { it.type != RepositoryType.NPM && it.type != RepositoryType.GRADLE_PLUGIN_PORTAL }
            .filter { it.enabled }

        for (repo in searchableRepos) {
            searchRepoSelector.addItem(repo)
        }

        // Select Maven Central by default if available
        val mavenCentral = searchableRepos.find { it.type == RepositoryType.MAVEN_CENTRAL }
        if (mavenCentral != null) {
            searchRepoSelector.selectedItem = mavenCentral
        }
    }

    private fun refreshMavenDependencies() {
        ApplicationManager.getApplication().executeOnPooledThread {
            mavenInstalledDependencies = mavenScanner.scanInstalledDependencies()

            ApplicationManager.getApplication().invokeLater {
                updateModuleSelector()
                loadDataForMode(listPanel.getFilterMode())
            }
        }
    }

    private fun updateModuleSelector() {
        val currentSelection = moduleSelector.selectedItem as? String
        moduleSelector.removeAllItems()
        moduleSelector.addItem(message("unified.panel.module.all"))

        // Collect modules from both Gradle and Maven
        val gradleModules = if (isGradleProject) {
            gradleDependencyService.installedDependencies.map { it.moduleName }
        } else emptyList()

        val mavenModules = if (isMavenProject) {
            mavenInstalledDependencies.map { it.moduleName }
        } else emptyList()

        availableModules = (gradleModules + mavenModules).distinct().sorted()

        for (module in availableModules) {
            moduleSelector.addItem(module)
        }

        // Restore selection if possible
        if (currentSelection != null && (currentSelection == message("unified.panel.module.all") || currentSelection in availableModules)) {
            moduleSelector.selectedItem = currentSelection
        }
    }

    private fun loadDataForMode(mode: PackageListPanel.FilterMode) {
        when (mode) {
            PackageListPanel.FilterMode.INSTALLED -> {
                val packages = getAllInstalledPackages()
                listPanel.setPackages(packages)
            }

            PackageListPanel.FilterMode.UPDATES -> {
                // For now, only Gradle has update detection
                val packages = if (isGradleProject) {
                    gradleDependencyService.dependencyUpdates.map { update ->
                        PackageAdapters.fromInstalledDependency(update.installed, update)
                    }
                } else emptyList()
                listPanel.setPackages(packages)
            }

            PackageListPanel.FilterMode.ALL -> {
                // Show installed with merged update info
                val packages = getAllInstalledPackages()
                listPanel.setPackages(packages)
            }

            PackageListPanel.FilterMode.BROWSE -> {
                // Clear list, wait for search
                listPanel.clearPackages()
            }
        }
    }

    private fun getAllInstalledPackages(): List<UnifiedPackage> {
        val packages = mutableListOf<UnifiedPackage>()

        // Add Gradle packages
        if (isGradleProject) {
            packages.addAll(
                PackageAdapters.aggregateByPackage(
                    gradleDependencyService.installedDependencies,
                    gradleDependencyService.dependencyUpdates
                )
            )
        }

        // Add Maven packages
        if (isMavenProject) {
            packages.addAll(
                PackageAdapters.aggregateMavenByPackage(mavenInstalledDependencies)
            )
        }

        return packages
    }

    private fun performSearch(query: String) {
        listPanel.setLoading(true)

        val selectedRepo = searchRepoSelector.selectedItem as? RepositoryConfig

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val packages = searchInRepository(query, selectedRepo)

                // Merge with installed status from both Gradle and Maven
                val gradleInstalled = if (isGradleProject) gradleDependencyService.installedDependencies else emptyList()
                val mavenInstalled = if (isMavenProject) mavenInstalledDependencies else emptyList()

                val mergedPackages = packages.map { pkg ->
                    // Check Gradle first
                    val gradleDep = gradleInstalled.find { it.id == pkg.id }
                    if (gradleDep != null) {
                        val modules = gradleInstalled.filter { it.id == pkg.id }.map { it.moduleName }
                        return@map PackageAdapters.mergeWithInstalled(pkg, gradleDep, modules)
                    }

                    // Then check Maven
                    val mavenDep = mavenInstalled.find { it.id == pkg.id }
                    if (mavenDep != null) {
                        val modules = mavenInstalled.filter { it.id == pkg.id }.map { it.moduleName }
                        return@map PackageAdapters.mergeWithMavenInstalled(pkg, mavenDep, modules)
                    }

                    pkg
                }

                ApplicationManager.getApplication().invokeLater {
                    listPanel.setPackages(mergedPackages)
                    listPanel.setLoading(false)
                }
            } catch (e: Exception) {
                log.error("Search failed", e)
                ApplicationManager.getApplication().invokeLater {
                    listPanel.setLoading(false)
                }
            }
        }
    }

    /**
     * Search for packages in the specified repository.
     */
    private fun searchInRepository(query: String, repo: RepositoryConfig?): List<UnifiedPackage> {
        if (repo == null) return emptyList()

        log.info("Searching repository: ${repo.name} (${repo.type}) for query: $query")

        return when (repo.type) {
            RepositoryType.MAVEN_CENTRAL -> {
                val results = DependencyService.searchFromMavenCentral(query)
                results.map { PackageAdapters.fromDependency(it, PackageSource.MAVEN_CENTRAL) }
            }
            RepositoryType.NEXUS -> {
                val results = DependencyService.searchFromNexus(query)
                results.map { PackageAdapters.fromDependency(it, PackageSource.NEXUS) }
            }
            RepositoryType.AZURE_ARTIFACTS -> {
                searchAzureArtifacts(query, repo)
            }
            RepositoryType.ARTIFACTORY -> {
                searchArtifactoryRepo(query, repo)
            }
            RepositoryType.CUSTOM, RepositoryType.MAVEN, RepositoryType.JITPACK -> {
                // Try generic Maven repository search
                searchGenericMavenRepo(query, repo)
            }
            else -> {
                log.warn("Search not supported for repository type: ${repo.type}")
                emptyList()
            }
        }
    }

    /**
     * Search a generic Maven repository by trying to find artifacts.
     * Uses the Maven Central search API format if the repo supports it,
     * otherwise falls back to directory listing heuristics.
     */
    private fun searchGenericMavenRepo(query: String, repo: RepositoryConfig): List<UnifiedPackage> {
        // For Azure Artifacts, Artifactory, and other repos, we need to use their specific APIs
        val repoUrl = repo.url.trimEnd('/')

        // Try Artifactory API if it looks like an Artifactory URL
        if (repoUrl.contains("artifactory") || repoUrl.contains("jfrog")) {
            return searchArtifactoryRepo(query, repo)
        }

        // Try Azure Artifacts API if it looks like an Azure DevOps URL
        if (repoUrl.contains("pkgs.dev.azure.com") || repoUrl.contains("visualstudio.com")) {
            return searchAzureArtifacts(query, repo)
        }

        // For other repos, try a simple Nexus-style search if available
        val nexusSearchUrl = "$repoUrl/service/rest/v1/search?sort=version&direction=desc&q=$query"
        try {
            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(nexusSearchUrl) { it }
            if (result is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success && result.data != null) {
                // Parse Nexus-style response
                val results = DependencyService.searchFromNexus(query)
                return results.map { PackageAdapters.fromDependency(it, PackageSource.NEXUS) }
            }
        } catch (e: Exception) {
            log.debug("Nexus-style search not available at $repoUrl: ${e.message}")
        }

        log.info("Search not fully supported for repository: ${repo.name}. Try using Maven Central or configure a Nexus repository.")
        return emptyList()
    }

    /**
     * Search Artifactory repository using Artifactory Query Language (AQL) or GAVC search.
     */
    private fun searchArtifactoryRepo(query: String, repo: RepositoryConfig): List<UnifiedPackage> {
        val repoUrl = repo.url.trimEnd('/')

        // Try Artifactory GAVC search API
        val searchUrl = "$repoUrl/api/search/gavc?g=*&a=*$query*&repos=${repo.id}"

        try {
            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(searchUrl) { it }
            if (result is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success && result.data != null) {
                return parseArtifactoryResponse(result.data, repo)
            }
        } catch (e: Exception) {
            log.debug("Artifactory search failed: ${e.message}")
        }

        return emptyList()
    }

    private fun parseArtifactoryResponse(response: String, repo: RepositoryConfig): List<UnifiedPackage> {
        // Simple parsing of Artifactory GAVC search response
        val packages = mutableListOf<UnifiedPackage>()
        try {
            // Response format: {"results":[{"uri":"..."}, ...]}
            val uriPattern = """"uri"\s*:\s*"([^"]+)"""".toRegex()
            val matches = uriPattern.findAll(response)

            for (match in matches.take(50)) { // Limit results
                val uri = match.groupValues[1]
                // Extract groupId, artifactId, version from URI
                // Format: .../groupId/artifactId/version/artifactId-version.ext
                val parts = uri.split("/").takeLast(4)
                if (parts.size >= 3) {
                    val artifactId = parts[parts.size - 3]
                    val version = parts[parts.size - 2]
                    val groupId = uri.substringAfter(repo.url.trimEnd('/') + "/")
                        .substringBefore("/$artifactId/")
                        .replace("/", ".")

                    if (groupId.isNotBlank() && artifactId.isNotBlank()) {
                        packages.add(
                            UnifiedPackage(
                                name = artifactId,
                                publisher = groupId,
                                installedVersion = null,
                                latestVersion = version,
                                description = null,
                                homepage = null,
                                license = null,
                                scope = null,
                                modules = emptyList(),
                                source = PackageSource.NEXUS, // Using NEXUS as closest match
                                metadata = star.intellijplugin.pkgfinder.gradle.manager.model.PackageMetadata.MavenMetadata(
                                    packaging = "jar",
                                    timestamp = null,
                                    extensions = emptyList()
                                )
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to parse Artifactory response: ${e.message}")
        }
        return packages.distinctBy { it.id }
    }

    /**
     * Search Azure Artifacts feed using Azure DevOps REST API.
     */
    private fun searchAzureArtifacts(query: String, repo: RepositoryConfig): List<UnifiedPackage> {
        // Azure Artifacts URL formats:
        // - Project-scoped: https://pkgs.dev.azure.com/{org}/{project}/_packaging/{feed}/maven/v1
        // - Org-scoped: https://pkgs.dev.azure.com/{org}/_packaging/{feed}/maven/v1
        // API endpoint: https://pkgs.dev.azure.com/{org}/{project}/_apis/packaging/feeds/{feed}/packages?api-version=7.0
        val repoUrl = repo.url.trimEnd('/')

        log.info("Searching Azure Artifacts: $repoUrl for query: $query")

        try {
            // Try project-scoped URL first
            var regex = """pkgs\.dev\.azure\.com/([^/]+)/([^/]+)/_packaging/([^/]+)""".toRegex()
            var match = regex.find(repoUrl)

            val org: String
            val project: String?
            val feed: String

            if (match != null) {
                // Project-scoped URL
                org = match.groupValues[1]
                project = match.groupValues[2]
                feed = match.groupValues[3]
                log.info("Detected project-scoped Azure Artifacts: org=$org, project=$project, feed=$feed")
            } else {
                // Try org-scoped URL
                regex = """pkgs\.dev\.azure\.com/([^/]+)/_packaging/([^/]+)""".toRegex()
                match = regex.find(repoUrl)

                if (match != null) {
                    org = match.groupValues[1]
                    project = null
                    feed = match.groupValues[2]
                    log.info("Detected org-scoped Azure Artifacts: org=$org, feed=$feed")
                } else {
                    log.warn("Could not parse Azure Artifacts URL: $repoUrl")
                    return emptyList()
                }
            }

            val apiBase = if (project != null) {
                "https://pkgs.dev.azure.com/$org/$project/_apis/packaging/feeds/$feed"
            } else {
                "https://pkgs.dev.azure.com/$org/_apis/packaging/feeds/$feed"
            }

            // Build the search URL - use empty query to list all packages if no query provided
            val searchUrl = if (query.isNotBlank()) {
                "$apiBase/packages?api-version=7.0&packageNameQuery=$query&protocolType=maven"
            } else {
                "$apiBase/packages?api-version=7.0&protocolType=maven"
            }

            log.info("Azure Artifacts API URL: $searchUrl")

            // Build authentication credentials
            val auth = if (repo.username != null && repo.password != null) {
                star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials(
                    username = repo.username,
                    password = repo.password
                )
            } else {
                // For Azure Artifacts, try using a PAT with empty username (Azure DevOps convention)
                // The PAT is often stored as the password
                repo.password?.let { pat ->
                    star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials(
                        username = "",
                        password = pat
                    )
                }
            }

            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }

            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        log.info("Azure Artifacts search returned data (${result.data.length} chars)")
                        return parseAzureArtifactsResponse(result.data)
                    } else {
                        log.info("Azure Artifacts search returned empty response")
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    log.warn("Azure Artifacts search failed: ${result.exception.message} (code: ${result.responseCode})")
                    if (result.responseCode == 401 || result.responseCode == 403) {
                        log.warn("Authentication failed for Azure Artifacts. Make sure credentials are configured.")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Azure Artifacts search failed with exception: ${e.message}", e)
        }

        return emptyList()
    }

    private fun parseAzureArtifactsResponse(response: String): List<UnifiedPackage> {
        val packages = mutableListOf<UnifiedPackage>()
        try {
            // Azure Artifacts response format:
            // {"count":N,"value":[{"id":"...","name":"groupId:artifactId","versions":[{"version":"...","publishDate":"..."}],...}]}
            log.debug("Parsing Azure Artifacts response: ${response.take(500)}...")

            // Use a more robust JSON parsing approach
            // Extract package objects from the "value" array
            val valueArrayStart = response.indexOf("\"value\"")
            if (valueArrayStart == -1) {
                log.warn("Azure Artifacts response missing 'value' array")
                return emptyList()
            }

            // Find all "name" fields that contain colons (groupId:artifactId format)
            val packagePattern = """\{\s*"[^"]*"[^}]*"name"\s*:\s*"([^"]+)"[^}]*"versions"\s*:\s*\[([^\]]*)\]""".toRegex()
            val matches = packagePattern.findAll(response)

            for (match in matches) {
                val fullName = match.groupValues[1]
                val versionsJson = match.groupValues[2]

                // Parse name (format: groupId:artifactId)
                if (fullName.contains(":")) {
                    val parts = fullName.split(":")
                    if (parts.size >= 2) {
                        val groupId = parts[0]
                        val artifactId = parts[1]

                        // Extract the latest version from versions array
                        val versionMatch = """"version"\s*:\s*"([^"]+)"""".toRegex().find(versionsJson)
                        val version = versionMatch?.groupValues?.get(1)

                        packages.add(
                            UnifiedPackage(
                                name = artifactId,
                                publisher = groupId,
                                installedVersion = null,
                                latestVersion = version,
                                description = null,
                                homepage = null,
                                license = null,
                                scope = null,
                                modules = emptyList(),
                                source = PackageSource.NEXUS, // Using NEXUS as closest match
                                metadata = star.intellijplugin.pkgfinder.gradle.manager.model.PackageMetadata.MavenMetadata(
                                    packaging = "jar",
                                    timestamp = null,
                                    extensions = emptyList()
                                )
                            )
                        )

                        log.debug("Found Azure Artifacts package: $groupId:$artifactId:$version")
                    }
                }
            }

            // If the pattern didn't match, try a simpler approach
            if (packages.isEmpty()) {
                log.info("Trying simpler parsing for Azure Artifacts response")

                // Look for name patterns directly
                val namePattern = """"name"\s*:\s*"([^":]+):([^"]+)"""".toRegex()
                val nameMatches = namePattern.findAll(response)

                for (nameMatch in nameMatches) {
                    val groupId = nameMatch.groupValues[1]
                    val artifactId = nameMatch.groupValues[2]

                    packages.add(
                        UnifiedPackage(
                            name = artifactId,
                            publisher = groupId,
                            installedVersion = null,
                            latestVersion = null,
                            description = null,
                            homepage = null,
                            license = null,
                            scope = null,
                            modules = emptyList(),
                            source = PackageSource.NEXUS,
                            metadata = star.intellijplugin.pkgfinder.gradle.manager.model.PackageMetadata.MavenMetadata(
                                packaging = "jar",
                                timestamp = null,
                                extensions = emptyList()
                            )
                        )
                    )
                }
            }

            log.info("Parsed ${packages.size} packages from Azure Artifacts response")
        } catch (e: Exception) {
            log.warn("Failed to parse Azure Artifacts response: ${e.message}", e)
        }
        return packages.distinctBy { it.id }
    }

    private fun performInstall(pkg: UnifiedPackage, version: String, module: String, configuration: String) {
        // Determine if this should be installed via Gradle or Maven based on project type
        if (isGradleProject) {
            performGradleInstall(pkg, version, module, configuration)
        } else if (isMavenProject) {
            performMavenInstall(pkg, version, module, configuration)
        }
    }

    private fun performGradleInstall(pkg: UnifiedPackage, version: String, module: String, configuration: String) {
        val buildFiles = gradleScanner.getModuleBuildFiles()
        val buildFile = buildFiles[module]?.path ?: return

        val newContent = gradleModifier.getAddedContent(buildFile, pkg.publisher, pkg.name, version, configuration)
        if (newContent != null) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile)
            val originalContent = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text } ?: ""

            val dialog = PreviewDiffDialog(project, buildFile, originalContent, newContent)
            if (dialog.showAndGet()) {
                gradleModifier.applyChanges(buildFile, newContent, "Add Dependency: ${pkg.id}")
            }
        }
    }

    private fun performMavenInstall(pkg: UnifiedPackage, version: String, module: String, scope: String) {
        val pomFiles = mavenScanner.getModulePomFiles()
        val pomFile = pomFiles[module]?.path ?: return

        val newContent = mavenModifier.getAddedContent(pomFile, pkg.publisher, pkg.name, version, scope.takeIf { it != "implementation" })
        if (newContent != null) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile)
            val originalContent = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text } ?: ""

            val dialog = PreviewDiffDialog(project, pomFile, originalContent, newContent)
            if (dialog.showAndGet()) {
                mavenModifier.applyChanges(pomFile, newContent, "Add Dependency: ${pkg.id}")
                refreshMavenDependencies()
            }
        }
    }

    private fun performUpdate(pkg: UnifiedPackage, newVersion: String) {
        val metadata = pkg.metadata

        when (metadata) {
            is PackageMetadata.GradleMetadata -> performGradleUpdate(pkg, newVersion)
            is PackageMetadata.MavenInstalledMetadata -> performMavenUpdate(pkg, newVersion)
            else -> log.warn("Cannot update package with metadata type: ${metadata::class.simpleName}")
        }
    }

    private fun performGradleUpdate(pkg: UnifiedPackage, newVersion: String) {
        // Find the installed dependency to update
        val installed = gradleDependencyService.installedDependencies.find {
            it.groupId == pkg.publisher && it.artifactId == pkg.name
        } ?: return

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile)
        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null) {
            val originalContent = document.text
            val newContent = gradleModifier.getUpdatedContent(installed, newVersion)
            if (newContent != null) {
                val dialog = PreviewDiffDialog(project, installed.buildFile, originalContent, newContent)
                if (dialog.showAndGet()) {
                    gradleModifier.applyChanges(installed, newContent, "Update Dependency: ${pkg.id}")
                }
            }
        }
    }

    private fun performMavenUpdate(pkg: UnifiedPackage, newVersion: String) {
        // Find the installed dependency to update
        val installed = mavenInstalledDependencies.find {
            it.groupId == pkg.publisher && it.artifactId == pkg.name
        } ?: return

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.pomFile)
        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null) {
            val originalContent = document.text
            val newContent = mavenModifier.getUpdatedContent(installed, newVersion)
            if (newContent != null) {
                val dialog = PreviewDiffDialog(project, installed.pomFile, originalContent, newContent)
                if (dialog.showAndGet()) {
                    mavenModifier.applyChanges(installed, newContent, "Update Dependency: ${pkg.id}")
                    refreshMavenDependencies()
                }
            }
        }
    }

    private fun performUninstall(pkg: UnifiedPackage) {
        val metadata = pkg.metadata

        when (metadata) {
            is PackageMetadata.GradleMetadata -> performGradleUninstall(pkg)
            is PackageMetadata.MavenInstalledMetadata -> performMavenUninstall(pkg)
            else -> log.warn("Cannot uninstall package with metadata type: ${metadata::class.simpleName}")
        }
    }

    private fun performGradleUninstall(pkg: UnifiedPackage) {
        // Find the installed dependency to remove
        val installed = gradleDependencyService.installedDependencies.find {
            it.groupId == pkg.publisher && it.artifactId == pkg.name
        } ?: return

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile)
        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null) {
            val originalContent = document.text
            val newContent = gradleModifier.getRemovedContent(installed)
            if (newContent != null) {
                val dialog = PreviewDiffDialog(project, installed.buildFile, originalContent, newContent)
                if (dialog.showAndGet()) {
                    gradleModifier.applyChanges(installed, newContent, "Remove Dependency: ${pkg.id}")
                }
            }
        }
    }

    private fun performMavenUninstall(pkg: UnifiedPackage) {
        // Find the installed dependency to remove
        val installed = mavenInstalledDependencies.find {
            it.groupId == pkg.publisher && it.artifactId == pkg.name
        } ?: return

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.pomFile)
        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null) {
            val originalContent = document.text
            val newContent = mavenModifier.getRemovedContent(installed)
            if (newContent != null) {
                val dialog = PreviewDiffDialog(project, installed.pomFile, originalContent, newContent)
                if (dialog.showAndGet()) {
                    mavenModifier.applyChanges(installed, newContent, "Remove Dependency: ${pkg.id}")
                    refreshMavenDependencies()
                }
            }
        }
    }

    private fun fetchAvailableVersions(pkg: UnifiedPackage, callback: (List<String>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Search for all versions of this package
                val query = "${pkg.publisher}:${pkg.name}"
                val results = DependencyService.searchFromMavenCentral(query)
                val versions = results.map { it.version }.distinct().sortedDescending()

                ApplicationManager.getApplication().invokeLater {
                    callback(versions.ifEmpty { listOfNotNull(pkg.latestVersion) })
                }
            } catch (e: Exception) {
                log.error("Failed to fetch versions", e)
                ApplicationManager.getApplication().invokeLater {
                    callback(listOfNotNull(pkg.latestVersion))
                }
            }
        }
    }

    private fun showRepositoryManager() {
        RepositoryManagerDialog(project).show()
    }
}
