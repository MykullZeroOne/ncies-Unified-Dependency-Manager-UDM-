package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.GradlePluginUpdateService
import com.maddrobot.plugins.udm.gradle.manager.service.PackageMetadataService
import com.maddrobot.plugins.udm.gradle.manager.service.PluginLogService
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryConfig
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependencyService
import com.maddrobot.plugins.udm.gradle.manager.service.VulnerabilityService
import com.maddrobot.plugins.udm.maven.manager.MavenPluginUpdateService
import com.maddrobot.plugins.udm.maven.manager.PluginDescriptorService
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryConfigWriter
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryDiscoveryService
import com.maddrobot.plugins.udm.gradle.manager.service.RepositorySource
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryType
import java.awt.*
import javax.swing.*

/**
 * Main tool window panel with tab bar (Packages, Repositories, Caches, Log).
 * Provides NuGet-style interface for the Unified Dependency Manager.
 */
class MainToolWindowPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : SimpleToolWindowPanel(true, true) {

    private val tabbedPane = JBTabbedPane()

    // Tab panels
    private val packagesPanel: UnifiedDependencyPanel
    private val repositoriesPanel: JPanel
    private val cachesPanel: JPanel
    private val logPanel: JPanel

    val contentPanel: JPanel

    // Tab button management - must be initialized before init block
    private val tabButtons = mutableListOf<JToggleButton>()
    private val tabButtonGroup = ButtonGroup()

    init {
        // Create tab content panels
        packagesPanel = UnifiedDependencyPanel(project, parentDisposable)
        repositoriesPanel = createRepositoriesPanel()
        cachesPanel = createCachesPanel()
        logPanel = createLogPanel()

        // Build the main panel
        contentPanel = JPanel(BorderLayout()).apply {
            add(createHeaderPanel(), BorderLayout.NORTH)
            add(createMainContentPanel(), BorderLayout.CENTER)
        }

        setContent(contentPanel)
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 0, 8)
            background = JBColor.PanelBackground

            // Left side: UDP logo/title and tabs
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false

                // UDP logo/title
                add(JBLabel(message("unified.main.title")).apply {
                    font = font.deriveFont(Font.BOLD, 14f)
                    icon = AllIcons.Nodes.PpLib
                    border = JBUI.Borders.emptyRight(16)
                })

                // Tab buttons
                add(createTabButton(message("unified.tab.packages"), 0, true))
                add(createTabButton(message("unified.tab.repositories"), 1, false))
                add(createTabButton(message("unified.tab.caches"), 2, false))
                add(createTabButton(message("unified.tab.log"), 3, false))
            }

            // Right side: Project selector and menu
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false

                add(JBLabel(message("unified.panel.module")))
                add(packagesPanel.getModuleSelector().apply {
                    preferredSize = Dimension(120, preferredSize.height)
                })
                add(createMenuButton())
            }

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    private fun createTabButton(text: String, tabIndex: Int, selected: Boolean): JToggleButton {
        return JToggleButton(text).apply {
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            font = font.deriveFont(13f)
            foreground = if (selected) JBColor(0x4A90D9, 0x589DF6) else JBColor.foreground()
            border = JBUI.Borders.empty(8, 16)
            isSelected = selected

            // Underline for selected tab
            if (selected) {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(0x4A90D9, 0x589DF6)),
                    JBUI.Borders.empty(8, 16, 6, 16)
                )
            }

            addActionListener {
                selectTab(tabIndex)
            }

            tabButtons.add(this)
            tabButtonGroup.add(this)
        }
    }

    private fun selectTab(index: Int) {
        // Update button styles
        tabButtons.forEachIndexed { i, button ->
            val isSelected = i == index
            button.foreground = if (isSelected) JBColor(0x4A90D9, 0x589DF6) else JBColor.foreground()
            button.border = if (isSelected) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(0x4A90D9, 0x589DF6)),
                    JBUI.Borders.empty(8, 16, 6, 16)
                )
            } else {
                JBUI.Borders.empty(8, 16)
            }
        }

        // Switch tab content
        tabbedPane.selectedIndex = index
    }

    private fun createMenuButton(): JButton {
        return JButton(AllIcons.Actions.More).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            toolTipText = message("unified.main.menu")
            preferredSize = Dimension(28, 28)
            addActionListener {
                showPopupMenu(this)
            }
        }
    }

    private fun showPopupMenu(component: Component) {
        val menu = JPopupMenu()
        menu.add(JMenuItem(message("unified.panel.settings")).apply {
            icon = AllIcons.General.Settings
            addActionListener {
                RepositoryManagerDialog(project).show()
            }
        })
        menu.addSeparator()
        menu.add(JMenuItem(message("unified.main.refresh")).apply {
            icon = AllIcons.Actions.Refresh
            addActionListener {
                packagesPanel.refresh()
            }
        })
        menu.show(component, 0, component.height)
    }

    private fun createMainContentPanel(): JPanel {
        return JPanel(CardLayout()).apply {
            // Use CardLayout for tab switching
            add(packagesPanel.getContentWithoutHeader(), "packages")
            add(repositoriesPanel, "repositories")
            add(cachesPanel, "caches")
            add(logPanel, "log")

            // Create a wrapper that switches cards
            tabbedPane.addChangeListener {
                val layout = this.layout as CardLayout
                when (tabbedPane.selectedIndex) {
                    0 -> layout.show(this, "packages")
                    1 -> layout.show(this, "repositories")
                    2 -> layout.show(this, "caches")
                    3 -> layout.show(this, "log")
                }
            }
        }.also { cardPanel ->
            // Initialize with packages tab
            tabbedPane.addTab("Packages", JPanel())
            tabbedPane.addTab("Repositories", JPanel())
            tabbedPane.addTab("Caches", JPanel())
            tabbedPane.addTab("Log", JPanel())
            tabbedPane.selectedIndex = 0
        }
    }

    private fun createRepositoriesPanel(): JPanel {
        val discoveryService = RepositoryDiscoveryService.getInstance(project)
        val configWriter = RepositoryConfigWriter.getInstance(project)

        val repositoryListModel = DefaultListModel<RepositoryConfig>()
        val repositoryList = JList(repositoryListModel).apply {
            cellRenderer = RepositoryListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        // Load repositories
        fun loadRepositories() {
            repositoryListModel.clear()
            discoveryService.getConfiguredRepositories().forEach { repositoryListModel.addElement(it) }
        }

        // Test connection
        fun testConnection() {
            val selected = repositoryList.selectedValue ?: return
            try {
                val url = java.net.URL(selected.url.trimEnd('/') + "/")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (selected.username != null || selected.password != null) {
                    val credentials = "${selected.username ?: ""}:${selected.password ?: ""}"
                    val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                    connection.setRequestProperty("Authorization", "Basic $encoded")
                }

                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 200..399) {
                    val authInfo = if (selected.username != null || selected.password != null) " (authenticated)" else ""
                    Messages.showInfoMessage(project, "Successfully connected to ${selected.name}$authInfo", "Test Connection")
                } else {
                    Messages.showWarningDialog(project, "Connection failed with HTTP status: $responseCode", "Test Connection")
                }
            } catch (e: Exception) {
                Messages.showErrorDialog(project, "Connection error: ${e.localizedMessage}", "Test Connection")
            }
        }

        // Create toolbar decorator
        val decorator = ToolbarDecorator.createDecorator(repositoryList)
            .setAddAction {
                val dialog = AddEditRepositoryDialog(project, null)
                if (dialog.showAndGet()) {
                    val repo = dialog.getRepository()
                    if (repo != null) {
                        saveRepository(repo, dialog.saveTarget, configWriter, loadRepositories = { loadRepositories() })
                    }
                }
            }
            .setRemoveAction {
                val selected = repositoryList.selectedValue ?: return@setRemoveAction
                if (selected.source == RepositorySource.BUILTIN) {
                    Messages.showWarningDialog(project, "Built-in repositories cannot be removed.", "Remove Repository")
                    return@setRemoveAction
                }
                val result = Messages.showYesNoDialog(
                    project,
                    "Are you sure you want to remove \"${selected.name}\"?",
                    "Remove Repository",
                    Messages.getQuestionIcon()
                )
                if (result == Messages.YES) {
                    // For now, show manual removal instructions
                    Messages.showInfoMessage(
                        project,
                        "Please manually remove this repository from your build configuration:\n\n" +
                            "• Gradle: Remove from settings.gradle or build.gradle\n" +
                            "• Maven: Remove from ~/.m2/settings.xml or pom.xml\n\n" +
                            "Source: ${selected.source.name.lowercase().replace("_", " ")}",
                        "Remove Repository"
                    )
                    loadRepositories()
                }
            }
            .setEditAction {
                val selected = repositoryList.selectedValue ?: return@setEditAction
                if (selected.source == RepositorySource.BUILTIN) {
                    Messages.showWarningDialog(project, "Built-in repositories cannot be edited.", "Edit Repository")
                    return@setEditAction
                }
                val dialog = AddEditRepositoryDialog(project, selected)
                if (dialog.showAndGet()) {
                    val repo = dialog.getRepository()
                    if (repo != null) {
                        saveRepository(repo, dialog.saveTarget, configWriter, loadRepositories = { loadRepositories() })
                    }
                }
            }
            .addExtraAction(object : AnAction("Test Connection", "Test connection to selected repository", AllIcons.Actions.Lightning) {
                override fun actionPerformed(e: AnActionEvent) = testConnection()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            .addExtraAction(object : AnAction("Refresh", "Refresh repository list", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = loadRepositories()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

        // Initial load
        loadRepositories()

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            // Info panel at top
            val infoPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(8)
                add(JBLabel("<html><b>Configured Repositories</b><br>" +
                    "<font color='gray'>Repositories are discovered from your build configuration. " +
                    "Add new repositories to make them available for dependency search.</font></html>"), BorderLayout.CENTER)
            }

            add(infoPanel, BorderLayout.NORTH)
            add(decorator.createPanel(), BorderLayout.CENTER)

            // Legend panel at bottom
            val legendPanel = JPanel(FlowLayout(FlowLayout.LEFT, 16, 4)).apply {
                border = JBUI.Borders.emptyTop(8)
                add(JBLabel("<html><font color='gray'>Sources:</font></html>"))
                add(JBLabel("<html><font color='#666666'>builtin</font> = Default repos</html>"))
                add(JBLabel("<html><font color='#666666'>gradle settings/build</font> = From Gradle files</html>"))
                add(JBLabel("<html><font color='#666666'>maven settings</font> = From ~/.m2/settings.xml or pom.xml</html>"))
            }
            add(legendPanel, BorderLayout.SOUTH)
        }
    }

    private fun saveRepository(repo: RepositoryConfig, target: SaveTarget, configWriter: RepositoryConfigWriter, loadRepositories: () -> Unit) {
        when (target) {
            SaveTarget.GRADLE -> {
                val targetFile = configWriter.getRecommendedGradleTarget()
                if (targetFile != null) {
                    val result = configWriter.getGradleRepositoryAddition(repo, targetFile)
                    if (result != null) {
                        val (original, modified) = result
                        val previewDialog = PreviewDiffDialog(project, targetFile.path, original, modified, "Preview Repository Addition")
                        if (previewDialog.showAndGet()) {
                            configWriter.applyGradleChanges(targetFile, modified, "Add Repository: ${repo.name}")
                            if (repo.username != null || repo.password != null) {
                                Messages.showInfoMessage(project, "Repository added to ${targetFile.name}.\nCredentials saved to ~/.gradle/gradle.properties", "Add Repository")
                            }
                            loadRepositories()
                        }
                    } else {
                        Messages.showInfoMessage(project, "This repository already exists in the configuration.", "Add Repository")
                    }
                }
            }
            SaveTarget.MAVEN_SETTINGS -> {
                val result = configWriter.getMavenRepositoryAddition(repo)
                if (result != null) {
                    val (original, modified) = result
                    val confirm = if (original.isEmpty()) {
                        Messages.showYesNoDialog(project, "This will create a new ~/.m2/settings.xml file. Continue?", "Add Repository", Messages.getQuestionIcon())
                    } else {
                        Messages.showYesNoDialog(project, "This will modify your ~/.m2/settings.xml file. Continue?", "Add Repository", Messages.getQuestionIcon())
                    }
                    if (confirm == Messages.YES) {
                        configWriter.applyMavenChanges(modified)
                        loadRepositories()
                    }
                }
            }
            SaveTarget.MAVEN_POM -> {
                val pomFile = configWriter.getRootPomFile()
                if (pomFile != null) {
                    val result = configWriter.getPomRepositoryAddition(repo, pomFile)
                    if (result != null) {
                        val (original, modified) = result
                        val previewDialog = PreviewDiffDialog(project, pomFile.path, original, modified, "Preview Repository Addition")
                        if (previewDialog.showAndGet()) {
                            configWriter.applyPomChanges(pomFile, modified, "Add Repository: ${repo.name}")
                            if (repo.username != null || repo.password != null) {
                                val settingsResult = configWriter.getMavenRepositoryAddition(repo)
                                if (settingsResult != null) {
                                    configWriter.applyMavenChanges(settingsResult.second)
                                }
                                Messages.showInfoMessage(project, "Repository added to pom.xml.\nCredentials saved to ~/.m2/settings.xml", "Add Repository")
                            }
                            loadRepositories()
                        }
                    } else {
                        Messages.showInfoMessage(project, "This repository already exists in the configuration.", "Add Repository")
                    }
                } else {
                    Messages.showErrorDialog(project, "No pom.xml found in project root.", "Add Repository")
                }
            }
            SaveTarget.PLUGIN_ONLY -> {
                Messages.showInfoMessage(project, "Repository saved to plugin settings only. It will not affect CLI builds.", "Add Repository")
            }
        }
    }


    private fun createCachesPanel(): JPanel {
        // Cache info data class
        data class CacheInfo(
            val name: String,
            val description: String,
            var entries: Int,
            val clearAction: () -> Unit
        )

        // Get all cache services
        val metadataService = PackageMetadataService.getInstance(project)
        val vulnerabilityService = VulnerabilityService.getInstance(project)
        val transitiveDependencyService = TransitiveDependencyService.getInstance(project)
        val gradlePluginUpdateService = GradlePluginUpdateService
        val mavenPluginUpdateService = MavenPluginUpdateService
        val pluginDescriptorService = PluginDescriptorService.getInstance(project)

        // Define cache entries
        val caches = mutableListOf<CacheInfo>()

        fun refreshCacheStats() {
            caches.clear()
            caches.addAll(listOf(
                CacheInfo(
                    "Package Metadata",
                    "Cached package descriptions, licenses, and homepage URLs",
                    (metadataService.getCacheStats()["size"] as? Int) ?: 0,
                    { metadataService.clearCache() }
                ),
                CacheInfo(
                    "Vulnerability Scans",
                    "Cached OSV vulnerability scan results (1 hour TTL)",
                    (vulnerabilityService.getCacheStats()["size"] as? Int) ?: 0,
                    { vulnerabilityService.clearCache() }
                ),
                CacheInfo(
                    "Transitive Dependencies",
                    "Cached dependency tree lookups from Maven Central",
                    (transitiveDependencyService.getCacheStats()["size"] as? Int) ?: 0,
                    { transitiveDependencyService.clearCache() }
                ),
                CacheInfo(
                    "Gradle Plugin Versions",
                    "Cached latest version lookups from Gradle Plugin Portal (1 hour TTL)",
                    (gradlePluginUpdateService.getCacheStats()["size"] as? Int) ?: 0,
                    { gradlePluginUpdateService.clearCache() }
                ),
                CacheInfo(
                    "Maven Plugin Versions",
                    "Cached latest version lookups from Maven Central (1 hour TTL)",
                    (mavenPluginUpdateService.getCacheStats()["size"] as? Int) ?: 0,
                    { mavenPluginUpdateService.clearCache() }
                ),
                CacheInfo(
                    "Plugin Descriptors",
                    "Cached Gradle plugin descriptors and goal information",
                    (pluginDescriptorService.getCacheStats()["size"] as? Int) ?: 0,
                    { pluginDescriptorService.clearCache() }
                )
            ))
        }
        refreshCacheStats()

        // Create the cache list panel
        val cacheListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        // Mutable reference to update function
        var updateCacheList: (() -> Unit)? = null

        updateCacheList = {
            refreshCacheStats()
            cacheListPanel.removeAll()

            var totalEntries = 0
            for (cache in caches) {
                totalEntries += cache.entries
                val cacheRow = JPanel(BorderLayout()).apply {
                    maximumSize = Dimension(Int.MAX_VALUE, 60)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                        JBUI.Borders.empty(8)
                    )

                    // Left: Name and description
                    val infoPanel = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        add(JBLabel(cache.name).apply {
                            font = font.deriveFont(Font.BOLD, 13f)
                        })
                        add(JBLabel(cache.description).apply {
                            foreground = JBColor.GRAY
                            font = font.deriveFont(11f)
                        })
                    }

                    // Right: Entry count and clear button
                    val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                        isOpaque = false
                        add(JBLabel("${cache.entries} entries").apply {
                            foreground = if (cache.entries > 0) JBColor(0x4CAF50, 0x81C784) else JBColor.GRAY
                            font = font.deriveFont(Font.BOLD)
                        })
                        add(JButton("Clear").apply {
                            icon = AllIcons.Actions.GC
                            isEnabled = cache.entries > 0
                            addActionListener {
                                cache.clearAction()
                                updateCacheList?.invoke()
                            }
                        })
                    }

                    add(infoPanel, BorderLayout.CENTER)
                    add(actionPanel, BorderLayout.EAST)
                }
                cacheListPanel.add(cacheRow)
            }

            // Summary row
            cacheListPanel.add(Box.createVerticalStrut(16))
            cacheListPanel.add(JPanel(BorderLayout()).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 40)
                isOpaque = false
                add(JBLabel("<html><b>Total cached entries: $totalEntries</b></html>").apply {
                    border = JBUI.Borders.emptyLeft(8)
                }, BorderLayout.WEST)
            })

            cacheListPanel.revalidate()
            cacheListPanel.repaint()
        }

        // Initial population
        updateCacheList()

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            // Header with info
            val headerPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(12)
                add(JBLabel("<html><b>Cache Management</b><br>" +
                    "<font color='gray'>View and clear cached data to free memory or force fresh lookups. " +
                    "Caches improve performance by avoiding repeated network requests.</font></html>"), BorderLayout.CENTER)
            }

            // Toolbar
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                border = JBUI.Borders.emptyBottom(8)
                add(JButton("Refresh").apply {
                    icon = AllIcons.Actions.Refresh
                    addActionListener { updateCacheList?.invoke() }
                })
                add(JButton("Clear All Caches").apply {
                    icon = AllIcons.Actions.GC
                    addActionListener {
                        val result = Messages.showYesNoDialog(
                            project,
                            "This will clear all cached data. Continue?",
                            "Clear All Caches",
                            Messages.getQuestionIcon()
                        )
                        if (result == Messages.YES) {
                            caches.forEach { it.clearAction() }
                            updateCacheList?.invoke()
                        }
                    }
                })
            }

            add(headerPanel, BorderLayout.NORTH)
            add(JBScrollPane(cacheListPanel).apply {
                border = JBUI.Borders.empty()
            }, BorderLayout.CENTER)
            add(toolbar, BorderLayout.SOUTH)
        }
    }

    private fun createLogPanel(): JPanel {
        val logService = PluginLogService.getInstance(project)

        // Log text area with monospace font
        val logTextArea = JTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = JBColor.PanelBackground
            foreground = JBColor.foreground()
            text = logService.getFormattedLog()
        }

        // Listen for new log entries
        val logListener: (PluginLogService.LogEntry) -> Unit = { entry ->
            ApplicationManager.getApplication().invokeLater {
                logTextArea.append(entry.formatted() + "\n")
                // Auto-scroll to bottom
                logTextArea.caretPosition = logTextArea.document.length
            }
        }
        logService.addListener(logListener)

        // Remove listener when disposed
        Disposer.register(parentDisposable) {
            logService.removeListener(logListener)
        }

        // Log an initial message
        logService.info("Log panel initialized", "UI")

        return JPanel(BorderLayout()).apply {
            // Toolbar with clear button
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(JButton("Clear").apply {
                    icon = AllIcons.Actions.GC
                    addActionListener {
                        logTextArea.text = ""
                        logService.clear()
                    }
                })
                add(JButton("Copy").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        logTextArea.selectAll()
                        logTextArea.copy()
                        logTextArea.select(0, 0)
                    }
                })

                // Log level filter (for future use)
                add(Box.createHorizontalStrut(20))
                add(JBLabel("Filter:"))
                add(JComboBox(arrayOf("All", "Info", "Warn", "Error")).apply {
                    addActionListener {
                        // TODO: Implement log level filtering
                    }
                })
            }

            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(logTextArea).apply {
                border = JBUI.Borders.empty()
            }, BorderLayout.CENTER)
        }
    }
}
