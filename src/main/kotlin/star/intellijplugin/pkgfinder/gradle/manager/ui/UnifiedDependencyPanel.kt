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
import star.intellijplugin.pkgfinder.gradle.manager.service.PluginLogService
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
    companion object {
        // Sentinel value for "All Repositories" option - must be in companion object
        // to be initialized before init block runs
        val ALL_REPOSITORIES_OPTION = RepositoryConfig(
            id = "__all__",
            name = "All Repositories",
            url = "",
            type = RepositoryType.CUSTOM
        )
    }

    private val log = Logger.getInstance(javaClass)
    private val uiLog by lazy { PluginLogService.getInstance(project) }

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

            // Right: Module selector + Settings button
            // Note: Search repo selector is only in the filter bar to avoid Swing component issues
            // (a component can only be in one container at a time)
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
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
                // Show icon for "All Repositories"
                icon = if (value.id == ALL_REPOSITORIES_OPTION.id) {
                    AllIcons.Actions.Search
                } else {
                    null
                }
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
        detailsPanel.onInstallRequested = { pkg, version, module, configuration, sourceRepoUrl ->
            performInstall(pkg, version, module, configuration, sourceRepoUrl)
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

        uiLog.info("Populating search repo selector with ${searchableRepos.size} repositories", "Init")

        // Add "All Repositories" option first
        searchRepoSelector.addItem(ALL_REPOSITORIES_OPTION)
        uiLog.debug("Added 'All Repositories' option (id=${ALL_REPOSITORIES_OPTION.id})", "Init")

        for (repo in searchableRepos) {
            searchRepoSelector.addItem(repo)
            val hasAuth = repo.username != null || repo.password != null
            val authInfo = if (hasAuth) {
                "HAS CREDENTIALS (user=${repo.username ?: "null"}, pw=${repo.password?.let { "${it.length} chars" } ?: "null"})"
            } else {
                "NO CREDENTIALS"
            }
            uiLog.info("Repository: ${repo.name} | id='${repo.id}' | type=${repo.type} | source=${repo.source} | $authInfo", "Init")
        }

        // Select "All Repositories" by default
        searchRepoSelector.selectedItem = ALL_REPOSITORIES_OPTION

        // Verify selection
        val selected = searchRepoSelector.selectedItem
        uiLog.info("Search repo selector initialized. Total items: ${searchRepoSelector.itemCount}, Selected: ${(selected as? RepositoryConfig)?.name ?: "null"}", "Init")
    }

    /**
     * Refresh the repository selector (call this after adding new repositories).
     */
    fun refreshRepositories() {
        val currentSelection = searchRepoSelector.selectedItem
        populateSearchRepoSelector()
        // Try to restore previous selection
        if (currentSelection != null) {
            for (i in 0 until searchRepoSelector.itemCount) {
                val item = searchRepoSelector.getItemAt(i)
                if (item.id == (currentSelection as? RepositoryConfig)?.id) {
                    searchRepoSelector.selectedItem = item
                    break
                }
            }
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

        // Debug: Log combo box state
        val rawSelectedItem = searchRepoSelector.selectedItem
        uiLog.debug("Combo box state - itemCount: ${searchRepoSelector.itemCount}, rawSelectedItem: $rawSelectedItem, type: ${rawSelectedItem?.javaClass?.simpleName ?: "null"}", "Search")

        val selectedRepo = rawSelectedItem as? RepositoryConfig
        val isAllRepos = selectedRepo?.id == ALL_REPOSITORIES_OPTION.id

        uiLog.debug("Selected repo: ${selectedRepo?.name ?: "null"} (id=${selectedRepo?.id}), isAllRepos=$isAllRepos", "Search")

        if (isAllRepos) {
            uiLog.info("Starting search for: '$query' in ALL repositories (${searchableRepos.size} repos)", "Search")
        } else if (selectedRepo != null) {
            uiLog.info("Starting search for: '$query' in ${selectedRepo.name}", "Search")
        } else {
            uiLog.warn("Starting search for: '$query' but no repository selected (combo box may not be initialized)", "Search")
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val packages = when {
                    isAllRepos -> {
                        // Search all repositories and combine results
                        searchAllRepositories(query)
                    }
                    selectedRepo != null -> {
                        searchInRepository(query, selectedRepo)
                    }
                    else -> {
                        // Fallback: If no repo selected but we have searchable repos, search all
                        if (searchableRepos.isNotEmpty()) {
                            uiLog.warn("No repo selected, falling back to searching all ${searchableRepos.size} repos", "Search")
                            searchAllRepositories(query)
                        } else {
                            // Last resort: search Maven Central directly
                            uiLog.warn("No repositories configured, falling back to Maven Central only", "Search")
                            DependencyService.searchFromMavenCentral(query)
                                .map { PackageAdapters.fromDependency(it, PackageSource.MAVEN_CENTRAL) }
                        }
                    }
                }

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

                uiLog.info("Search completed: found ${packages.size} packages, ${mergedPackages.count { it.isInstalled }} installed", "Search")

                ApplicationManager.getApplication().invokeLater {
                    listPanel.setPackages(mergedPackages)
                    listPanel.setLoading(false)
                }
            } catch (e: Exception) {
                log.error("Search failed", e)
                uiLog.error("Search failed: ${e.message}", "Search")
                ApplicationManager.getApplication().invokeLater {
                    listPanel.setLoading(false)
                }
            }
        }
    }

    /**
     * Search all configured repositories and combine results.
     * Tracks which repositories each package is available in.
     */
    private fun searchAllRepositories(query: String): List<UnifiedPackage> {
        // Map of package ID to list of (package, repo) pairs
        val packagesByRepo = mutableMapOf<String, MutableList<Pair<UnifiedPackage, RepositoryConfig>>>()
        val searchedRepos = mutableListOf<String>()

        for (repo in searchableRepos) {
            try {
                uiLog.info("Searching ${repo.name}...", "Search")
                val results = searchInRepository(query, repo)
                if (results.isNotEmpty()) {
                    for (pkg in results) {
                        packagesByRepo.getOrPut(pkg.id) { mutableListOf() }.add(Pair(pkg, repo))
                    }
                    searchedRepos.add("${repo.name}: ${results.size}")
                    uiLog.info("Found ${results.size} packages in ${repo.name}", "Search")
                }
            } catch (e: Exception) {
                uiLog.warn("Failed to search ${repo.name}: ${e.message}", "Search")
            }
        }

        uiLog.info("All repositories search complete: ${searchedRepos.joinToString(", ")}", "Search")

        // Combine packages, tracking all available repositories
        return packagesByRepo.map { (_, packagesWithRepos) ->
            // Use the package with the most info as the base
            val bestPackage = packagesWithRepos.maxByOrNull { (pkg, _) ->
                (pkg.latestVersion?.length ?: 0) + (pkg.description?.length ?: 0)
            }?.first ?: packagesWithRepos.first().first

            // Build list of available repositories
            val availableRepos = packagesWithRepos.map { (pkg, repo) ->
                star.intellijplugin.pkgfinder.gradle.manager.model.AvailableRepository(
                    id = repo.id,
                    name = repo.name,
                    url = repo.url,
                    version = pkg.latestVersion
                )
            }

            bestPackage.copy(availableRepositories = availableRepos)
        }
    }

    /**
     * Search for packages in the specified repository.
     */
    private fun searchInRepository(query: String, repo: RepositoryConfig?): List<UnifiedPackage> {
        if (repo == null) {
            uiLog.warn("No repository selected for search", "Search")
            return emptyList()
        }

        log.info("Searching repository: ${repo.name} (${repo.type}) for query: $query")
        uiLog.info("Searching ${repo.name} (${repo.type}) - URL: ${repo.url}", "Search")

        val hasCredentials = repo.username != null || repo.password != null
        if (hasCredentials) {
            uiLog.debug("Repository has credentials configured", "Search")
        } else {
            uiLog.debug("No credentials configured for this repository", "Search")
        }

        val results = when (repo.type) {
            RepositoryType.MAVEN_CENTRAL -> {
                uiLog.info("Using Maven Central search API", "Search")
                val results = DependencyService.searchFromMavenCentral(query)
                results.map { PackageAdapters.fromDependency(it, PackageSource.MAVEN_CENTRAL) }
            }
            RepositoryType.NEXUS -> {
                uiLog.info("Using Nexus search API", "Search")
                // Use repository-specific credentials if available
                searchNexusRepository(query, repo)
            }
            RepositoryType.AZURE_ARTIFACTS -> {
                uiLog.info("Using Azure Artifacts REST API", "Search")
                searchAzureArtifacts(query, repo)
            }
            RepositoryType.ARTIFACTORY -> {
                uiLog.info("Using Artifactory GAVC search API", "Search")
                searchArtifactoryRepo(query, repo)
            }
            RepositoryType.CUSTOM, RepositoryType.MAVEN, RepositoryType.JITPACK -> {
                uiLog.info("Using generic Maven repository search", "Search")
                searchGenericMavenRepo(query, repo)
            }
            else -> {
                log.warn("Search not supported for repository type: ${repo.type}")
                uiLog.warn("Search not supported for repository type: ${repo.type}", "Search")
                emptyList()
            }
        }

        uiLog.info("Repository search returned ${results.size} results", "Search")
        return results
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

        // Build authentication credentials from repository config
        val auth = buildAuthCredentials(repo)

        // For other repos, try a simple Nexus-style search if available
        val nexusSearchUrl = "$repoUrl/service/rest/v1/search?sort=version&direction=desc&q=$query"
        try {
            uiLog.info("Trying Nexus-style search at: $nexusSearchUrl", "Search")
            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(nexusSearchUrl, auth) { it }
            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Nexus search successful, parsing response...", "Search")
                        return parseNexusSearchResponse(result.data)
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    uiLog.warn("Nexus-style search failed: ${result.exception.message} (code: ${result.responseCode})", "Search")
                    if (result.responseCode == 401 || result.responseCode == 403) {
                        uiLog.error("Authentication failed. Check credentials in Maven settings.xml or gradle.properties", "Search")
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Nexus-style search not available at $repoUrl: ${e.message}")
        }

        log.info("Search not fully supported for repository: ${repo.name}. Try using Maven Central or configure a Nexus repository.")
        uiLog.warn("Search not fully supported for repository: ${repo.name}", "Search")
        return emptyList()
    }

    /**
     * Build authentication credentials from a RepositoryConfig.
     */
    private fun buildAuthCredentials(repo: RepositoryConfig): star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials? {
        return when {
            repo.username != null && repo.password != null -> {
                star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials(
                    username = repo.username,
                    password = repo.password
                )
            }
            repo.password != null -> {
                // PAT-style authentication (empty username with token as password)
                star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials(
                    username = "",
                    password = repo.password
                )
            }
            else -> null
        }
    }

    /**
     * Parse a Nexus search API response.
     */
    private fun parseNexusSearchResponse(response: String): List<UnifiedPackage> {
        val packages = mutableListOf<UnifiedPackage>()
        try {
            // Nexus response format: {"items":[{"group":"...","name":"...","version":"...",...}]}
            val groupPattern = """"group"\s*:\s*"([^"]+)"""".toRegex()
            val namePattern = """"name"\s*:\s*"([^"]+)"""".toRegex()
            val versionPattern = """"version"\s*:\s*"([^"]+)"""".toRegex()

            // Split by items to find individual packages
            val itemsStart = response.indexOf("\"items\"")
            if (itemsStart == -1) return emptyList()

            // Simple extraction of group:name:version tuples
            val itemPattern = """\{[^}]*"group"\s*:\s*"([^"]+)"[^}]*"name"\s*:\s*"([^"]+)"[^}]*"version"\s*:\s*"([^"]+)"[^}]*\}""".toRegex()
            val matches = itemPattern.findAll(response)

            for (match in matches.take(50)) {
                val groupId = match.groupValues[1]
                val artifactId = match.groupValues[2]
                val version = match.groupValues[3]

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
                        source = PackageSource.NEXUS,
                        metadata = star.intellijplugin.pkgfinder.gradle.manager.model.PackageMetadata.MavenMetadata(
                            packaging = "jar",
                            timestamp = null,
                            extensions = emptyList()
                        )
                    )
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse Nexus response: ${e.message}")
        }
        return packages.distinctBy { it.id }
    }

    /**
     * Search a Nexus repository using the repository's own credentials.
     */
    private fun searchNexusRepository(query: String, repo: RepositoryConfig): List<UnifiedPackage> {
        val repoUrl = repo.url.trimEnd('/')
        val auth = buildAuthCredentials(repo)

        val searchUrl = "$repoUrl/service/rest/v1/search?sort=version&direction=desc&q=$query"

        uiLog.info("Nexus: Searching with URL: $searchUrl", "Nexus")
        if (auth != null) {
            uiLog.info("Nexus: Using authentication credentials", "Nexus")
        } else {
            uiLog.warn("Nexus: No credentials configured - request may fail with 401", "Nexus")
        }

        try {
            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }
            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Nexus: Search successful, parsing response...", "Nexus")
                        return parseNexusSearchResponse(result.data)
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    uiLog.error("Nexus: Search failed - ${result.exception.message} (code: ${result.responseCode})", "Nexus")
                    if (result.responseCode == 401 || result.responseCode == 403) {
                        uiLog.error("Nexus: Authentication failed. Check credentials in Maven settings.xml or gradle.properties", "Nexus")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Nexus search failed: ${e.message}")
            uiLog.error("Nexus: Exception - ${e.message}", "Nexus")
        }

        return emptyList()
    }

    /**
     * Search Artifactory repository using Artifactory Query Language (AQL) or GAVC search.
     */
    private fun searchArtifactoryRepo(query: String, repo: RepositoryConfig): List<UnifiedPackage> {
        val repoUrl = repo.url.trimEnd('/')

        // Build authentication credentials from repository config
        val auth = buildAuthCredentials(repo)

        // Try Artifactory GAVC search API
        val searchUrl = "$repoUrl/api/search/gavc?g=*&a=*$query*&repos=${repo.id}"

        uiLog.info("Artifactory: Searching with URL: $searchUrl", "Artifactory")
        if (auth != null) {
            uiLog.info("Artifactory: Using authentication credentials", "Artifactory")
        } else {
            uiLog.warn("Artifactory: No credentials configured - request may fail with 401", "Artifactory")
        }

        try {
            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }
            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Artifactory: Search successful, parsing response...", "Artifactory")
                        return parseArtifactoryResponse(result.data, repo)
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    uiLog.error("Artifactory: Search failed - ${result.exception.message} (code: ${result.responseCode})", "Artifactory")
                    if (result.responseCode == 401 || result.responseCode == 403) {
                        uiLog.error("Artifactory: Authentication failed. Check credentials in Maven settings.xml or gradle.properties", "Artifactory")
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Artifactory search failed: ${e.message}")
            uiLog.error("Artifactory: Exception - ${e.message}", "Artifactory")
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
        uiLog.info("Azure Artifacts: Parsing URL: $repoUrl", "Azure")

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
                uiLog.info("Azure Artifacts: Detected project-scoped feed: org=$org, project=$project, feed=$feed", "Azure")
            } else {
                // Try org-scoped URL
                regex = """pkgs\.dev\.azure\.com/([^/]+)/_packaging/([^/]+)""".toRegex()
                match = regex.find(repoUrl)

                if (match != null) {
                    org = match.groupValues[1]
                    project = null
                    feed = match.groupValues[2]
                    log.info("Detected org-scoped Azure Artifacts: org=$org, feed=$feed")
                    uiLog.info("Azure Artifacts: Detected org-scoped feed: org=$org, feed=$feed", "Azure")
                } else {
                    log.warn("Could not parse Azure Artifacts URL: $repoUrl")
                    uiLog.error("Azure Artifacts: Could not parse URL format. Expected: pkgs.dev.azure.com/{org}/{project?}/_packaging/{feed}/maven/v1", "Azure")
                    return emptyList()
                }
            }

            // Azure DevOps REST API uses feeds.dev.azure.com, NOT pkgs.dev.azure.com
            // pkgs.dev.azure.com is for Maven/NuGet package downloads
            // feeds.dev.azure.com is for the Packaging REST API
            val apiBase = if (project != null) {
                "https://feeds.dev.azure.com/$org/$project/_apis/packaging/feeds/$feed"
            } else {
                "https://feeds.dev.azure.com/$org/_apis/packaging/feeds/$feed"
            }
            uiLog.info("Azure Artifacts: Using API base: $apiBase", "Azure")

            // Build the search URL with proper query parameters
            // includeDescription=true is required to get package descriptions
            // See: https://learn.microsoft.com/en-us/rest/api/azure/devops/artifacts/artifact-details/get-packages
            val baseParams = "api-version=7.1&protocolType=maven&includeDescription=true"
            val searchUrl = if (query.isNotBlank()) {
                "$apiBase/packages?$baseParams&packageNameQuery=$query"
            } else {
                "$apiBase/packages?$baseParams"
            }

            log.info("Azure Artifacts API URL: $searchUrl")
            uiLog.info("Azure Artifacts: API URL: $searchUrl", "Azure")

            // Build authentication credentials - log what we have
            uiLog.info("Azure Artifacts: Repository config - id='${repo.id}', source=${repo.source}", "Azure")
            uiLog.info("Azure Artifacts: Credentials check - username=${repo.username?.let { "'$it'" } ?: "null"}, password=${repo.password?.let { "present (${it.length} chars)" } ?: "null"}", "Azure")

            val auth = if (repo.username != null && repo.password != null) {
                val maskedPw = if (repo.password.length > 4) {
                    "${repo.password.take(2)}${"*".repeat(minOf(repo.password.length - 4, 20))}${repo.password.takeLast(2)}"
                } else "****"
                uiLog.info("Azure Artifacts: Using Basic auth - username='${repo.username}', password=$maskedPw", "Azure")
                star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials(
                    username = repo.username,
                    password = repo.password
                )
            } else if (repo.password != null) {
                // For Azure Artifacts, try using a PAT with empty username (Azure DevOps convention)
                val maskedPw = if (repo.password.length > 4) {
                    "${repo.password.take(2)}${"*".repeat(minOf(repo.password.length - 4, 20))}${repo.password.takeLast(2)}"
                } else "****"
                uiLog.info("Azure Artifacts: Using PAT auth (empty username) - password=$maskedPw", "Azure")
                star.intellijplugin.pkgfinder.util.HttpRequestHelper.AuthCredentials(
                    username = "",
                    password = repo.password
                )
            } else {
                uiLog.error("Azure Artifacts: NO CREDENTIALS FOUND! Check that:", "Azure")
                uiLog.error("  1. Server ID in settings.xml matches repository ID in pom.xml: '${repo.id}'", "Azure")
                uiLog.error("  2. Password is not Maven-encrypted (starts with '{' and ends with '}')", "Azure")
                uiLog.error("  3. settings.xml is at ~/.m2/settings.xml", "Azure")
                null
            }

            uiLog.info("Azure Artifacts: Making HTTP request...", "Azure")

            // Azure DevOps REST API requires Accept header
            val headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            )

            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(searchUrl, auth, headers) { it }

            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        log.info("Azure Artifacts search returned data (${result.data.length} chars)")
                        uiLog.info("Azure Artifacts: Received ${result.data.length} bytes of response data", "Azure")
                        val packages = parseAzureArtifactsResponse(result.data)
                        uiLog.info("Azure Artifacts: Parsed ${packages.size} packages from response", "Azure")
                        return packages
                    } else {
                        log.info("Azure Artifacts search returned empty response")
                        uiLog.warn("Azure Artifacts: Server returned empty response", "Azure")
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    log.warn("Azure Artifacts search failed: ${result.exception.message} (code: ${result.responseCode})")
                    uiLog.error("Azure Artifacts: HTTP request failed - ${result.exception.message} (code: ${result.responseCode})", "Azure")
                    if (result.responseCode == 401 || result.responseCode == 403) {
                        log.warn("Authentication failed for Azure Artifacts. Make sure credentials are configured.")
                        uiLog.error("Azure Artifacts: Authentication failed (${result.responseCode}). Check your PAT token in Maven settings.xml", "Azure")
                    } else if (result.responseCode == 404) {
                        uiLog.error("Azure Artifacts: Feed not found (404). Check the feed name and URL", "Azure")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Azure Artifacts search failed with exception: ${e.message}", e)
            uiLog.error("Azure Artifacts: Exception - ${e.message}", "Azure")
        }

        return emptyList()
    }

    private fun parseAzureArtifactsResponse(response: String): List<UnifiedPackage> {
        val packages = mutableListOf<UnifiedPackage>()
        try {
            // Azure Artifacts response format:
            // {"count":N,"value":[{"id":"...","name":"groupId:artifactId","description":"...","versions":[{"version":"..."}],...}]}
            log.debug("Parsing Azure Artifacts response: ${response.take(500)}...")

            val valueArrayStart = response.indexOf("\"value\"")
            if (valueArrayStart == -1) {
                log.warn("Azure Artifacts response missing 'value' array")
                uiLog.warn("Azure: Response missing 'value' array", "Azure")
                return emptyList()
            }

            // Find package names in format "name":"groupId:artifactId"
            val namePattern = """"name"\s*:\s*"([^":]+):([^"]+)"""".toRegex()
            val nameMatches = namePattern.findAll(response).toList()

            uiLog.info("Azure: Found ${nameMatches.size} package name matches", "Azure")

            for (nameMatch in nameMatches) {
                val groupId = nameMatch.groupValues[1]
                val artifactId = nameMatch.groupValues[2]

                // Find the context around this match to extract version and description
                val matchStart = nameMatch.range.first
                // Look backwards and forwards to find the containing object
                var objStart = matchStart
                var braceCount = 0
                for (i in matchStart downTo 0) {
                    if (response[i] == '}') braceCount++
                    if (response[i] == '{') {
                        if (braceCount == 0) {
                            objStart = i
                            break
                        }
                        braceCount--
                    }
                }

                var objEnd = matchStart
                braceCount = 0
                for (i in matchStart until response.length) {
                    if (response[i] == '{') braceCount++
                    if (response[i] == '}') {
                        if (braceCount == 0) {
                            objEnd = i + 1
                            break
                        }
                        braceCount--
                    }
                }

                val objectJson = response.substring(objStart, minOf(objEnd, response.length))

                // Extract version and description from versions array
                // Note: In Azure DevOps API, description is "packageDescription" INSIDE the version object
                val versionsMatch = """"versions"\s*:\s*\[([^\]]*)\]""".toRegex().find(objectJson)
                val versionsJson = versionsMatch?.groupValues?.get(1) ?: ""

                // Get version number
                val versionMatch = """"version"\s*:\s*"([^"]+)"""".toRegex().find(versionsJson)
                val version = versionMatch?.groupValues?.get(1)

                // Get packageDescription from the version object (not at package level!)
                val descMatch = """"packageDescription"\s*:\s*"([^"]*)"""".toRegex().find(versionsJson)
                val description = descMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

                packages.add(
                    UnifiedPackage(
                        name = artifactId,
                        publisher = groupId,
                        installedVersion = null,
                        latestVersion = version,
                        description = description,
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

                log.debug("Found Azure Artifacts package: $groupId:$artifactId:$version")
            }

            uiLog.info("Azure: Parsed ${packages.size} packages from response", "Azure")
            log.info("Parsed ${packages.size} packages from Azure Artifacts response")
        } catch (e: Exception) {
            log.warn("Failed to parse Azure Artifacts response: ${e.message}", e)
        }
        return packages.distinctBy { it.id }
    }

    private fun performInstall(pkg: UnifiedPackage, version: String, module: String, configuration: String, sourceRepoUrl: String? = null) {
        // Log which repository is being used
        if (sourceRepoUrl != null) {
            uiLog.info("Installing ${pkg.id}:$version from repository: $sourceRepoUrl", "Install")
        } else {
            uiLog.info("Installing ${pkg.id}:$version", "Install")
        }

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
                val versions = when {
                    // If package has available repositories, use the first one
                    pkg.availableRepositories.isNotEmpty() -> {
                        val repo = pkg.availableRepositories.first()
                        val repoConfig = searchableRepos.find { it.url == repo.url || it.id == repo.id }
                        if (repoConfig != null) {
                            fetchVersionsFromRepository(pkg, repoConfig)
                        } else {
                            fetchVersionsFromMavenCentral(pkg)
                        }
                    }
                    // Check source type
                    pkg.source == PackageSource.NEXUS -> {
                        // Try to find matching Nexus/Azure repo
                        val azureRepo = searchableRepos.find { it.type == RepositoryType.AZURE_ARTIFACTS }
                        if (azureRepo != null) {
                            fetchVersionsFromAzure(pkg, azureRepo)
                        } else {
                            fetchVersionsFromMavenCentral(pkg)
                        }
                    }
                    else -> fetchVersionsFromMavenCentral(pkg)
                }

                ApplicationManager.getApplication().invokeLater {
                    callback(versions.ifEmpty { listOfNotNull(pkg.latestVersion) })
                }
            } catch (e: Exception) {
                log.error("Failed to fetch versions", e)
                uiLog.error("Failed to fetch versions: ${e.message}", "Versions")
                ApplicationManager.getApplication().invokeLater {
                    callback(listOfNotNull(pkg.latestVersion))
                }
            }
        }
    }

    /**
     * Fetch versions based on repository type.
     */
    private fun fetchVersionsFromRepository(pkg: UnifiedPackage, repo: RepositoryConfig): List<String> {
        return when (repo.type) {
            RepositoryType.MAVEN_CENTRAL -> fetchVersionsFromMavenCentral(pkg)
            RepositoryType.AZURE_ARTIFACTS -> fetchVersionsFromAzure(pkg, repo)
            RepositoryType.NEXUS -> fetchVersionsFromNexus(pkg, repo)
            else -> fetchVersionsFromMavenCentral(pkg)
        }
    }

    /**
     * Fetch versions from Maven Central.
     */
    private fun fetchVersionsFromMavenCentral(pkg: UnifiedPackage): List<String> {
        uiLog.info("Fetching versions from Maven Central for ${pkg.id}", "Versions")
        val query = "${pkg.publisher}:${pkg.name}"
        val results = DependencyService.searchFromMavenCentral(query)
        return results.map { it.version }.distinct().sortedWith(VersionComparator.reversed())
    }

    /**
     * Fetch versions from Azure Artifacts using Get Package Versions API.
     * https://learn.microsoft.com/en-us/rest/api/azure/devops/artifacts/artifact-details/get-package-versions
     */
    private fun fetchVersionsFromAzure(pkg: UnifiedPackage, repo: RepositoryConfig): List<String> {
        val repoUrl = repo.url.trimEnd('/')
        uiLog.info("Fetching versions from Azure Artifacts for ${pkg.id}", "Versions")

        try {
            // Parse Azure URL to get org/project/feed
            var regex = """pkgs\.dev\.azure\.com/([^/]+)/([^/]+)/_packaging/([^/]+)""".toRegex()
            var match = regex.find(repoUrl)

            val org: String
            val project: String?
            val feed: String

            if (match != null) {
                org = match.groupValues[1]
                project = match.groupValues[2]
                feed = match.groupValues[3]
            } else {
                regex = """pkgs\.dev\.azure\.com/([^/]+)/_packaging/([^/]+)""".toRegex()
                match = regex.find(repoUrl)
                if (match != null) {
                    org = match.groupValues[1]
                    project = null
                    feed = match.groupValues[2]
                } else {
                    return fetchVersionsFromMavenCentral(pkg)
                }
            }

            // Package name in Azure format: groupId:artifactId
            val packageName = "${pkg.publisher}:${pkg.name}"
            val encodedName = java.net.URLEncoder.encode(packageName, "UTF-8")

            val apiBase = if (project != null) {
                "https://feeds.dev.azure.com/$org/$project/_apis/packaging/feeds/$feed"
            } else {
                "https://feeds.dev.azure.com/$org/_apis/packaging/feeds/$feed"
            }

            val versionsUrl = "$apiBase/packages/$encodedName/versions?api-version=7.1"
            uiLog.info("Azure Versions API URL: $versionsUrl", "Versions")

            val auth = buildAuthCredentials(repo)
            val headers = mapOf("Accept" to "application/json")

            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(versionsUrl, auth, headers) { it }

            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        return parseAzureVersionsResponse(result.data)
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    uiLog.warn("Azure versions fetch failed: ${result.exception.message}", "Versions")
                }
            }
        } catch (e: Exception) {
            uiLog.error("Failed to fetch Azure versions: ${e.message}", "Versions")
        }

        return listOfNotNull(pkg.latestVersion)
    }

    /**
     * Parse Azure Get Package Versions response.
     */
    private fun parseAzureVersionsResponse(response: String): List<String> {
        val versions = mutableListOf<String>()
        // Response format: {"count":N,"value":[{"version":"1.0.0","isDeleted":false,"isListed":true,...}]}
        val versionPattern = """"version"\s*:\s*"([^"]+)"""".toRegex()
        val matches = versionPattern.findAll(response)

        for (match in matches) {
            versions.add(match.groupValues[1])
        }

        uiLog.info("Azure: Found ${versions.size} versions", "Versions")
        return versions.sortedWith(VersionComparator.reversed())
    }

    /**
     * Fetch versions from Nexus repository.
     */
    private fun fetchVersionsFromNexus(pkg: UnifiedPackage, repo: RepositoryConfig): List<String> {
        val repoUrl = repo.url.trimEnd('/')
        uiLog.info("Fetching versions from Nexus for ${pkg.id}", "Versions")

        try {
            val searchUrl = "$repoUrl/service/rest/v1/search?sort=version&direction=desc&group=${pkg.publisher}&name=${pkg.name}"
            val auth = buildAuthCredentials(repo)

            val result = star.intellijplugin.pkgfinder.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }

            when (result) {
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        val versionPattern = """"version"\s*:\s*"([^"]+)"""".toRegex()
                        val versions = versionPattern.findAll(result.data)
                            .map { it.groupValues[1] }
                            .distinct()
                            .toList()
                        uiLog.info("Nexus: Found ${versions.size} versions", "Versions")
                        return versions.sortedWith(VersionComparator.reversed())
                    }
                }
                is star.intellijplugin.pkgfinder.util.HttpRequestHelper.RequestResult.Error -> {
                    uiLog.warn("Nexus versions fetch failed: ${result.exception.message}", "Versions")
                }
            }
        } catch (e: Exception) {
            uiLog.error("Failed to fetch Nexus versions: ${e.message}", "Versions")
        }

        return fetchVersionsFromMavenCentral(pkg)
    }

    /**
     * Comparator for semantic versions.
     */
    private object VersionComparator : Comparator<String> {
        override fun compare(v1: String, v2: String): Int {
            val parts1 = v1.split("[.\\-_]".toRegex()).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val parts2 = v2.split("[.\\-_]".toRegex()).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return v1.compareTo(v2)
        }
    }

    private fun showRepositoryManager() {
        RepositoryManagerDialog(project).show()
    }
}
