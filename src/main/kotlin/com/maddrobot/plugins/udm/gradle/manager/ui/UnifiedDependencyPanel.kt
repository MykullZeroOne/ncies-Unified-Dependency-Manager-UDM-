package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.*
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import com.maddrobot.plugins.udm.gradle.manager.model.PackageAdapters
import com.maddrobot.plugins.udm.gradle.manager.model.PackageMetadata
import com.maddrobot.plugins.udm.gradle.manager.model.PackageSource
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.gradle.manager.service.PluginLogService
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryConfig
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryDiscoveryService
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryType
import com.maddrobot.plugins.udm.gradle.manager.service.VulnerabilityIgnoreService
import com.maddrobot.plugins.udm.gradle.manager.service.VulnerabilityService
import com.maddrobot.plugins.udm.maven.DependencyService
import com.maddrobot.plugins.udm.maven.manager.MavenDependencyModifier
import com.maddrobot.plugins.udm.maven.manager.MavenDependencyScanner
import com.maddrobot.plugins.udm.maven.manager.MavenInstalledDependency
import com.maddrobot.plugins.udm.maven.manager.MavenPluginScanner
import com.maddrobot.plugins.udm.maven.manager.MavenInstalledPlugin
import com.maddrobot.plugins.udm.maven.manager.MavenPluginModifier
import com.maddrobot.plugins.udm.maven.manager.MavenPluginUpdateService
import com.maddrobot.plugins.udm.maven.manager.PluginDescriptorService
import com.maddrobot.plugins.udm.setting.PackageFinderSettingState
import com.maddrobot.plugins.udm.npm.NpmRegistryService
import com.maddrobot.plugins.udm.setting.PackageFinderSetting
import com.maddrobot.plugins.udm.maven.MavenRepositorySource
import com.maddrobot.plugins.udm.ui.PackageContextMenuBuilder
import com.maddrobot.plugins.udm.ui.SearchPackagesAction
import com.maddrobot.plugins.udm.ui.ModuleSelectorAction
import com.maddrobot.plugins.udm.ui.FeedSelectorAction
import com.maddrobot.plugins.udm.ui.PrereleaseToggleAction
import com.maddrobot.plugins.udm.ui.RefreshAction
import com.maddrobot.plugins.udm.ui.UpgradeAllAction
import com.maddrobot.plugins.udm.ui.ConsolidateAction
import com.maddrobot.plugins.udm.ui.RepositorySettingsAction
import com.maddrobot.plugins.udm.ui.UdmToolbarActionGroup
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

    // Plugin Scanners and Modifiers
    private val gradlePluginScanner = GradlePluginScanner(project)
    private val gradlePluginModifier = GradlePluginModifier(project)
    private val mavenPluginScanner = MavenPluginScanner(project)
    private val mavenPluginModifier = MavenPluginModifier(project)

    // Vulnerability Service
    private val vulnerabilityService = VulnerabilityService.getInstance(project)
    private val vulnerabilityIgnoreService = VulnerabilityIgnoreService.getInstance(project)

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
    private var availableModules: List<String> = emptyList()

    // Repository selector for search (Source dropdown)
    private var searchableRepos: List<RepositoryConfig> = emptyList()

    // Toolbar Actions
    private val searchAction = SearchPackagesAction { query ->
        if (query.isNotBlank()) {
            performSearch(query)
        }
    }
    private val moduleSelectorAction = ModuleSelectorAction { module ->
        listPanel.setModuleFilter(module)
    }
    private val feedSelectorAction = FeedSelectorAction { feed ->
        // Feed selection will be used when performing search
        uiLog.info("Selected repository feed: ${feed?.name ?: "All Repositories"}", "Toolbar")
    }
    private val prereleaseToggleAction = PrereleaseToggleAction { includePrerelease ->
        uiLog.info("Prerelease filter: $includePrerelease", "Toolbar")
        // TODO: Apply prerelease filter to search/list
    }
    private val refreshAction = RefreshAction {
        if (isGradleProject) {
            gradleDependencyService.refresh()
        }
        if (isMavenProject) {
            refreshMavenDependencies()
        }
    }
    private val upgradeAllAction = UpgradeAllAction(project) {
        showBulkUpgradeDialog()
    }
    private val consolidateAction = ConsolidateAction(project) {
        showConsolidateDialog()
    }
    private val settingsAction = RepositorySettingsAction {
        showRepositoryManager()
    }

    // Legacy module selector for backward compatibility
    private val moduleSelector = JComboBox<String>()

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
            border = JBUI.Borders.empty(4, 4, 4, 4)

            // Create the toolbar action group
            val actionGroup = UdmToolbarActionGroup.create(
                project = project,
                searchAction = searchAction,
                moduleSelectorAction = moduleSelectorAction,
                feedSelectorAction = feedSelectorAction,
                prereleaseToggleAction = prereleaseToggleAction,
                refreshAction = refreshAction,
                upgradeAllAction = upgradeAllAction,
                consolidateAction = consolidateAction,
                settingsAction = settingsAction
            )

            // Create the toolbar
            val toolbar = ActionManager.getInstance().createActionToolbar(
                ActionPlaces.TOOLBAR,
                actionGroup,
                true // horizontal
            )
            toolbar.layoutPolicy = ActionToolbar.AUTO_LAYOUT_POLICY
            toolbar.targetComponent = this

            add(toolbar.component, BorderLayout.CENTER)
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

        // onFilterModeChanged is deprecated - unified view doesn't use filter modes
        // We still connect it to maintain backward compatibility, but it just refreshes the list
        @Suppress("DEPRECATION")
        listPanel.onFilterModeChanged = { _ ->
            loadInstalledPackages()
        }

        listPanel.onSearchRequested = { query ->
            if (query.isNotBlank()) {
                performSearch(query)
            } else {
                // Empty search - reload installed packages
                loadInstalledPackages()
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

        // Provide available modules for install dialog
        detailsPanel.onModulesNeeded = {
            getAvailableModulesForInstall()
        }

        // Vulnerability ignore callback
        detailsPanel.onIgnoreVulnerabilityRequested = { pkg, vulnInfo ->
            handleIgnoreVulnerability(pkg, vulnInfo)
        }

        // Plugin configure callback
        detailsPanel.onConfigurePluginRequested = { pkg ->
            when (pkg.source) {
                PackageSource.MAVEN_PLUGIN_INSTALLED -> showMavenPluginConfigDialog(pkg)
                PackageSource.GRADLE_PLUGIN_INSTALLED -> showGradlePluginConfigDialog(pkg)
                else -> {}
            }
        }

        // Exclusion management callbacks
        detailsPanel.onExclusionAddRequested = { pkg, exclusion ->
            performAddExclusion(pkg, exclusion)
        }

        detailsPanel.onExclusionRemoveRequested = { pkg, exclusion ->
            performRemoveExclusion(pkg, exclusion)
        }

        // Context menu callbacks for list panel (enables keyboard shortcuts and right-click actions)
        listPanel.contextMenuCallbacks = PackageContextMenuBuilder.ContextMenuCallbacks(
            onInstall = { pkg ->
                // Open install dialog
                detailsPanel.setPackage(pkg)
            },
            onUpgrade = { pkg, version ->
                if (version != null) {
                    performUpdate(pkg, version)
                }
            },
            onDowngrade = { pkg ->
                // Show version picker for downgrade
                detailsPanel.setPackage(pkg)
            },
            onRemoveFromModule = { pkg, module ->
                performUninstallFromModule(pkg, module)
            },
            onRemoveFromAll = { pkg ->
                performUninstall(pkg)
            },
            onOpenFolder = { pkg ->
                openPackageFolder(pkg)
            },
            onShowDependencyTree = { pkg ->
                showDependencyTree(pkg)
            },
            onShowDependents = { pkg ->
                showReverseDependents(pkg)
            },
            onWhyInstalled = { pkg ->
                showWhyInstalled(pkg)
            },
            onRemoveSelected = { packages ->
                performBatchUninstall(packages)
            },
            onUpgradeSelected = { packages ->
                performBatchUpgrade(packages)
            },
            onViewAdvisory = { vulnInfo ->
                val url = vulnInfo.advisoryUrl ?: vulnInfo.cveId?.let { "https://nvd.nist.gov/vuln/detail/$it" }
                if (url != null) {
                    BrowserUtil.browse(url)
                }
            },
            onManageExclusions = { pkg ->
                // Open exclusion dialog directly
                val dialog = ExclusionDialog(project, pkg.id)
                if (dialog.showAndGet()) {
                    performAddExclusion(pkg, dialog.getExclusion())
                }
            }
        )
    }

    /**
     * Get available modules for dependency installation.
     * For Gradle projects: module names from build files
     * For Maven projects: module names from pom.xml parent folders
     */
    private fun getAvailableModulesForInstall(): List<String> {
        val modules = mutableListOf<String>()

        if (isGradleProject) {
            modules.addAll(gradleScanner.getModuleBuildFiles().keys)
        }

        if (isMavenProject) {
            modules.addAll(mavenScanner.getModulePomFiles().keys)
        }

        return modules.distinct().sorted().ifEmpty { listOf("root") }
    }

    private fun setupMessageBusSubscription() {
        project.messageBus.connect(parentDisposable).subscribe(
            GradleDependencyManagerService.DEPENDENCY_CHANGE_TOPIC,
            object : GradleDependencyManagerService.DependencyChangeListener {
                override fun onDependenciesChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        updateModuleSelector()
                        loadInstalledPackages()
                    }
                }
            }
        )
    }

    private fun loadInitialData() {
        // All initial data loading involves file system I/O (repository discovery,
        // dependency scanning, etc.) and must run off the EDT to avoid SlowOperations errors.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Populate search repository selector (reads gradle.properties, settings.xml, etc.)
                val repos = repoDiscoveryService.getConfiguredRepositories()
                    .filter { it.type != RepositoryType.NPM && it.type != RepositoryType.GRADLE_PLUGIN_PORTAL }
                    .filter { it.enabled }

                // Detect project types (reads file system)
                val isGradle = isGradleProject
                val isMaven = isMavenProject

                ApplicationManager.getApplication().invokeLater {
                    // Update UI components on EDT
                    searchableRepos = repos
                    uiLog.info("Populating feed selector with ${repos.size} repositories", "Init")
                    feedSelectorAction.setFeeds(repos)

                    for (repo in repos) {
                        val hasAuth = repo.username != null || repo.password != null
                        val authInfo = if (hasAuth) {
                            "HAS CREDENTIALS (user=${repo.username ?: "null"}, pw=${repo.password?.let { "${it.length} chars" } ?: "null"})"
                        } else {
                            "NO CREDENTIALS"
                        }
                        uiLog.info("Repository: ${repo.name} | id='${repo.id}' | type=${repo.type} | source=${repo.source} | $authInfo", "Init")
                    }

                    uiLog.info("Feed selector initialized with ${repos.size} repositories", "Init")
                }

                // Trigger dependency refreshes (these already handle their own threading)
                if (isGradle) {
                    gradleDependencyService.refresh()
                }
                if (isMaven) {
                    refreshMavenDependencies()
                }

                // Load any already-cached installed packages
                ApplicationManager.getApplication().invokeLater {
                    loadInstalledPackages()
                }
            } catch (e: Exception) {
                log.error("Failed to load initial data", e)
                uiLog.error("Failed to load initial data: ${e.message}", "Init")
            }
        }
    }

    /**
     * Populate the search repository selector.
     * Must be called from a background thread since it performs file system I/O.
     * UI updates are dispatched to the EDT internally.
     */
    private fun populateSearchRepoSelector() {
        // Get all searchable repositories (exclude NPM and Gradle Plugin Portal for now)
        // This reads gradle.properties, settings.xml, etc. — must be off EDT
        val repos = repoDiscoveryService.getConfiguredRepositories()
            .filter { it.type != RepositoryType.NPM && it.type != RepositoryType.GRADLE_PLUGIN_PORTAL }
            .filter { it.enabled }

        ApplicationManager.getApplication().invokeLater {
            searchableRepos = repos
            uiLog.info("Populating feed selector with ${repos.size} repositories", "Init")

            // Update the feed selector action with available repositories
            feedSelectorAction.setFeeds(repos)

            for (repo in repos) {
                val hasAuth = repo.username != null || repo.password != null
                val authInfo = if (hasAuth) {
                    "HAS CREDENTIALS (user=${repo.username ?: "null"}, pw=${repo.password?.let { "${it.length} chars" } ?: "null"})"
                } else {
                    "NO CREDENTIALS"
                }
                uiLog.info("Repository: ${repo.name} | id='${repo.id}' | type=${repo.type} | source=${repo.source} | $authInfo", "Init")
            }

            uiLog.info("Feed selector initialized with ${repos.size} repositories", "Init")
        }
    }

    /**
     * Refresh the repository selector (call this after adding new repositories).
     */
    fun refreshRepositories() {
        ApplicationManager.getApplication().executeOnPooledThread {
            populateSearchRepoSelector()
        }
    }

    private fun refreshMavenDependencies() {
        uiLog.info("Maven Refresh: Starting dependency refresh...", "Refresh")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Refresh the VFS to pick up any external changes to pom.xml files
                val basePath = project.basePath
                if (basePath != null) {
                    val projectRoot = LocalFileSystem.getInstance().findFileByPath(basePath)
                    if (projectRoot != null) {
                        uiLog.debug("Maven Refresh: Refreshing VFS for project root", "Refresh")
                        // Refresh synchronously to ensure we read the latest file contents
                        projectRoot.refresh(false, true)
                    }
                }

                val previousCount = mavenInstalledDependencies.size
                mavenInstalledDependencies = mavenScanner.scanInstalledDependencies()
                val newCount = mavenInstalledDependencies.size

                uiLog.info("Maven Refresh: Found $newCount dependencies (was $previousCount)", "Refresh")

                if (newCount != previousCount) {
                    uiLog.info("Maven Refresh: Dependency count changed from $previousCount to $newCount", "Refresh")
                }

                ApplicationManager.getApplication().invokeLater {
                    updateModuleSelector()
                    loadInstalledPackages()
                    uiLog.info("Maven Refresh: UI updated", "Refresh")
                }
            } catch (e: Exception) {
                uiLog.error("Maven Refresh: Failed - ${e.message}", "Refresh")
                log.error("Failed to refresh Maven dependencies", e)
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

        // Update toolbar module selector action
        moduleSelectorAction.setModules(availableModules)

        // Update upgrade/consolidate action states based on dependencies
        updateToolbarActionStates()
    }

    /**
     * Update toolbar action states based on current dependency data.
     */
    private fun updateToolbarActionStates() {
        // Check if there are packages with updates
        val hasUpdates = if (isGradleProject) {
            gradleDependencyService.dependencyUpdates.isNotEmpty()
        } else false
        upgradeAllAction.setHasUpdates(hasUpdates)

        // Check for inconsistent versions across modules
        val allPackages = getAllInstalledPackages()
        val inconsistentPackages = ConsolidateVersionsDialog.findInconsistentPackages(allPackages)
        consolidateAction.setHasInconsistentVersions(inconsistentPackages.isNotEmpty())
    }

    /**
     * Load all installed packages into the unified list.
     * This is called on initial load and after refreshes.
     * Also triggers vulnerability checking if enabled.
     */
    private fun loadInstalledPackages() {
        val packages = getAllInstalledPackages()
        listPanel.setPackages(packages)

        // Check for vulnerabilities asynchronously if enabled
        val settings = PackageFinderSettingState.getInstance()
        if (settings.enableVulnerabilityScanning && settings.vulnerabilityScanOnLoad) {
            checkVulnerabilitiesAsync(packages)
        }
    }

    private fun getAllInstalledPackages(): List<UnifiedPackage> {
        val packages = mutableListOf<UnifiedPackage>()

        // Add Gradle dependencies
        if (isGradleProject) {
            packages.addAll(
                PackageAdapters.aggregateByPackage(
                    gradleDependencyService.installedDependencies,
                    gradleDependencyService.dependencyUpdates
                )
            )

            // Add Gradle plugins (scan separately from update check for resilience)
            try {
                val gradlePlugins = gradlePluginScanner.scanInstalledPlugins()
                uiLog.debug("Found ${gradlePlugins.size} Gradle plugins", "Plugins")

                val pluginUpdates = try {
                    GradlePluginUpdateService.checkForUpdates(gradlePlugins)
                } catch (e: Exception) {
                    log.warn("Failed to check Gradle plugin updates", e)
                    emptyList()
                }

                packages.addAll(
                    PackageAdapters.aggregatePluginsByPackage(gradlePlugins, pluginUpdates)
                )
            } catch (e: Exception) {
                log.warn("Failed to scan Gradle plugins", e)
            }
        }

        // Add Maven dependencies
        if (isMavenProject) {
            uiLog.debug("Maven project detected, scanning dependencies and plugins...", "Plugins")

            // Check for updates on Maven dependencies
            val mavenDependencyVersions = try {
                mavenInstalledDependencies.associate { dep ->
                    val latestVersion = MavenPluginUpdateService.getLatestVersion(dep.groupId, dep.artifactId)
                    dep.id to latestVersion
                }.filterValues { it != null }.mapValues { it.value!! }
            } catch (e: Exception) {
                log.warn("Failed to check Maven dependency updates", e)
                emptyMap()
            }

            packages.addAll(
                PackageAdapters.aggregateMavenByPackage(mavenInstalledDependencies, mavenDependencyVersions)
            )

            // Add Maven plugins (scan separately from update check for resilience)
            try {
                val mavenPlugins = mavenPluginScanner.scanInstalledPlugins()
                uiLog.debug("Found ${mavenPlugins.size} Maven plugins", "Plugins")

                val pluginUpdates = try {
                    MavenPluginUpdateService.checkForUpdates(mavenPlugins)
                } catch (e: Exception) {
                    log.warn("Failed to check Maven plugin updates", e)
                    emptyList()
                }

                packages.addAll(
                    PackageAdapters.aggregateMavenPluginsByPackage(mavenPlugins, pluginUpdates)
                )
            } catch (e: Exception) {
                log.warn("Failed to scan Maven plugins", e)
            }
        } else {
            uiLog.debug("Not a Maven project (no root pom.xml found)", "Plugins")
        }

        return packages
    }

    /**
     * Check vulnerabilities for installed packages asynchronously.
     * Updates the package list when vulnerabilities are found.
     */
    private fun checkVulnerabilitiesAsync(packages: List<UnifiedPackage>) {
        val settings = PackageFinderSettingState.getInstance()
        if (!settings.enableVulnerabilityScanning) {
            return
        }

        // Build list of dependencies to check (groupId, artifactId, version)
        val depsToCheck = packages
            .filter { it.isInstalled && it.installedVersion != null }
            .map { Triple(it.publisher, it.name, it.installedVersion!!) }

        if (depsToCheck.isEmpty()) return

        uiLog.info("Checking vulnerabilities for ${depsToCheck.size} packages...", "Vulnerability")

        vulnerabilityService.batchCheck(depsToCheck) { results ->
            if (results.isEmpty()) {
                uiLog.info("No vulnerabilities found", "Vulnerability")
                return@batchCheck
            }

            // Count vulnerable packages
            val vulnerableCount = results.count { it.value.isNotEmpty() }
            uiLog.info("Found $vulnerableCount packages with vulnerabilities", "Vulnerability")

            // Update packages with vulnerability info
            // Prefer the most severe vulnerability that has a fix version;
            // fall back to the most severe vulnerability overall
            // Skip packages where the vulnerability has been explicitly ignored via build file comment
            val updatedPackages = packages.map { pkg ->
                // Check if vulnerability is ignored via comment in build file
                if (vulnerabilityIgnoreService.isPackageVulnerabilityIgnored(pkg)) {
                    return@map pkg // Keep as-is (no vulnerability info)
                }

                val vulns = results["${pkg.publisher}:${pkg.name}"]
                if (!vulns.isNullOrEmpty()) {
                    val severityScore = { v: com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilityInfo ->
                        when (v.severity) {
                            com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilitySeverity.CRITICAL -> 4
                            com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilitySeverity.HIGH -> 3
                            com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilitySeverity.MEDIUM -> 2
                            com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilitySeverity.LOW -> 1
                            else -> 0
                        }
                    }
                    // Prefer vulnerabilities with fix versions (so the UI can suggest the fix)
                    val withFix = vulns.filter { it.fixedVersion != null }
                    val bestVuln = if (withFix.isNotEmpty()) {
                        withFix.maxByOrNull(severityScore)
                    } else {
                        vulns.maxByOrNull(severityScore)
                    }
                    pkg.copy(vulnerabilityInfo = bestVuln)
                } else {
                    pkg
                }
            }

            listPanel.setPackages(updatedPackages)
        }
    }

    /**
     * Handle the "Ignore Vulnerability" action from the details panel.
     * Shows a confirmation dialog, then inserts the ignore comment in the build file.
     */
    private fun handleIgnoreVulnerability(pkg: UnifiedPackage, vulnInfo: com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilityInfo) {
        val dialog = VulnerabilityIgnoreDialog(project, pkg, vulnInfo)
        if (dialog.showAndGet()) {
            val reason = dialog.reason
            val success = vulnerabilityIgnoreService.ignoreVulnerability(pkg, vulnInfo, reason)
            if (success) {
                uiLog.info("Ignored vulnerability ${vulnInfo.cveId ?: "unknown"} for ${pkg.id}: $reason", "Vulnerability")

                // Remove the vulnerability from the package in the list and refresh the details panel
                val updatedPkg = pkg.copy(vulnerabilityInfo = null)
                listPanel.updatePackage(updatedPkg)
                detailsPanel.setPackage(updatedPkg)
            } else {
                uiLog.error("Failed to ignore vulnerability for ${pkg.id}", "Vulnerability")
            }
        }
    }

    private fun performSearch(query: String) {
        listPanel.setLoading(true)

        // Get selected repository from the toolbar feed selector
        val selectedRepo = feedSelectorAction.getSelectedFeed()
        val isAllRepos = selectedRepo == null // null means "All Repositories"

        uiLog.debug("Selected repo: ${selectedRepo?.name ?: "All Repositories"}, isAllRepos=$isAllRepos", "Search")

        if (isAllRepos) {
            uiLog.info("Starting search for: '$query' in ALL repositories (${searchableRepos.size} repos)", "Search")
        } else {
            uiLog.info("Starting search for: '$query' in ${selectedRepo?.name}", "Search")
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val searchResults = when {
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

                // Get all installed packages
                val installedPackages = getAllInstalledPackages()
                val installedIds = installedPackages.map { it.id }.toSet()

                // Filter installed packages by the search query
                val queryLower = query.lowercase()
                val filteredInstalled = installedPackages.filter { pkg ->
                    pkg.name.lowercase().contains(queryLower) ||
                        pkg.publisher.lowercase().contains(queryLower) ||
                        pkg.id.lowercase().contains(queryLower) ||
                        (pkg.description?.lowercase()?.contains(queryLower) == true)
                }

                // Mark search results with installed status and filter out duplicates
                val gradleInstalled = if (isGradleProject) gradleDependencyService.installedDependencies else emptyList()
                val mavenInstalled = if (isMavenProject) mavenInstalledDependencies else emptyList()

                val availablePackages = searchResults
                    .filter { pkg -> pkg.id !in installedIds } // Only packages NOT already installed
                    .map { pkg ->
                        // Check if it's actually installed (for proper status)
                        val gradleDep = gradleInstalled.find { it.id == pkg.id }
                        if (gradleDep != null) {
                            val modules = gradleInstalled.filter { it.id == pkg.id }.map { it.moduleName }
                            return@map PackageAdapters.mergeWithInstalled(pkg, gradleDep, modules)
                        }

                        val mavenDep = mavenInstalled.find { it.id == pkg.id }
                        if (mavenDep != null) {
                            val modules = mavenInstalled.filter { it.id == pkg.id }.map { it.moduleName }
                            return@map PackageAdapters.mergeWithMavenInstalled(pkg, mavenDep, modules)
                        }

                        pkg
                    }

                // Combine: filtered installed packages + available (not installed) search results
                // The list panel will organize these into sections (Installed, Transitive, Available)
                val combinedPackages = filteredInstalled + availablePackages

                uiLog.info("Search completed: ${filteredInstalled.size} installed matches, ${availablePackages.size} available packages", "Search")

                ApplicationManager.getApplication().invokeLater {
                    listPanel.setPackages(combinedPackages)
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
                com.maddrobot.plugins.udm.gradle.manager.model.AvailableRepository(
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
            val result = com.maddrobot.plugins.udm.util.HttpRequestHelper.getForObject(nexusSearchUrl, auth) { it }
            when (result) {
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Nexus search successful, parsing response...", "Search")
                        return parseNexusSearchResponse(result.data)
                    }
                }
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Error -> {
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
    private fun buildAuthCredentials(repo: RepositoryConfig): com.maddrobot.plugins.udm.util.HttpRequestHelper.AuthCredentials? {
        return when {
            repo.username != null && repo.password != null -> {
                com.maddrobot.plugins.udm.util.HttpRequestHelper.AuthCredentials(
                    username = repo.username,
                    password = repo.password
                )
            }
            repo.password != null -> {
                // PAT-style authentication (empty username with token as password)
                com.maddrobot.plugins.udm.util.HttpRequestHelper.AuthCredentials(
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
                        metadata = com.maddrobot.plugins.udm.gradle.manager.model.PackageMetadata.MavenMetadata(
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
            val result = com.maddrobot.plugins.udm.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }
            when (result) {
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Nexus: Search successful, parsing response...", "Nexus")
                        return parseNexusSearchResponse(result.data)
                    }
                }
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Error -> {
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
            val result = com.maddrobot.plugins.udm.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }
            when (result) {
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Artifactory: Search successful, parsing response...", "Artifactory")
                        return parseArtifactoryResponse(result.data, repo)
                    }
                }
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Error -> {
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
                                metadata = com.maddrobot.plugins.udm.gradle.manager.model.PackageMetadata.MavenMetadata(
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
                com.maddrobot.plugins.udm.util.HttpRequestHelper.AuthCredentials(
                    username = repo.username,
                    password = repo.password
                )
            } else if (repo.password != null) {
                // For Azure Artifacts, try using a PAT with empty username (Azure DevOps convention)
                val maskedPw = if (repo.password.length > 4) {
                    "${repo.password.take(2)}${"*".repeat(minOf(repo.password.length - 4, 20))}${repo.password.takeLast(2)}"
                } else "****"
                uiLog.info("Azure Artifacts: Using PAT auth (empty username) - password=$maskedPw", "Azure")
                com.maddrobot.plugins.udm.util.HttpRequestHelper.AuthCredentials(
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

            val result = com.maddrobot.plugins.udm.util.HttpRequestHelper.getForObject(searchUrl, auth, headers) { it }

            when (result) {
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Success -> {
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
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Error -> {
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
                        metadata = com.maddrobot.plugins.udm.gradle.manager.model.PackageMetadata.MavenMetadata(
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

    /**
     * Conditionally show preview diff or apply changes immediately based on user settings.
     */
    private fun applyWithOptionalPreview(
        filePath: String,
        originalContent: String,
        newContent: String,
        commandName: String,
        applyAction: () -> Unit
    ) {
        val showPreview = PackageFinderSetting.instance.showPreviewBeforeChanges
        if (showPreview) {
            val dialog = PreviewDiffDialog(project, filePath, originalContent, newContent)
            if (dialog.showAndGet()) {
                applyAction()
            }
        } else {
            applyAction()
        }
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
        uiLog.info("Gradle Install: Starting install of ${pkg.id}:$version to module '$module' with config '$configuration'", "Install")

        val buildFiles = gradleScanner.getModuleBuildFiles()
        uiLog.debug("Gradle Install: Available modules: ${buildFiles.keys.joinToString(", ")}", "Install")

        val buildFile = buildFiles[module]?.path
        if (buildFile == null) {
            uiLog.error("Gradle Install: Could not find build file for module '$module'. Available modules: ${buildFiles.keys.joinToString(", ")}", "Install")
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Could not find build file for module '$module'.\nAvailable modules: ${buildFiles.keys.joinToString(", ")}",
                    "Install Failed"
                )
            }
            return
        }

        uiLog.info("Gradle Install: Found build file at $buildFile", "Install")

        val newContent = gradleModifier.getAddedContent(buildFile, pkg.publisher, pkg.name, version, configuration)
        if (newContent != null) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFile)
            val originalContent = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text } ?: ""

            uiLog.info("Gradle Install: Applying changes to $buildFile", "Install")
            applyWithOptionalPreview(buildFile, originalContent, newContent, "Add Dependency: ${pkg.id}") {
                gradleModifier.applyChanges(buildFile, newContent, "Add Dependency: ${pkg.id}")
                refresh()
                uiLog.info("Gradle Install: Successfully added ${pkg.id}:$version to $module", "Install")
            }
        } else {
            uiLog.error("Gradle Install: Failed to generate new build file content", "Install")
        }
    }

    private fun performMavenInstall(pkg: UnifiedPackage, version: String, module: String, scope: String) {
        uiLog.info("Maven Install: Starting install of ${pkg.id}:$version to module '$module' with scope '$scope'", "Install")

        val pomFiles = mavenScanner.getModulePomFiles()
        uiLog.debug("Maven Install: Available modules: ${pomFiles.keys.joinToString(", ")}", "Install")

        val pomFile = pomFiles[module]?.path
        if (pomFile == null) {
            uiLog.error("Maven Install: Could not find pom.xml for module '$module'. Available modules: ${pomFiles.keys.joinToString(", ")}", "Install")
            // Show error to user
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Could not find pom.xml for module '$module'.\nAvailable modules: ${pomFiles.keys.joinToString(", ")}",
                    "Install Failed"
                )
            }
            return
        }

        uiLog.info("Maven Install: Found pom.xml at $pomFile", "Install")

        // Map Gradle configuration to Maven scope
        val mavenScope = mapGradleConfigToMavenScope(scope)
        uiLog.debug("Maven Install: Mapped scope '$scope' to Maven scope '$mavenScope'", "Install")

        val newContent = mavenModifier.getAddedContent(pomFile, pkg.publisher, pkg.name, version, mavenScope)
        if (newContent != null) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomFile)
            val originalContent = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text } ?: ""

            uiLog.info("Maven Install: Applying changes to $pomFile", "Install")
            applyWithOptionalPreview(pomFile, originalContent, newContent, "Add Dependency: ${pkg.id}") {
                mavenModifier.applyChanges(pomFile, newContent, "Add Dependency: ${pkg.id}")
                refresh()
                uiLog.info("Maven Install: Successfully added ${pkg.id}:$version to $module", "Install")
            }
        } else {
            uiLog.error("Maven Install: Failed to generate new pom.xml content", "Install")
        }
    }

    /**
     * Map Gradle configuration names to Maven scope names.
     */
    private fun mapGradleConfigToMavenScope(config: String): String? {
        return when (config.lowercase()) {
            "implementation", "api", "compile" -> null // compile is default in Maven
            "runtimeonly", "runtime" -> "runtime"
            "testimplementation", "testcompile", "testruntimeonly" -> "test"
            "compileonly", "provided" -> "provided"
            else -> null // Default to compile scope
        }
    }

    private fun performUpdate(pkg: UnifiedPackage, newVersion: String) {
        val metadata = pkg.metadata
        uiLog.info("Update: ${pkg.id} to version $newVersion, metadata type: ${metadata::class.simpleName}", "Update")

        when (metadata) {
            is PackageMetadata.GradleMetadata -> performGradleUpdate(pkg, newVersion)
            is PackageMetadata.MavenInstalledMetadata -> performMavenUpdate(pkg, newVersion)
            is PackageMetadata.GradlePluginMetadata -> performGradlePluginUpdate(pkg, metadata, newVersion)
            is PackageMetadata.MavenPluginMetadata -> performMavenPluginUpdate(pkg, metadata, newVersion)
            else -> {
                uiLog.warn("Update: Cannot update package with metadata type: ${metadata::class.simpleName}", "Update")
            }
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
                applyWithOptionalPreview(installed.buildFile, originalContent, newContent, "Update Dependency: ${pkg.id}") {
                    gradleModifier.applyChanges(installed, newContent, "Update Dependency: ${pkg.id}")
                    refresh()
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
                applyWithOptionalPreview(installed.pomFile, originalContent, newContent, "Update Dependency: ${pkg.id}") {
                    mavenModifier.applyChanges(installed, newContent, "Update Dependency: ${pkg.id}")
                    refresh()
                }
            }
        }
    }

    private fun performGradlePluginUpdate(pkg: UnifiedPackage, metadata: PackageMetadata.GradlePluginMetadata, newVersion: String) {
        // Reconstruct InstalledPlugin from metadata
        val installedPlugin = InstalledPlugin(
            pluginId = pkg.name,
            version = pkg.installedVersion,
            moduleName = pkg.modules.firstOrNull() ?: "root",
            buildFile = metadata.buildFile,
            offset = metadata.offset,
            length = metadata.length,
            pluginSyntax = metadata.pluginSyntax,
            isKotlinShorthand = metadata.isKotlinShorthand,
            isApplied = metadata.isApplied
        )

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(metadata.buildFile)
        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null) {
            val originalContent = document.text
            val newContent = gradlePluginModifier.getUpdatedContent(installedPlugin, newVersion)
            if (newContent != null) {
                applyWithOptionalPreview(metadata.buildFile, originalContent, newContent, "Update Plugin: ${pkg.id}") {
                    gradlePluginModifier.applyChanges(metadata.buildFile, newContent, "Update Plugin: ${pkg.id}")
                    refresh()
                }
            } else {
                uiLog.warn("Update: Could not generate updated content for plugin ${pkg.id}", "Update")
            }
        }
    }

    private fun performMavenPluginUpdate(pkg: UnifiedPackage, metadata: PackageMetadata.MavenPluginMetadata, newVersion: String) {
        // Reconstruct MavenInstalledPlugin from metadata
        val installedPlugin = MavenInstalledPlugin(
            groupId = pkg.publisher,
            artifactId = pkg.name,
            version = pkg.installedVersion,
            moduleName = pkg.modules.firstOrNull() ?: "root",
            pomFile = metadata.pomFile,
            offset = metadata.offset,
            length = metadata.length,
            phase = metadata.phase,
            goals = metadata.goals,
            inherited = metadata.inherited,
            isFromPluginManagement = metadata.isFromPluginManagement
        )

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(metadata.pomFile)
        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null) {
            val originalContent = document.text
            val newContent = mavenPluginModifier.getUpdatedContent(installedPlugin, newVersion)
            if (newContent != null) {
                applyWithOptionalPreview(metadata.pomFile, originalContent, newContent, "Update Plugin: ${pkg.id}") {
                    mavenPluginModifier.applyChanges(installedPlugin, newContent, "Update Plugin: ${pkg.id}")
                    refresh()
                }
            } else {
                uiLog.warn("Update: Could not generate updated content for Maven plugin ${pkg.id}", "Update")
            }
        }
    }

    // ========== Exclusion Management ==========

    /**
     * Add an exclusion to a dependency.
     */
    private fun performAddExclusion(pkg: UnifiedPackage, exclusion: DependencyExclusion) {
        val metadata = pkg.metadata

        uiLog.info("AddExclusion: Adding ${exclusion.id} to ${pkg.id}", "Exclusion")

        when (metadata) {
            is PackageMetadata.GradleMetadata -> {
                val installed = gradleDependencyService.installedDependencies.find {
                    it.groupId == pkg.publisher && it.artifactId == pkg.name
                }
                if (installed == null) {
                    uiLog.error("AddExclusion: Could not find ${pkg.id} in installed dependencies", "Exclusion")
                    return
                }

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile) ?: return
                virtualFile.refresh(false, false)
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
                val originalContent = document.text
                val newContent = gradleModifier.getContentWithExclusionAdded(installed, exclusion)
                if (newContent == null) {
                    uiLog.error("AddExclusion: Failed to generate content with exclusion", "Exclusion")
                    return
                }

                applyWithOptionalPreview(installed.buildFile, originalContent, newContent, "Add Exclusion: ${exclusion.id}") {
                    gradleModifier.applyChanges(installed, newContent, "Add Exclusion: ${exclusion.id}")
                    refresh()
                    uiLog.info("AddExclusion: Successfully added ${exclusion.id} to ${pkg.id}", "Exclusion")
                }
            }
            is PackageMetadata.MavenInstalledMetadata -> {
                val installed = mavenInstalledDependencies.find {
                    it.groupId == pkg.publisher && it.artifactId == pkg.name
                }
                if (installed == null) {
                    uiLog.error("AddExclusion: Could not find ${pkg.id} in Maven installed dependencies", "Exclusion")
                    return
                }

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.pomFile) ?: return
                virtualFile.refresh(false, false)
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
                val originalContent = document.text
                val newContent = mavenModifier.getContentWithExclusionAdded(installed, exclusion)
                if (newContent == null) {
                    uiLog.error("AddExclusion: Failed to generate content with exclusion", "Exclusion")
                    return
                }

                applyWithOptionalPreview(installed.pomFile, originalContent, newContent, "Add Exclusion: ${exclusion.id}") {
                    mavenModifier.applyChanges(installed, newContent, "Add Exclusion: ${exclusion.id}")
                    refresh()
                    uiLog.info("AddExclusion: Successfully added ${exclusion.id} to ${pkg.id}", "Exclusion")
                }
            }
            else -> {
                uiLog.error("AddExclusion: Unsupported metadata type: ${metadata::class.simpleName}", "Exclusion")
            }
        }
    }

    /**
     * Remove an exclusion from a dependency.
     */
    private fun performRemoveExclusion(pkg: UnifiedPackage, exclusion: DependencyExclusion) {
        val metadata = pkg.metadata

        uiLog.info("RemoveExclusion: Removing ${exclusion.id} from ${pkg.id}", "Exclusion")

        when (metadata) {
            is PackageMetadata.GradleMetadata -> {
                val installed = gradleDependencyService.installedDependencies.find {
                    it.groupId == pkg.publisher && it.artifactId == pkg.name
                }
                if (installed == null) {
                    uiLog.error("RemoveExclusion: Could not find ${pkg.id} in installed dependencies", "Exclusion")
                    return
                }

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile) ?: return
                virtualFile.refresh(false, false)
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
                val originalContent = document.text
                val newContent = gradleModifier.getContentWithExclusionRemoved(installed, exclusion)
                if (newContent == null) {
                    uiLog.error("RemoveExclusion: Failed to generate content without exclusion", "Exclusion")
                    return
                }

                applyWithOptionalPreview(installed.buildFile, originalContent, newContent, "Remove Exclusion: ${exclusion.id}") {
                    gradleModifier.applyChanges(installed, newContent, "Remove Exclusion: ${exclusion.id}")
                    refresh()
                    uiLog.info("RemoveExclusion: Successfully removed ${exclusion.id} from ${pkg.id}", "Exclusion")
                }
            }
            is PackageMetadata.MavenInstalledMetadata -> {
                val installed = mavenInstalledDependencies.find {
                    it.groupId == pkg.publisher && it.artifactId == pkg.name
                }
                if (installed == null) {
                    uiLog.error("RemoveExclusion: Could not find ${pkg.id} in Maven installed dependencies", "Exclusion")
                    return
                }

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.pomFile) ?: return
                virtualFile.refresh(false, false)
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
                val originalContent = document.text
                val newContent = mavenModifier.getContentWithExclusionRemoved(installed, exclusion)
                if (newContent == null) {
                    uiLog.error("RemoveExclusion: Failed to generate content without exclusion", "Exclusion")
                    return
                }

                applyWithOptionalPreview(installed.pomFile, originalContent, newContent, "Remove Exclusion: ${exclusion.id}") {
                    mavenModifier.applyChanges(installed, newContent, "Remove Exclusion: ${exclusion.id}")
                    refresh()
                    uiLog.info("RemoveExclusion: Successfully removed ${exclusion.id} from ${pkg.id}", "Exclusion")
                }
            }
            else -> {
                uiLog.error("RemoveExclusion: Unsupported metadata type: ${metadata::class.simpleName}", "Exclusion")
            }
        }
    }

    private fun performUninstall(pkg: UnifiedPackage) {
        val metadata = pkg.metadata

        uiLog.info("Uninstall: Package ${pkg.id}, metadata type: ${metadata::class.simpleName}, source: ${pkg.source}", "Uninstall")

        when (metadata) {
            is PackageMetadata.GradleMetadata -> performGradleUninstall(pkg)
            is PackageMetadata.MavenInstalledMetadata -> performMavenUninstall(pkg)
            else -> {
                uiLog.error("Uninstall: Cannot uninstall package with metadata type: ${metadata::class.simpleName}", "Uninstall")
                log.warn("Cannot uninstall package with metadata type: ${metadata::class.simpleName}")

                // Show error to user
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        "Cannot uninstall this package.\nMetadata type ${metadata::class.simpleName} is not supported.\nThis may be a search result that wasn't properly merged with installed info.",
                        "Uninstall Failed"
                    )
                }
            }
        }
    }

    private fun performGradleUninstall(pkg: UnifiedPackage) {
        uiLog.info("Gradle Uninstall: Starting uninstall of ${pkg.id}", "Uninstall")

        // Find the installed dependency to remove
        val installed = gradleDependencyService.installedDependencies.find {
            it.groupId == pkg.publisher && it.artifactId == pkg.name
        }

        if (installed == null) {
            uiLog.error("Gradle Uninstall: Could not find ${pkg.id} in installed dependencies", "Uninstall")
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Could not find dependency ${pkg.id} in installed dependencies.\nTry refreshing the dependency list first.",
                    "Uninstall Failed"
                )
            }
            return
        }

        uiLog.info("Gradle Uninstall: Found dependency in ${installed.buildFile}", "Uninstall")

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile)
        if (virtualFile == null) {
            uiLog.error("Gradle Uninstall: Could not find file ${installed.buildFile}", "Uninstall")
            return
        }

        virtualFile.refresh(false, false)

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        if (document == null) {
            uiLog.error("Gradle Uninstall: Could not get document for ${installed.buildFile}", "Uninstall")
            return
        }

        val originalContent = document.text
        val newContent = gradleModifier.getRemovedContent(installed)
        if (newContent == null) {
            uiLog.error("Gradle Uninstall: Failed to generate content with dependency removed", "Uninstall")
            return
        }

        uiLog.info("Gradle Uninstall: Applying changes to ${installed.buildFile}", "Uninstall")
        applyWithOptionalPreview(installed.buildFile, originalContent, newContent, "Remove Dependency: ${pkg.id}") {
            gradleModifier.applyChanges(installed, newContent, "Remove Dependency: ${pkg.id}")
            refresh()
            uiLog.info("Gradle Uninstall: Successfully removed ${pkg.id}", "Uninstall")
        }
    }

    private fun performMavenUninstall(pkg: UnifiedPackage) {
        uiLog.info("Maven Uninstall: Starting uninstall of ${pkg.id}", "Uninstall")

        // Find the installed dependency to remove
        uiLog.debug("Maven Uninstall: Searching in ${mavenInstalledDependencies.size} cached dependencies", "Uninstall")

        val installed = mavenInstalledDependencies.find {
            it.groupId == pkg.publisher && it.artifactId == pkg.name
        }

        if (installed == null) {
            uiLog.error("Maven Uninstall: Could not find ${pkg.id} in installed dependencies cache", "Uninstall")
            uiLog.info("Maven Uninstall: Available dependencies: ${mavenInstalledDependencies.map { it.id }.joinToString(", ")}", "Uninstall")

            // Show error to user
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Could not find dependency ${pkg.id} in installed dependencies.\nTry refreshing the dependency list first.",
                    "Uninstall Failed"
                )
            }
            return
        }

        uiLog.info("Maven Uninstall: Found dependency in ${installed.pomFile}", "Uninstall")

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.pomFile)
        if (virtualFile == null) {
            uiLog.error("Maven Uninstall: Could not find file ${installed.pomFile}", "Uninstall")
            return
        }

        // Refresh the file to get latest content
        virtualFile.refresh(false, false)

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        if (document == null) {
            uiLog.error("Maven Uninstall: Could not get document for ${installed.pomFile}", "Uninstall")
            return
        }

        val originalContent = document.text
        uiLog.debug("Maven Uninstall: Original content length: ${originalContent.length}", "Uninstall")

        val newContent = mavenModifier.getRemovedContent(installed)
        if (newContent == null) {
            uiLog.error("Maven Uninstall: Failed to generate content with dependency removed", "Uninstall")
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Failed to generate modified pom.xml content.\nThe dependency block may not be in expected format.",
                    "Uninstall Failed"
                )
            }
            return
        }

        uiLog.info("Maven Uninstall: Applying changes to ${installed.pomFile}", "Uninstall")
        applyWithOptionalPreview(installed.pomFile, originalContent, newContent, "Remove Dependency: ${pkg.id}") {
            mavenModifier.applyChanges(installed, newContent, "Remove Dependency: ${pkg.id}")
            refresh()
            uiLog.info("Maven Uninstall: Successfully removed ${pkg.id}", "Uninstall")
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
     * Fetch versions from Azure Artifacts using the Maven-specific API.
     * https://learn.microsoft.com/en-us/rest/api/azure/devops/artifactspackagetypes/maven/get-package-versions
     *
     * Uses: GET https://pkgs.dev.azure.com/{organization}/{project}/_apis/packaging/feeds/{feedId}/maven/{groupId}/{artifactId}/versions
     * Note: This is different from the generic packages API - Maven has its own endpoint with separate groupId/artifactId path segments.
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
                    uiLog.warn("Could not parse Azure URL, falling back to Maven Central", "Versions")
                    return fetchVersionsFromMavenCentral(pkg)
                }
            }

            // Use the Maven-specific API endpoint with separate groupId/artifactId path segments
            // This avoids the colon (:) in URL path issue that causes HTTP 400 errors
            val groupId = pkg.publisher
            val artifactId = pkg.name

            val apiBase = if (project != null) {
                "https://pkgs.dev.azure.com/$org/$project/_apis/packaging/feeds/$feed/maven"
            } else {
                "https://pkgs.dev.azure.com/$org/_apis/packaging/feeds/$feed/maven"
            }

            val versionsUrl = "$apiBase/$groupId/$artifactId/versions?api-version=7.1"
            uiLog.info("Azure Maven Versions API URL: $versionsUrl", "Versions")

            val auth = buildAuthCredentials(repo)
            val headers = mapOf("Accept" to "application/json")

            val result = com.maddrobot.plugins.udm.util.HttpRequestHelper.getForObject(versionsUrl, auth, headers) { it }

            when (result) {
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Success -> {
                    if (result.data != null) {
                        uiLog.info("Azure Versions: Received response (${result.data.length} bytes)", "Versions")
                        return parseAzureVersionsResponse(result.data)
                    }
                }
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Error -> {
                    uiLog.warn("Azure versions fetch failed: ${result.exception.message} (code: ${result.responseCode})", "Versions")
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

            val result = com.maddrobot.plugins.udm.util.HttpRequestHelper.getForObject(searchUrl, auth) { it }

            when (result) {
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Success -> {
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
                is com.maddrobot.plugins.udm.util.HttpRequestHelper.RequestResult.Error -> {
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

    /**
     * Show the bulk upgrade dialog for upgrading multiple packages at once.
     */
    private fun showBulkUpgradeDialog() {
        val packagesWithUpdates = if (isGradleProject) {
            gradleDependencyService.dependencyUpdates.map { update ->
                PackageAdapters.fromInstalledDependency(update.installed, update)
            }
        } else emptyList()

        if (packagesWithUpdates.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "All packages are up to date.",
                "No Updates Available"
            )
            return
        }

        val dialog = BulkUpgradeDialog(project, packagesWithUpdates)
        if (dialog.showAndGet()) {
            val selectedPackages = dialog.getSelectedPackages()
            uiLog.info("Bulk upgrade: ${selectedPackages.size} packages selected", "BulkUpgrade")

            // Perform updates for each selected package
            for (pkg in selectedPackages) {
                val newVersion = pkg.latestVersion ?: continue
                val metadata = pkg.metadata

                when (metadata) {
                    is PackageMetadata.GradleMetadata -> {
                        val installed = gradleDependencyService.installedDependencies.find {
                            it.groupId == pkg.publisher && it.artifactId == pkg.name
                        }
                        if (installed != null) {
                            val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile)
                            val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
                            if (document != null) {
                                val newContent = gradleModifier.getUpdatedContent(installed, newVersion)
                                if (newContent != null) {
                                    gradleModifier.applyChanges(installed, newContent, "Bulk Update: ${pkg.id}")
                                }
                            }
                        }
                    }
                    else -> {
                        uiLog.warn("Bulk upgrade not implemented for metadata type: ${metadata::class.simpleName}", "BulkUpgrade")
                    }
                }
            }

            // Refresh after all updates
            refresh()
        }
    }

    /**
     * Show the consolidate versions dialog for fixing inconsistent package versions.
     */
    private fun showConsolidateDialog() {
        val allPackages = getAllInstalledPackages()
        val inconsistentPackages = ConsolidateVersionsDialog.findInconsistentPackages(allPackages)

        if (inconsistentPackages.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "All package versions are consistent across modules.",
                "No Inconsistencies Found"
            )
            return
        }

        val dialog = ConsolidateVersionsDialog(project, inconsistentPackages)
        if (dialog.showAndGet()) {
            val results = dialog.getConsolidationResults()
            uiLog.info("Consolidate: ${results.size} version changes to apply", "Consolidate")

            // Apply consolidation changes
            for (result in results) {
                // Find the installed dependency for this module
                val installed = if (isGradleProject) {
                    gradleDependencyService.installedDependencies.find {
                        it.id == result.packageId && it.moduleName == result.moduleName
                    }
                } else null

                if (installed != null) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(installed.buildFile)
                    val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
                    if (document != null) {
                        val newContent = gradleModifier.getUpdatedContent(installed, result.newVersion)
                        if (newContent != null) {
                            gradleModifier.applyChanges(installed, newContent, "Consolidate: ${result.packageId}")
                        }
                    }
                }
            }

            // Refresh after all changes
            refresh()
        }
    }

    private fun showRepositoryManager() {
        RepositoryManagerDialog(project).show()
    }

    // ========== Context Menu Action Handlers ==========

    /**
     * Uninstall a package from a specific module only.
     */
    private fun performUninstallFromModule(pkg: UnifiedPackage, module: String) {
        uiLog.info("Uninstall from module: ${pkg.id} from $module", "Uninstall")
        // For now, use the same uninstall logic - module-specific uninstall can be enhanced later
        performUninstall(pkg)
    }

    /**
     * Open the package's local installation folder in the system file explorer.
     */
    private fun openPackageFolder(pkg: UnifiedPackage) {
        val localRepoPath = System.getProperty("user.home") + "/.m2/repository"
        val groupPath = pkg.publisher.replace(".", "/")
        val artifactPath = pkg.name
        val versionPath = pkg.installedVersion ?: pkg.latestVersion ?: ""
        val folderPath = "$localRepoPath/$groupPath/$artifactPath/$versionPath"

        val folder = java.io.File(folderPath)
        if (folder.exists()) {
            java.awt.Desktop.getDesktop().open(folder)
        } else {
            uiLog.warn("Package folder not found: $folderPath", "Open Folder")
        }
    }

    /**
     * Show the dependency tree dialog for a package.
     */
    private fun showDependencyTree(pkg: UnifiedPackage) {
        DependencyTreeDialog(
            project,
            pkg,
            DependencyTreeDialog.Mode.DEPENDENCIES,
            onExcludeRequested = { node ->
                val exclusion = DependencyExclusion(
                    groupId = node.groupId,
                    artifactId = node.artifactId
                )
                performAddExclusion(pkg, exclusion)
            }
        ).show()
    }

    /**
     * Show what packages depend on this package (reverse dependencies).
     */
    private fun showReverseDependents(pkg: UnifiedPackage) {
        DependencyTreeDialog(project, pkg, DependencyTreeDialog.Mode.DEPENDENTS).show()
    }

    /**
     * Explain why a transitive dependency is installed.
     */
    private fun showWhyInstalled(pkg: UnifiedPackage) {
        // Show reverse dependency tree to explain why this package is installed
        showReverseDependents(pkg)
    }

    /**
     * Batch uninstall multiple packages.
     */
    private fun performBatchUninstall(packages: List<UnifiedPackage>) {
        if (packages.isEmpty()) return

        val message = "Are you sure you want to remove ${packages.size} packages?\n\n" +
            packages.take(10).joinToString("\n") { "• ${it.id}" } +
            (if (packages.size > 10) "\n... and ${packages.size - 10} more" else "")

        val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            message,
            "Remove ${packages.size} Packages",
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )

        if (result == com.intellij.openapi.ui.Messages.YES) {
            packages.forEach { pkg ->
                performUninstall(pkg)
            }
        }
    }

    /**
     * Batch upgrade multiple packages with available updates.
     */
    private fun performBatchUpgrade(packages: List<UnifiedPackage>) {
        val packagesWithUpdates = packages.filter { it.getStatus().hasUpdate }
        if (packagesWithUpdates.isEmpty()) return

        // Show bulk upgrade dialog
        showBulkUpgradeDialog()
    }

    /**
     * Show Maven plugin configuration dialog.
     * Downloads plugin descriptor asynchronously, then shows the config editor.
     */
    private fun showMavenPluginConfigDialog(pkg: UnifiedPackage) {
        val metadata = pkg.metadata as? PackageMetadata.MavenPluginMetadata ?: return

        uiLog.info("Configure: Loading Maven plugin descriptor for ${pkg.id}", "Configure")

        // Fetch plugin descriptor on a pooled thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val descriptorService = PluginDescriptorService.getInstance(project)
            var version = pkg.installedVersion

            // Resolve property-based versions (e.g., "${maven-compiler-plugin.version}")
            if (version != null && version.contains("\${")) {
                uiLog.info("Configure: Resolving property-based version '$version' from ${metadata.pomFile}", "Configure")
                version = mavenPluginScanner.resolveVersionFromPom(version, metadata.pomFile)
                if (version != null) {
                    uiLog.info("Configure: Resolved version to '$version'", "Configure")
                } else {
                    uiLog.warn("Configure: Could not resolve version property for ${pkg.id}", "Configure")
                }
            }

            val descriptor = if (version != null) {
                descriptorService.getPluginDescriptor(pkg.publisher, pkg.name, version)
            } else {
                null
            }

            ApplicationManager.getApplication().invokeLater {
                if (descriptor != null) {
                    uiLog.info("Configure: Loaded descriptor with ${descriptor.mojos.size} goals", "Configure")
                } else {
                    uiLog.warn("Configure: Could not load plugin descriptor for ${pkg.id}", "Configure")
                }

                val dialog = MavenPluginConfigDialog(
                    project,
                    descriptor,
                    metadata.configuration,
                    pkg.id
                )

                if (dialog.showAndGet()) {
                    val newConfig = dialog.resultConfiguration
                    uiLog.info("Configure: Applying ${newConfig.size} configuration properties to ${pkg.id}", "Configure")
                    performMavenPluginConfigure(pkg, metadata, newConfig)
                }
            }
        }
    }

    /**
     * Apply Maven plugin configuration changes.
     */
    private fun performMavenPluginConfigure(
        pkg: UnifiedPackage,
        metadata: PackageMetadata.MavenPluginMetadata,
        configuration: Map<String, String>
    ) {
        val installedPlugin = MavenInstalledPlugin(
            groupId = pkg.publisher,
            artifactId = pkg.name,
            version = pkg.installedVersion,
            moduleName = pkg.modules.firstOrNull() ?: "root",
            pomFile = metadata.pomFile,
            offset = metadata.offset,
            length = metadata.length,
            phase = metadata.phase,
            goals = metadata.goals,
            inherited = metadata.inherited,
            isFromPluginManagement = metadata.isFromPluginManagement,
            configuration = metadata.configuration
        )

        val originalContent = mavenPluginModifier.getOriginalContent(metadata.pomFile) ?: return
        val newContent = mavenPluginModifier.getConfiguredContent(installedPlugin, configuration)
        if (newContent != null) {
            applyWithOptionalPreview(metadata.pomFile, originalContent, newContent, "Configure Plugin: ${pkg.id}") {
                mavenPluginModifier.applyChanges(installedPlugin, newContent, "Configure Plugin: ${pkg.id}")
                refresh()
                uiLog.info("Configure: Successfully applied Maven plugin configuration for ${pkg.id}", "Configure")
            }
        } else {
            uiLog.error("Configure: Failed to generate configured pom.xml content for ${pkg.id}", "Configure")
        }
    }

    /**
     * Show Gradle plugin configuration dialog.
     * Scans for existing extension block, then shows the config editor.
     */
    private fun showGradlePluginConfigDialog(pkg: UnifiedPackage) {
        val metadata = pkg.metadata as? PackageMetadata.GradlePluginMetadata ?: return

        val extensionName = KnownPluginExtensions.getExtensionName(pkg.name)

        // Scan for existing configuration if we know the extension name
        val existingConfig = if (extensionName != null) {
            gradlePluginScanner.scanPluginConfiguration(metadata.buildFile, extensionName)
        } else {
            null
        }

        val isKotlinDsl = metadata.buildFile.endsWith(".kts")

        val dialog = GradlePluginConfigDialog(
            project,
            pkg.name,
            extensionName ?: existingConfig?.extensionName,
            existingConfig,
            isKotlinDsl
        )

        if (dialog.showAndGet()) {
            uiLog.info("Configure: Applying Gradle plugin configuration for ${pkg.id}", "Configure")
            performGradlePluginConfigure(pkg, metadata, dialog)
        }
    }

    /**
     * Apply Gradle plugin configuration changes.
     */
    private fun performGradlePluginConfigure(
        pkg: UnifiedPackage,
        metadata: PackageMetadata.GradlePluginMetadata,
        dialog: GradlePluginConfigDialog
    ) {
        val extensionName = dialog.extensionName
        val originalContent = gradlePluginModifier.getOriginalContent(metadata.buildFile) ?: return

        // Scan for existing config to get offset/length for in-place replacement
        val existingConfig = gradlePluginScanner.scanPluginConfiguration(metadata.buildFile, extensionName)

        val newContent = if (dialog.isRawEditorMode) {
            gradlePluginModifier.getConfiguredContentFromRaw(
                metadata.buildFile,
                extensionName,
                dialog.rawBlockContent,
                existingConfig
            )
        } else {
            gradlePluginModifier.getConfiguredContent(
                metadata.buildFile,
                extensionName,
                dialog.resultProperties,
                existingConfig
            )
        }

        if (newContent != null) {
            applyWithOptionalPreview(metadata.buildFile, originalContent, newContent, "Configure Plugin: ${pkg.id}") {
                gradlePluginModifier.applyChanges(metadata.buildFile, newContent, "Configure Plugin: ${pkg.id}")
                refresh()
                uiLog.info("Configure: Successfully applied Gradle plugin configuration for ${pkg.id}", "Configure")
            }
        } else {
            uiLog.error("Configure: Failed to generate configured build file content for ${pkg.id}", "Configure")
        }
    }
}
