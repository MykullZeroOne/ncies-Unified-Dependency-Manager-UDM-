package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
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
import com.maddrobot.plugins.udm.gradle.manager.service.PackageCacheService
import com.maddrobot.plugins.udm.gradle.manager.service.PluginLogService
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryConfig
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

            // Left side: UDM logo/title and tabs
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false

                // UDM logo/title
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

        // Load repositories (off EDT to avoid SlowOperations violation from VFS/PSI access)
        fun loadRepositories() {
            repositoryListModel.clear()
            ApplicationManager.getApplication().executeOnPooledThread {
                val repos = ReadAction.compute<List<RepositoryConfig>, Throwable> {
                    discoveryService.getConfiguredRepositories()
                }
                ApplicationManager.getApplication().invokeLater {
                    repositoryListModel.clear()
                    repos.forEach { repositoryListModel.addElement(it) }
                }
            }
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
                    Messages.showInfoMessage(project, message("unified.repo.manager.test.success", "${selected.name}$authInfo"), message("unified.repo.manager.test.title"))
                } else {
                    Messages.showWarningDialog(project, message("unified.repo.manager.test.failed", responseCode), message("unified.repo.manager.test.title"))
                }
            } catch (e: Exception) {
                Messages.showErrorDialog(project, message("unified.repo.manager.test.error", e.localizedMessage ?: ""), message("unified.repo.manager.test.title"))
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
                    Messages.showWarningDialog(project, message("unified.repo.manager.remove.builtin.warning"), message("unified.repo.manager.remove.title"))
                    return@setRemoveAction
                }
                val result = Messages.showYesNoDialog(
                    project,
                    message("unified.repo.manager.remove.confirm", selected.name),
                    message("unified.repo.manager.remove.title"),
                    Messages.getQuestionIcon()
                )
                if (result == Messages.YES) {
                    Messages.showInfoMessage(
                        project,
                        message("unified.repo.panel.remove.manual", selected.source.name.lowercase().replace("_", " ")),
                        message("unified.repo.manager.remove.title")
                    )
                    loadRepositories()
                }
            }
            .setEditAction {
                val selected = repositoryList.selectedValue ?: return@setEditAction
                if (selected.source == RepositorySource.BUILTIN) {
                    Messages.showWarningDialog(project, message("unified.repo.manager.edit.builtin.warning"), message("unified.repo.manager.edit.title"))
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
            .addExtraAction(object : AnAction(message("unified.repo.manager.button.test"), message("unified.repo.manager.button.test.tooltip"), AllIcons.Actions.Lightning) {
                override fun actionPerformed(e: AnActionEvent) = testConnection()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            .addExtraAction(object : AnAction(message("unified.main.refresh"), message("unified.repo.panel.refresh.tooltip"), AllIcons.Actions.Refresh) {
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
                add(JBLabel(message("unified.repo.panel.header")), BorderLayout.CENTER)
            }

            add(infoPanel, BorderLayout.NORTH)
            add(decorator.createPanel(), BorderLayout.CENTER)

            // Legend panel at bottom
            val legendPanel = JPanel(FlowLayout(FlowLayout.LEFT, 16, 4)).apply {
                border = JBUI.Borders.emptyTop(8)
                add(JBLabel(message("unified.repo.panel.legend.sources")))
                add(JBLabel(message("unified.repo.panel.legend.builtin")))
                add(JBLabel(message("unified.repo.panel.legend.gradle")))
                add(JBLabel(message("unified.repo.panel.legend.maven")))
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
                        val previewDialog = PreviewDiffDialog(project, targetFile.path, original, modified, message("unified.repo.manager.preview.title"))
                        if (previewDialog.showAndGet()) {
                            configWriter.applyGradleChanges(targetFile, modified, "${message("unified.repo.manager.add.title")}: ${repo.name}")
                            if (repo.username != null || repo.password != null) {
                                Messages.showInfoMessage(project, message("unified.repo.panel.credentials.saved.gradle", targetFile.name), message("unified.repo.manager.add.title"))
                            }
                            loadRepositories()
                        }
                    } else {
                        Messages.showInfoMessage(project, message("unified.repo.manager.already.exists"), message("unified.repo.manager.add.title"))
                    }
                }
            }
            SaveTarget.MAVEN_SETTINGS -> {
                val result = configWriter.getMavenRepositoryAddition(repo)
                if (result != null) {
                    val (original, modified) = result
                    val confirm = if (original.isEmpty()) {
                        Messages.showYesNoDialog(project, message("unified.repo.manager.maven.create.confirm"), message("unified.repo.manager.add.title"), Messages.getQuestionIcon())
                    } else {
                        Messages.showYesNoDialog(project, message("unified.repo.manager.maven.modify.confirm"), message("unified.repo.manager.add.title"), Messages.getQuestionIcon())
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
                        val previewDialog = PreviewDiffDialog(project, pomFile.path, original, modified, message("unified.repo.manager.preview.title"))
                        if (previewDialog.showAndGet()) {
                            configWriter.applyPomChanges(pomFile, modified, "${message("unified.repo.manager.add.title")}: ${repo.name}")
                            if (repo.username != null || repo.password != null) {
                                val settingsResult = configWriter.getMavenRepositoryAddition(repo)
                                if (settingsResult != null) {
                                    configWriter.applyMavenChanges(settingsResult.second)
                                }
                                Messages.showInfoMessage(project, message("unified.repo.panel.credentials.saved.pom"), message("unified.repo.manager.add.title"))
                            }
                            loadRepositories()
                        }
                    } else {
                        Messages.showInfoMessage(project, message("unified.repo.manager.already.exists"), message("unified.repo.manager.add.title"))
                    }
                } else {
                    Messages.showErrorDialog(project, message("unified.repo.panel.no.pom"), message("unified.repo.manager.add.title"))
                }
            }
            SaveTarget.PLUGIN_ONLY -> {
                Messages.showInfoMessage(project, message("unified.repo.manager.plugin.only.info"), message("unified.repo.manager.add.title"))
            }
        }
    }


    private fun createCachesPanel(): JPanel {
        val cacheService = PackageCacheService.getInstance(project)

        // Stats text area
        val statsTextArea = JTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = JBColor.PanelBackground
            foreground = JBColor.foreground()
            text = cacheService.getFormattedSummary()
        }

        // Function to refresh stats display
        fun refreshStats() {
            statsTextArea.text = cacheService.getFormattedSummary()
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            // Header panel with description
            val headerPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(12)
                add(JBLabel(message("unified.cache.panel.header")), BorderLayout.CENTER)
            }
            add(headerPanel, BorderLayout.NORTH)

            // Center: Stats display
            add(JBScrollPane(statsTextArea).apply {
                border = JBUI.Borders.empty()
            }, BorderLayout.CENTER)

            // Bottom: Action buttons
            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
                border = JBUI.Borders.emptyTop(8)

                add(JButton(message("unified.cache.button.refresh")).apply {
                    icon = AllIcons.Actions.Refresh
                    addActionListener {
                        refreshStats()
                    }
                })

                add(JButton(message("unified.cache.button.clear.version")).apply {
                    icon = AllIcons.Actions.GC
                    toolTipText = message("unified.cache.button.clear.version.tooltip")
                    addActionListener {
                        cacheService.clearVersionCache()
                        refreshStats()
                        Messages.showInfoMessage(project, message("unified.cache.cleared.version"), message("unified.cache.cleared.title"))
                    }
                })

                add(JButton(message("unified.cache.button.clear.search")).apply {
                    icon = AllIcons.Actions.GC
                    toolTipText = message("unified.cache.button.clear.search.tooltip")
                    addActionListener {
                        cacheService.clearSearchCache()
                        refreshStats()
                        Messages.showInfoMessage(project, message("unified.cache.cleared.search"), message("unified.cache.cleared.title"))
                    }
                })

                add(JButton(message("unified.cache.button.clear.all")).apply {
                    icon = AllIcons.Actions.Restart
                    toolTipText = message("unified.cache.button.clear.all.tooltip")
                    addActionListener {
                        val result = Messages.showYesNoDialog(
                            project,
                            message("unified.cache.clear.all.confirm"),
                            message("unified.cache.button.clear.all"),
                            Messages.getQuestionIcon()
                        )
                        if (result == Messages.YES) {
                            cacheService.clearAll()
                            refreshStats()
                            Messages.showInfoMessage(project, message("unified.cache.cleared.all"), message("unified.cache.cleared.title"))
                        }
                    }
                })
            }
            add(buttonPanel, BorderLayout.SOUTH)
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
                add(JButton(message("unified.log.button.clear")).apply {
                    icon = AllIcons.Actions.GC
                    addActionListener {
                        logTextArea.text = ""
                        logService.clear()
                    }
                })
                add(JButton(message("unified.log.button.copy")).apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        logTextArea.selectAll()
                        logTextArea.copy()
                        logTextArea.select(0, 0)
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
