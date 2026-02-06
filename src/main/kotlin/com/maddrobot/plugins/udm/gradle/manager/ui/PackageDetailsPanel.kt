package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyModifier
import com.maddrobot.plugins.udm.gradle.manager.model.AvailableRepository
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import com.maddrobot.plugins.udm.gradle.manager.model.PackageMetadata
import com.maddrobot.plugins.udm.gradle.manager.model.PackageSource
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilityInfo
import com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilitySeverity
import com.maddrobot.plugins.udm.gradle.manager.service.DependencyCoordinates
import com.maddrobot.plugins.udm.gradle.manager.service.PackageDetails
import com.maddrobot.plugins.udm.gradle.manager.service.PackageMetadataService
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependency
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependencyService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Right panel showing comprehensive package details with install/update/uninstall actions.
 * Fetches metadata from Maven Central on demand and caches for installed dependencies.
 */
class PackageDetailsPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) {
    private val modifier = GradleDependencyModifier(project)
    private val metadataService = PackageMetadataService.getInstance(project)
    private val transitiveDependencyService = TransitiveDependencyService.getInstance(project)

    // Observable properties
    private val propertyGraph = PropertyGraph()
    private val selectedPackageProperty = propertyGraph.property<UnifiedPackage?>(null)
    var selectedPackage: UnifiedPackage? by selectedPackageProperty

    // Current fetched details
    private var currentDetails: PackageDetails? = null
    private var isLoadingDetails = false

    // === HEADER SECTION ===
    private val nameLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, 18f)
    }
    private val installedVersionLabel = JBLabel().apply {
        font = font.deriveFont(14f)
        foreground = JBColor.GRAY
    }
    private val latestVersionLabel = JBLabel().apply {
        font = font.deriveFont(12f)
        foreground = JBColor(0x4CAF50, 0x81C784) // Green for updates
    }
    private val notInstalledLabel = JBLabel(message("unified.details.not.installed")).apply {
        font = font.deriveFont(Font.ITALIC, 12f)
        foreground = JBColor.GRAY
        isVisible = false
    }

    // === VERSION SELECTOR ===
    private val versionSelectorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isVisible = false
    }
    private val versionComboBox = JComboBox<VersionItem>().apply {
        preferredSize = Dimension(150, preferredSize.height)
        renderer = VersionListCellRenderer()
    }
    private val applyVersionButton = JButton("Apply").apply {
        isVisible = false
    }
    private var isLoadingVersions = false

    /**
     * Version item with update indicator support.
     */
    data class VersionItem(
        val version: String,
        val hasUpdateIndicator: Boolean = false,
        val isInstalled: Boolean = false
    ) {
        override fun toString(): String = version
    }

    // === DESCRIPTION SECTION ===
    private val descriptionLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, 12f)
        text = message("unified.details.section.description")
    }
    private val descriptionPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4)
        // Allow hyperlinks
        addHyperlinkListener { e ->
            if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                BrowserUtil.browse(e.url)
            }
        }
    }
    private val publisherLabel = JBLabel()

    // === METADATA SECTION ===
    private val homepageLink = HyperlinkLabel()
    private val groupValueLabel = JBLabel()
    private val artifactValueLabel = JBLabel()
    private val scopeValueLabel = JBLabel()
    private val modulesValueLabel = JBLabel()
    private val licenseValueLabel = JBLabel()
    private val sourceValueLabel = JBLabel()

    // === SOURCE REPOSITORY SELECTOR ===
    private val sourceRepoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isVisible = false
    }
    private val sourceRepoComboBox = JComboBox<AvailableRepository>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is AvailableRepository) {
                    text = "${value.name} ${value.version?.let { "($it)" } ?: ""}"
                    icon = AllIcons.Nodes.PpLib
                }
                return this
            }
        }
    }
    private var selectedSourceRepo: AvailableRepository? = null

    // === DEPENDENCIES SECTION (content wrapped in CollapsibleSectionPanel) ===
    private val dependenciesPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        isVisible = true // visibility controlled by CollapsibleSectionPanel
    }
    private val dependenciesListModel = DefaultListModel<String>()
    private val dependenciesList = JList(dependenciesListModel).apply {
        cellRenderer = DependencyListCellRenderer()
        visibleRowCount = 5
    }
    private val loadDependenciesButton = JButton(message("unified.details.load.dependencies")).apply {
        icon = AllIcons.Actions.Refresh
    }
    private var dependenciesExpanded = false

    // === BUILD FILE SECTION (content wrapped in CollapsibleSectionPanel) ===
    private val buildFilePanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        isVisible = true // visibility controlled by CollapsibleSectionPanel
    }
    private val buildFilePathLabel = JBLabel()
    private val showInBuildFileButton = JButton(message("unified.details.show.in.buildfile")).apply {
        icon = AllIcons.Actions.OpenNewTab
    }

    // === VULNERABILITY SECTION ===
    private val vulnerabilityPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }

    // === EXCLUSIONS SECTION ===
    private val exclusionsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }

    // Collapsible section panels (initialized in createContentPanel)
    private lateinit var dependenciesCollapsible: CollapsibleSectionPanel
    private lateinit var buildFileCollapsible: CollapsibleSectionPanel
    private lateinit var vulnerabilityCollapsible: CollapsibleSectionPanel
    private lateinit var exclusionsCollapsible: CollapsibleSectionPanel

    // === ACTION BUTTONS ===
    private val installButton = JButton(message("unified.details.button.install")).apply {
        preferredSize = Dimension(0, 40)
    }
    private val updateButton = JButton(message("unified.details.button.update")).apply {
        preferredSize = Dimension(0, 40)
    }
    private val downgradeButton = JButton(message("unified.details.button.downgrade")).apply {
        preferredSize = Dimension(0, 40)
    }
    private val uninstallButton = JButton(message("unified.details.button.uninstall")).apply {
        preferredSize = Dimension(0, 40)
    }
    private val configureButton = JButton(message("unified.plugin.configure.button")).apply {
        icon = AllIcons.General.Settings
        preferredSize = Dimension(0, 40)
    }

    // Loading indicator
    private val loadingLabel = JBLabel(message("unified.details.loading")).apply {
        icon = AllIcons.Process.Step_1
        foreground = JBColor.GRAY
        isVisible = false
    }

    // Available versions for the selected package (loaded on demand)
    private var availableVersions: List<String> = emptyList()

    // Callbacks
    // Parameters: package, version, module, configuration, sourceRepoUrl (optional)
    var onInstallRequested: ((UnifiedPackage, String, String, String, String?) -> Unit)? = null
    var onUpdateRequested: ((UnifiedPackage, String) -> Unit)? = null
    var onDowngradeRequested: ((UnifiedPackage, String) -> Unit)? = null
    var onUninstallRequested: ((UnifiedPackage) -> Unit)? = null
    var onVersionsNeeded: ((UnifiedPackage, (List<String>) -> Unit) -> Unit)? = null
    var onModulesNeeded: (() -> List<String>)? = null
    var onIgnoreVulnerabilityRequested: ((UnifiedPackage, VulnerabilityInfo) -> Unit)? = null
    var onConfigurePluginRequested: ((UnifiedPackage) -> Unit)? = null
    var onExclusionAddRequested: ((UnifiedPackage, DependencyExclusion) -> Unit)? = null
    var onExclusionRemoveRequested: ((UnifiedPackage, DependencyExclusion) -> Unit)? = null

    private val detailsPanel = JPanel(BorderLayout())
    private val emptyPanel = createEmptyPanel()
    private val contentPanel = createContentPanel()

    val component: JComponent = detailsPanel.apply {
        border = JBUI.Borders.empty(12)
        preferredSize = Dimension(320, 0)
        minimumSize = Dimension(280, 0)
        add(emptyPanel, BorderLayout.CENTER)
    }

    init {
        setupActionListeners()
        selectedPackageProperty.afterChange { pkg ->
            updateUI(pkg)
        }
    }

    private fun createEmptyPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            val label = JBLabel(message("unified.details.empty")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }
            add(label, BorderLayout.CENTER)
        }
    }

    private fun createContentPanel(): JComponent {
        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        // === HEADER ===
        setupVersionSelector()
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(16)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 100)

            val nameVersionPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(nameLabel)
                add(Box.createVerticalStrut(4))

                val versionRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    add(installedVersionLabel)
                    add(Box.createHorizontalStrut(8))
                    add(latestVersionLabel)
                    add(notInstalledLabel)
                }
                add(versionRow)

                // Add version selector row
                add(Box.createVerticalStrut(4))
                add(versionSelectorPanel)
            }
            add(nameVersionPanel, BorderLayout.CENTER)
            add(loadingLabel, BorderLayout.EAST)
        }

        // === DESCRIPTION (Collapsible, expanded by default) ===
        val descriptionCollapsible = CollapsibleSectionPanel(
            message("unified.details.section.description"),
            initiallyExpanded = true
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 180)

            val descContent = JPanel(BorderLayout()).apply {
                val descScroll = JBScrollPane(descriptionPane).apply {
                    preferredSize = Dimension(0, 100)
                    border = JBUI.Borders.empty()
                }
                add(descScroll, BorderLayout.CENTER)
            }
            setContent(descContent)
        }

        // === PUBLISHER ===
        val publisherPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            border = JBUI.Borders.emptyBottom(12)

            add(JBLabel(message("unified.details.label.publisher")).apply {
                foreground = JBColor.GRAY
            })
            add(Box.createHorizontalStrut(8))
            add(publisherLabel)
        }

        // === SOURCE REPOSITORY SELECTOR ===
        setupSourceRepoPanel()
        sourceRepoPanel.alignmentX = Component.LEFT_ALIGNMENT
        sourceRepoPanel.maximumSize = Dimension(Int.MAX_VALUE, 40)

        // === METADATA GRID ===
        val metadataPanel = createMetadataGrid().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        // === VULNERABILITY SECTION (Collapsible, expanded by default when present) ===
        vulnerabilityCollapsible = CollapsibleSectionPanel(
            message("unified.vulnerability.title"),
            initiallyExpanded = true
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 300)
            setContent(vulnerabilityPanel)
            isVisible = false // Hidden by default, shown when vulnerabilities detected
        }

        // === DEPENDENCIES SECTION (Collapsible, collapsed by default) ===
        setupDependenciesPanel()
        dependenciesCollapsible = CollapsibleSectionPanel(
            message("unified.details.section.dependencies"),
            initiallyExpanded = false
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
            setContent(dependenciesPanel)
        }

        // === EXCLUSIONS SECTION (Collapsible, collapsed by default) ===
        exclusionsCollapsible = CollapsibleSectionPanel(
            message("unified.exclusion.section.title"),
            initiallyExpanded = false
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
            setContent(exclusionsPanel)
            isVisible = false // Only visible for installed Gradle/Maven deps with possible exclusions
        }

        // === BUILD FILE SECTION (Collapsible, collapsed by default) ===
        setupBuildFilePanel()
        buildFileCollapsible = CollapsibleSectionPanel(
            message("unified.details.section.buildfile"),
            initiallyExpanded = false
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 100)
            setContent(buildFilePanel)
        }

        // === ACTIONS ===
        val actionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(16)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)

            installButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            installButton.alignmentX = Component.LEFT_ALIGNMENT
            updateButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            updateButton.alignmentX = Component.LEFT_ALIGNMENT
            configureButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            configureButton.alignmentX = Component.LEFT_ALIGNMENT
            downgradeButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            downgradeButton.alignmentX = Component.LEFT_ALIGNMENT
            uninstallButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            uninstallButton.alignmentX = Component.LEFT_ALIGNMENT

            add(installButton)
            add(Box.createVerticalStrut(8))
            add(updateButton)
            add(Box.createVerticalStrut(8))
            add(configureButton)
            add(Box.createVerticalStrut(8))
            add(downgradeButton)
            add(Box.createVerticalStrut(8))
            add(uninstallButton)
        }

        // Assemble main panel
        mainPanel.add(headerPanel)
        mainPanel.add(vulnerabilityCollapsible)
        mainPanel.add(descriptionCollapsible)
        mainPanel.add(publisherPanel)
        mainPanel.add(sourceRepoPanel)
        mainPanel.add(metadataPanel)
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(dependenciesCollapsible)
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(exclusionsCollapsible)
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(buildFileCollapsible)
        mainPanel.add(Box.createVerticalGlue())
        mainPanel.add(actionsPanel)

        return JBScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private val labelColor = JBColor(0x4A90D9, 0x589DF6) // Blue label color

    private fun createMetadataGrid(): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.emptyTop(12)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4, 0)
            }

            var row = 0

            // Homepage
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            add(createBlueLabel(message("unified.details.label.homepage")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            add(homepageLink, gbc)
            row++

            // Group
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            add(createBlueLabel(message("unified.details.label.group")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(groupValueLabel, gbc)
            row++

            // Artifact
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            add(createBlueLabel(message("unified.details.label.artifact")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(artifactValueLabel, gbc)
            row++

            // Scope
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            add(createBlueLabel(message("unified.details.label.scope")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(scopeValueLabel, gbc)
            row++

            // Modules
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            add(createBlueLabel(message("unified.details.label.modules")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(modulesValueLabel, gbc)
        }
    }

    private fun createBlueLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = labelColor
            border = JBUI.Borders.emptyRight(16)
        }
    }

    private fun setupVersionSelector() {
        versionSelectorPanel.add(JBLabel("Version:").apply {
            foreground = labelColor
            border = JBUI.Borders.emptyRight(8)
        })
        versionSelectorPanel.add(versionComboBox)
        versionSelectorPanel.add(Box.createHorizontalStrut(8))
        versionSelectorPanel.add(applyVersionButton)
        versionSelectorPanel.add(Box.createHorizontalStrut(8))

        // Add loading spinner for versions
        val versionsLoadingLabel = JBLabel().apply {
            icon = AllIcons.Process.Step_1
            isVisible = false
        }
        versionSelectorPanel.add(versionsLoadingLabel)

        // Handle version selection changes
        versionComboBox.addActionListener {
            if (!isLoadingVersions) {
                updateApplyVersionButton()
            }
        }

        // Handle apply button click
        applyVersionButton.addActionListener {
            val pkg = selectedPackage ?: return@addActionListener
            val selectedItem = versionComboBox.selectedItem as? VersionItem ?: return@addActionListener
            val selectedVersion = selectedItem.version
            val installedVersion = pkg.installedVersion ?: return@addActionListener

            if (isVersionNewer(selectedVersion, installedVersion)) {
                onUpdateRequested?.invoke(pkg, selectedVersion)
            } else {
                onDowngradeRequested?.invoke(pkg, selectedVersion)
            }
        }
    }

    /**
     * Update the apply version button visibility and text based on selected version.
     */
    private fun updateApplyVersionButton() {
        val pkg = selectedPackage
        val selectedItem = versionComboBox.selectedItem as? VersionItem

        if (pkg == null || selectedItem == null || !pkg.isInstalled) {
            applyVersionButton.isVisible = false
            return
        }

        val selectedVersion = selectedItem.version
        val installedVersion = pkg.installedVersion

        if (selectedVersion == installedVersion || selectedVersion == "Loading...") {
            applyVersionButton.isVisible = false
        } else {
            applyVersionButton.isVisible = true
            if (installedVersion != null && isVersionNewer(selectedVersion, installedVersion)) {
                applyVersionButton.text = "Upgrade to $selectedVersion"
                applyVersionButton.icon = AllIcons.Actions.Upload
            } else {
                applyVersionButton.text = "Downgrade to $selectedVersion"
                applyVersionButton.icon = AllIcons.Actions.Download
            }
        }
        versionSelectorPanel.revalidate()
        versionSelectorPanel.repaint()
    }

    private fun setupSourceRepoPanel() {
        sourceRepoPanel.border = JBUI.Borders.emptyBottom(12)

        sourceRepoPanel.add(JBLabel("Install from:").apply {
            foreground = labelColor
            border = JBUI.Borders.emptyRight(8)
        })
        sourceRepoPanel.add(sourceRepoComboBox.apply {
            preferredSize = Dimension(200, preferredSize.height)
        })

        sourceRepoComboBox.addActionListener {
            selectedSourceRepo = sourceRepoComboBox.selectedItem as? AvailableRepository
        }
    }

    /**
     * Load available versions for the current package.
     */
    private fun loadAvailableVersions(pkg: UnifiedPackage) {
        if (isLoadingVersions) return

        isLoadingVersions = true
        versionComboBox.removeAllItems()
        versionComboBox.addItem(VersionItem("Loading...", false, false))

        onVersionsNeeded?.invoke(pkg) { versions ->
            SwingUtilities.invokeLater {
                isLoadingVersions = false
                versionComboBox.removeAllItems()

                if (versions.isNotEmpty()) {
                    val currentVersion = pkg.installedVersion
                    for (version in versions) {
                        val isInstalled = version == currentVersion
                        // Show update indicator for versions newer than installed
                        val hasUpdate = currentVersion != null && isVersionNewer(version, currentVersion)
                        versionComboBox.addItem(VersionItem(version, hasUpdate, isInstalled))
                    }

                    // Select installed version or latest
                    val selectedIndex = versions.indexOfFirst { it == currentVersion }.takeIf { it >= 0 } ?: 0
                    versionComboBox.selectedIndex = selectedIndex
                } else {
                    val latestVersion = pkg.latestVersion ?: pkg.installedVersion ?: "N/A"
                    versionComboBox.addItem(VersionItem(latestVersion, false, pkg.installedVersion == latestVersion))
                }

                availableVersions = versions
                versionSelectorPanel.isVisible = versions.size > 1 || !pkg.isInstalled
            }
        }
    }

    /**
     * Check if v1 is newer than v2 using semantic version comparison.
     */
    private fun isVersionNewer(v1: String, v2: String): Boolean {
        val parts1 = v1.split("[.\\-_]".toRegex()).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = v2.split("[.\\-_]".toRegex()).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 > p2) return true
            if (p1 < p2) return false
        }
        return false
    }

    /**
     * Custom renderer for version dropdown with update indicator.
     */
    private inner class VersionListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is VersionItem) {
                text = buildString {
                    append(value.version)
                    if (value.isInstalled) append(" (installed)")
                }

                // Show update indicator (green arrow) for newer versions
                if (value.hasUpdateIndicator) {
                    icon = AllIcons.General.ArrowUp
                    foreground = if (isSelected) foreground else JBColor(0x4CAF50, 0x81C784)
                } else if (value.isInstalled) {
                    icon = AllIcons.Actions.Checked
                } else {
                    icon = null
                }
            }

            return this
        }
    }

    /**
     * Set the description text, converting markdown to HTML for proper rendering.
     */
    private fun setDescriptionText(text: String) {
        val html = markdownToHtml(text)
        val styledHtml = wrapInHtmlStyle(html)
        descriptionPane.text = styledHtml
        descriptionPane.caretPosition = 0 // Scroll to top
    }

    /**
     * Convert simple markdown to HTML.
     * Supports: headers, bold, italic, code, links, lists, horizontal rules.
     */
    private fun markdownToHtml(markdown: String): String {
        var text = markdown
            // Normalize line endings
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        // Escape HTML special characters first (but not our markdown)
        text = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Process line by line for block elements
        val lines = text.split("\n")
        val result = StringBuilder()
        var inCodeBlock = false
        var inList = false
        var listType = ""

        for (line in lines) {
            var processedLine = line

            // Code blocks (```)
            if (processedLine.trim().startsWith("```")) {
                if (inCodeBlock) {
                    result.append("</pre>")
                    inCodeBlock = false
                } else {
                    result.append("<pre style='background-color:#f5f5f5;padding:8px;border-radius:4px;font-family:monospace;'>")
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                result.append(processedLine).append("\n")
                continue
            }

            // Setext-style headers (underlined with === or ---)
            if (processedLine.matches(Regex("^=+\\s*$")) && result.isNotEmpty()) {
                // H1 - replace previous line
                val lastNewline = result.lastIndexOf("\n")
                if (lastNewline >= 0) {
                    val prevLine = result.substring(lastNewline + 1)
                    result.setLength(lastNewline + 1)
                    result.append("<h2 style='margin:8px 0;border-bottom:1px solid #ccc;'>$prevLine</h2>")
                }
                continue
            }
            if (processedLine.matches(Regex("^-+\\s*$")) && result.isNotEmpty()) {
                // H2 - replace previous line
                val lastNewline = result.lastIndexOf("\n")
                if (lastNewline >= 0) {
                    val prevLine = result.substring(lastNewline + 1)
                    result.setLength(lastNewline + 1)
                    result.append("<h3 style='margin:6px 0;'>$prevLine</h3>")
                }
                continue
            }

            // ATX-style headers (# Header)
            processedLine = processedLine
                .replace(Regex("^######\\s+(.+)$"), "<h6 style='margin:4px 0;'>$1</h6>")
                .replace(Regex("^#####\\s+(.+)$"), "<h5 style='margin:4px 0;'>$1</h5>")
                .replace(Regex("^####\\s+(.+)$"), "<h4 style='margin:5px 0;'>$1</h4>")
                .replace(Regex("^###\\s+(.+)$"), "<h3 style='margin:6px 0;'>$1</h3>")
                .replace(Regex("^##\\s+(.+)$"), "<h2 style='margin:8px 0;border-bottom:1px solid #ccc;'>$1</h2>")
                .replace(Regex("^#\\s+(.+)$"), "<h1 style='margin:10px 0;border-bottom:2px solid #ccc;'>$1</h1>")

            // Horizontal rules
            if (processedLine.matches(Regex("^(\\*{3,}|-{3,}|_{3,})\\s*$"))) {
                processedLine = "<hr style='border:none;border-top:1px solid #ccc;margin:8px 0;'/>"
            }

            // Unordered lists
            if (processedLine.matches(Regex("^\\s*[-*+]\\s+.+"))) {
                if (!inList || listType != "ul") {
                    if (inList) result.append("</$listType>")
                    result.append("<ul style='margin:4px 0;padding-left:20px;'>")
                    inList = true
                    listType = "ul"
                }
                processedLine = processedLine.replace(Regex("^\\s*[-*+]\\s+(.+)$"), "<li>$1</li>")
            }
            // Ordered lists
            else if (processedLine.matches(Regex("^\\s*\\d+\\.\\s+.+"))) {
                if (!inList || listType != "ol") {
                    if (inList) result.append("</$listType>")
                    result.append("<ol style='margin:4px 0;padding-left:20px;'>")
                    inList = true
                    listType = "ol"
                }
                processedLine = processedLine.replace(Regex("^\\s*\\d+\\.\\s+(.+)$"), "<li>$1</li>")
            }
            // Close list if not a list item
            else if (inList && processedLine.isNotBlank()) {
                result.append("</$listType>")
                inList = false
            }

            // Inline formatting
            processedLine = processedLine
                // Bold (**text** or __text__)
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
                .replace(Regex("__(.+?)__"), "<strong>$1</strong>")
                // Italic (*text* or _text_)
                .replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "<em>$1</em>")
                .replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "<em>$1</em>")
                // Inline code (`code`)
                .replace(Regex("`([^`]+)`"), "<code style='background-color:#f0f0f0;padding:1px 4px;border-radius:3px;font-family:monospace;'>$1</code>")
                // Links [text](url)
                .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href='$2'>$1</a>")

            // Empty lines become paragraph breaks
            if (processedLine.isBlank()) {
                result.append("<br/>")
            } else if (!processedLine.startsWith("<h") && !processedLine.startsWith("<li") &&
                       !processedLine.startsWith("<hr") && !processedLine.startsWith("<ul") &&
                       !processedLine.startsWith("<ol") && !processedLine.startsWith("<pre")) {
                result.append(processedLine).append("<br/>")
            } else {
                result.append(processedLine)
            }
        }

        // Close any open list
        if (inList) {
            result.append("</$listType>")
        }
        if (inCodeBlock) {
            result.append("</pre>")
        }

        return result.toString()
    }

    /**
     * Wrap HTML content with proper styling for the editor pane.
     */
    private fun wrapInHtmlStyle(content: String): String {
        val isDark = !JBColor.isBright()
        val textColor = if (isDark) "#bbbbbb" else "#333333"
        val linkColor = if (isDark) "#589df6" else "#4a90d9"
        val bgColor = if (isDark) "#2b2b2b" else "#ffffff"

        return """
            <html>
            <head>
                <style>
                    body {
                        font-family: '${UIUtil.getLabelFont().family}', sans-serif;
                        font-size: ${UIUtil.getLabelFont().size}pt;
                        color: $textColor;
                        background-color: $bgColor;
                        margin: 0;
                        padding: 4px;
                        line-height: 1.4;
                    }
                    a { color: $linkColor; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    h1, h2, h3, h4, h5, h6 { color: $textColor; font-weight: bold; }
                    code { font-size: ${UIUtil.getLabelFont().size - 1}pt; }
                    pre { font-size: ${UIUtil.getLabelFont().size - 1}pt; overflow-x: auto; }
                </style>
            </head>
            <body>$content</body>
            </html>
        """.trimIndent()
    }

    private fun updateSourceRepoSelector(pkg: UnifiedPackage) {
        sourceRepoComboBox.removeAllItems()

        if (pkg.availableRepositories.isNotEmpty()) {
            for (repo in pkg.availableRepositories) {
                sourceRepoComboBox.addItem(repo)
            }
            sourceRepoPanel.isVisible = pkg.availableRepositories.size > 1 || !pkg.isInstalled
            selectedSourceRepo = pkg.availableRepositories.firstOrNull()
        } else {
            sourceRepoPanel.isVisible = false
            selectedSourceRepo = null
        }
    }

    private fun setupDependenciesPanel() {
        val listScroll = JBScrollPane(dependenciesList).apply {
            preferredSize = Dimension(0, 100)
        }

        val controlPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(loadDependenciesButton)
        }

        dependenciesPanel.add(listScroll, BorderLayout.CENTER)
        dependenciesPanel.add(controlPanel, BorderLayout.SOUTH)

        loadDependenciesButton.addActionListener {
            loadTransitiveDependencies()
        }
    }

    private fun setupBuildFilePanel() {
        val infoPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(buildFilePathLabel, BorderLayout.CENTER)
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(showInBuildFileButton)
        }

        buildFilePanel.add(infoPanel, BorderLayout.CENTER)
        buildFilePanel.add(buttonPanel, BorderLayout.SOUTH)

        showInBuildFileButton.addActionListener {
            openBuildFile()
        }
    }

    private fun setupActionListeners() {
        installButton.addActionListener {
            selectedPackage?.let { pkg ->
                showInstallDialog(pkg)
            }
        }

        updateButton.addActionListener {
            selectedPackage?.let { pkg ->
                showUpdatePopup(pkg)
            }
        }

        downgradeButton.addActionListener {
            selectedPackage?.let { pkg ->
                showDowngradePopup(pkg)
            }
        }

        uninstallButton.addActionListener {
            selectedPackage?.let { pkg ->
                performUninstall(pkg)
            }
        }

        configureButton.addActionListener {
            selectedPackage?.let { pkg ->
                onConfigurePluginRequested?.invoke(pkg)
            }
        }

        homepageLink.addHyperlinkListener {
            val url = currentDetails?.homepage ?: selectedPackage?.homepage
            url?.let { BrowserUtil.browse(it) }
        }
    }

    private fun updateUI(pkg: UnifiedPackage?) {
        detailsPanel.removeAll()

        if (pkg == null) {
            detailsPanel.add(emptyPanel, BorderLayout.CENTER)
            currentDetails = null
        } else {
            // Reset state
            currentDetails = null
            dependenciesListModel.clear()
            dependenciesExpanded = false

            // Show content immediately with basic info
            updateBasicInfo(pkg)
            detailsPanel.add(contentPanel, BorderLayout.CENTER)

            // Fetch detailed metadata
            fetchPackageDetails(pkg)
        }

        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun updateBasicInfo(pkg: UnifiedPackage) {
        nameLabel.text = pkg.displayName

        // Version display
        if (pkg.isInstalled) {
            installedVersionLabel.text = pkg.installedVersion
            installedVersionLabel.isVisible = true
            notInstalledLabel.isVisible = false

            if (pkg.hasUpdate && pkg.latestVersion != null) {
                latestVersionLabel.text = "→ ${pkg.latestVersion} ${message("unified.details.available")}"
                latestVersionLabel.isVisible = true
            } else {
                latestVersionLabel.isVisible = false
            }
        } else {
            installedVersionLabel.isVisible = false
            notInstalledLabel.isVisible = true
            if (pkg.latestVersion != null) {
                latestVersionLabel.text = "${message("unified.details.latest")}: ${pkg.latestVersion}"
                latestVersionLabel.isVisible = true
            } else {
                latestVersionLabel.isVisible = false
            }
        }

        // Basic metadata from package - render as markdown/HTML
        setDescriptionText(pkg.description ?: message("unified.details.no.description"))
        publisherLabel.text = pkg.publisher
        groupValueLabel.text = pkg.publisher
        artifactValueLabel.text = pkg.name
        scopeValueLabel.text = mapScopeToUnified(pkg.scope) ?: message("common.NotAvailable")
        modulesValueLabel.text = if (pkg.modules.isNotEmpty()) pkg.modules.joinToString(", ") else message("common.NotAvailable")
        licenseValueLabel.text = pkg.license ?: message("unified.details.loading")
        sourceValueLabel.text = getSourceDisplayName(pkg.source)

        // Homepage
        if (pkg.homepage != null) {
            homepageLink.setHyperlinkText(pkg.homepage)
            homepageLink.isVisible = true
        } else {
            homepageLink.setHyperlinkText(message("unified.details.loading"))
            homepageLink.isVisible = true
        }

        // Vulnerability info
        updateVulnerabilitySection(pkg)

        // Build file info
        updateBuildFileInfo(pkg)

        // Exclusions info
        updateExclusionsSection(pkg)

        // Source repository selector
        updateSourceRepoSelector(pkg)

        // Show dependencies section for installed packages
        dependenciesCollapsible.isVisible = true

        // Load available versions for version dropdown
        loadAvailableVersions(pkg)

        // Update button states
        updateButtonStates(pkg)
    }

    private fun fetchPackageDetails(pkg: UnifiedPackage) {
        isLoadingDetails = true
        loadingLabel.isVisible = true

        metadataService.getPackageDetails(pkg.publisher, pkg.name, pkg.installedVersion ?: pkg.latestVersion) { details ->
            isLoadingDetails = false
            loadingLabel.isVisible = false
            currentDetails = details

            if (selectedPackage?.id == pkg.id && details != null) {
                // Update UI with fetched details
                SwingUtilities.invokeLater {
                    updateWithFetchedDetails(details)
                }
            }
        }
    }

    private fun updateWithFetchedDetails(details: PackageDetails) {
        // Update description if we got a better one
        details.description?.let { desc ->
            setDescriptionText(desc)
        }

        // Update publisher with organization name if available
        publisherLabel.text = details.publisherName

        // Update license
        licenseValueLabel.text = details.licenseNames

        // Update homepage link
        val homepage = details.homepage
        if (homepage != null) {
            homepageLink.setHyperlinkText(homepage)
            homepageLink.isVisible = true
        } else {
            homepageLink.setHyperlinkText(message("common.NotAvailable"))
        }
    }

    private fun updateBuildFileInfo(pkg: UnifiedPackage) {
        val buildFilePath = when (val metadata = pkg.metadata) {
            is PackageMetadata.GradleMetadata -> metadata.buildFile
            is PackageMetadata.MavenInstalledMetadata -> metadata.pomFile
            is PackageMetadata.GradlePluginMetadata -> metadata.buildFile
            is PackageMetadata.MavenPluginMetadata -> metadata.pomFile
            else -> null
        }

        if (buildFilePath != null && pkg.isInstalled) {
            buildFileCollapsible.isVisible = true
            val fileName = buildFilePath.substringAfterLast("/")
            buildFilePathLabel.text = "${message("unified.details.declared.in")}: $fileName"
        } else {
            buildFileCollapsible.isVisible = false
        }
    }

    /**
     * Update the exclusions section.
     * Shows existing exclusions with remove buttons and an Add Exclusion button.
     */
    private fun updateExclusionsSection(pkg: UnifiedPackage) {
        exclusionsPanel.removeAll()

        // Only show for installed Gradle/Maven dependencies and plugins
        val isExclusionCapable = pkg.isInstalled && (
            pkg.source == PackageSource.GRADLE_INSTALLED ||
            pkg.source == PackageSource.MAVEN_INSTALLED ||
            pkg.source == PackageSource.GRADLE_PLUGIN_INSTALLED ||
            pkg.source == PackageSource.MAVEN_PLUGIN_INSTALLED
        )

        if (!isExclusionCapable) {
            exclusionsCollapsible.isVisible = false
            return
        }

        exclusionsCollapsible.isVisible = true
        val exclusions = pkg.exclusions

        if (exclusions.isEmpty()) {
            // Empty state with just Add Exclusion button
            val emptyLabel = JBLabel(message("unified.exclusion.empty")).apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            }
            exclusionsPanel.add(emptyLabel)
            exclusionsPanel.add(Box.createVerticalStrut(8))
        } else {
            // List each exclusion with a remove button
            for (exclusion in exclusions) {
                val row = JPanel(BorderLayout()).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, 28)
                    border = JBUI.Borders.empty(2, 0)

                    val label = JBLabel(exclusion.displayName).apply {
                        icon = AllIcons.Nodes.ExceptionClass
                    }
                    add(label, BorderLayout.CENTER)

                    val removeButton = JButton().apply {
                        icon = AllIcons.General.Remove
                        toolTipText = message("unified.exclusion.remove.button")
                        preferredSize = Dimension(24, 24)
                        isBorderPainted = false
                        isContentAreaFilled = false
                        addActionListener {
                            selectedPackage?.let { currentPkg ->
                                onExclusionRemoveRequested?.invoke(currentPkg, exclusion)
                            }
                        }
                    }
                    add(removeButton, BorderLayout.EAST)
                }
                exclusionsPanel.add(row)
            }
        }

        // Add Exclusion button
        val addButton = JButton(message("unified.exclusion.add.button")).apply {
            icon = AllIcons.General.Add
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                selectedPackage?.let { currentPkg ->
                    val dialog = ExclusionDialog(project, currentPkg.id)
                    if (dialog.showAndGet()) {
                        val newExclusion = dialog.getExclusion()
                        onExclusionAddRequested?.invoke(currentPkg, newExclusion)
                    }
                }
            }
        }
        exclusionsPanel.add(Box.createVerticalStrut(8))
        exclusionsPanel.add(addButton)

        exclusionsPanel.revalidate()
        exclusionsPanel.repaint()
    }

    /**
     * Update the vulnerability section with CVE info and fix version suggestion.
     */
    private fun updateVulnerabilitySection(pkg: UnifiedPackage) {
        vulnerabilityPanel.removeAll()

        val vulnInfo = pkg.vulnerabilityInfo
        if (vulnInfo == null) {
            vulnerabilityCollapsible.isVisible = false
            return
        }

        vulnerabilityCollapsible.isVisible = true

        // Severity badge
        val severityColor = when (vulnInfo.severity) {
            VulnerabilitySeverity.CRITICAL -> JBColor(0xD32F2F, 0xEF5350)
            VulnerabilitySeverity.HIGH -> JBColor(0xE64A19, 0xFF7043)
            VulnerabilitySeverity.MEDIUM -> JBColor(0xF57C00, 0xFFB74D)
            VulnerabilitySeverity.LOW -> JBColor(0xFBC02D, 0xFFF176)
            VulnerabilitySeverity.UNKNOWN -> JBColor.GRAY
        }

        val severityRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)

            add(JBLabel(message("unified.vulnerability.severity")).apply {
                foreground = labelColor
            })

            // Severity badge label
            add(JBLabel(vulnInfo.severity.name).apply {
                foreground = JBColor.WHITE
                isOpaque = true
                background = severityColor
                border = JBUI.Borders.empty(2, 8)
                font = font.deriveFont(Font.BOLD, 11f)
            })
        }
        vulnerabilityPanel.add(severityRow)
        vulnerabilityPanel.add(Box.createVerticalStrut(4))

        // CVE ID
        if (vulnInfo.cveId != null) {
            val cveRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 24)
                add(JBLabel(message("unified.vulnerability.cve")).apply {
                    foreground = labelColor
                })
                add(JBLabel(vulnInfo.cveId).apply {
                    foreground = severityColor
                    font = font.deriveFont(Font.BOLD)
                })
            }
            vulnerabilityPanel.add(cveRow)
            vulnerabilityPanel.add(Box.createVerticalStrut(4))
        }

        // Description
        if (vulnInfo.description != null) {
            val descLabel = JBLabel("<html><body style='width:240px'>${vulnInfo.description}</body></html>").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = UIUtil.getLabelForeground()
            }
            vulnerabilityPanel.add(descLabel)
            vulnerabilityPanel.add(Box.createVerticalStrut(4))
        }

        // Affected versions
        if (vulnInfo.affectedVersions != null) {
            val affectedRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 24)
                add(JBLabel(message("unified.vulnerability.affected")).apply {
                    foreground = labelColor
                })
                add(JBLabel(vulnInfo.affectedVersions))
            }
            vulnerabilityPanel.add(affectedRow)
            vulnerabilityPanel.add(Box.createVerticalStrut(4))
        }

        // Fix version suggestion - prominent display with action button
        if (vulnInfo.fixedVersion != null) {
            val fixRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 36)

                add(JBLabel(message("unified.vulnerability.fixed")).apply {
                    foreground = labelColor
                })

                // Highlight the fix version prominently
                add(JBLabel(vulnInfo.fixedVersion).apply {
                    foreground = JBColor(0x4CAF50, 0x81C784)
                    font = font.deriveFont(Font.BOLD, 13f)
                })

                // Add "Update to fix" button
                add(Box.createHorizontalStrut(8))
                add(JButton(message("unified.vulnerability.update.to.fix")).apply {
                    toolTipText = "Update to ${vulnInfo.fixedVersion} to fix this vulnerability"
                    addActionListener {
                        onUpdateRequested?.invoke(pkg, vulnInfo.fixedVersion!!)
                    }
                })
            }
            vulnerabilityPanel.add(fixRow)
            vulnerabilityPanel.add(Box.createVerticalStrut(4))
        }

        // Advisory link
        if (vulnInfo.advisoryUrl != null) {
            val advisoryLink = HyperlinkLabel(message("unified.vulnerability.advisory")).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            advisoryLink.addHyperlinkListener {
                BrowserUtil.browse(vulnInfo.advisoryUrl)
            }
            vulnerabilityPanel.add(advisoryLink)
            vulnerabilityPanel.add(Box.createVerticalStrut(8))
        }

        // Ignore button
        val ignoreRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)

            add(JButton(message("unified.vulnerability.ignore.button")).apply {
                toolTipText = message("unified.vulnerability.ignore.tooltip")
                addActionListener {
                    onIgnoreVulnerabilityRequested?.invoke(pkg, vulnInfo)
                }
            })
        }
        vulnerabilityPanel.add(ignoreRow)

        vulnerabilityPanel.revalidate()
        vulnerabilityPanel.repaint()
    }

    private fun loadTransitiveDependencies() {
        val pkg = selectedPackage ?: return
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return

        loadDependenciesButton.isEnabled = false
        loadDependenciesButton.text = message("unified.details.loading")

        transitiveDependencyService.getTransitiveDependencies(pkg.publisher, pkg.name, version) { deps ->
            SwingUtilities.invokeLater {
                dependenciesListModel.clear()
                for (dep in deps) {
                    val versionStr = dep.version ?: "managed"
                    val scopeStr = dep.scope?.let { " ($it)" } ?: ""
                    dependenciesListModel.addElement("${dep.groupId}:${dep.artifactId}:$versionStr$scopeStr")
                }

                if (deps.isEmpty()) {
                    dependenciesListModel.addElement(message("unified.details.no.dependencies"))
                }

                loadDependenciesButton.isEnabled = true
                loadDependenciesButton.text = message("unified.details.refresh.dependencies")
            }
        }
    }

    private fun openBuildFile() {
        val pkg = selectedPackage ?: return
        val (buildFilePath, offset) = when (val metadata = pkg.metadata) {
            is PackageMetadata.GradleMetadata -> Pair(metadata.buildFile, metadata.offset)
            is PackageMetadata.MavenInstalledMetadata -> Pair(metadata.pomFile, metadata.offset)
            is PackageMetadata.GradlePluginMetadata -> Pair(metadata.buildFile, metadata.offset)
            is PackageMetadata.MavenPluginMetadata -> Pair(metadata.pomFile, metadata.offset)
            else -> return
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(buildFilePath) ?: return
        val descriptor = if (offset > 0) {
            OpenFileDescriptor(project, virtualFile, offset)
        } else {
            OpenFileDescriptor(project, virtualFile)
        }
        FileEditorManager.getInstance(project).openEditor(descriptor, true)
    }

    private fun mapScopeToUnified(scope: String?): String? {
        if (scope == null) return null
        return when (scope.lowercase()) {
            "implementation", "compile" -> "Compile"
            "runtimeonly", "runtime" -> "Runtime"
            "testimplementation", "testcompile", "test" -> "Test"
            "compileonly", "provided" -> "Compile Only"
            "annotationprocessor", "kapt" -> "Annotation Processing"
            "api" -> "API"
            else -> scope.replaceFirstChar { it.uppercase() }
        }
    }

    private fun updateButtonStates(pkg: UnifiedPackage) {
        // Configure button: only visible for installed plugins
        val isPlugin = pkg.source == PackageSource.GRADLE_PLUGIN_INSTALLED ||
                pkg.source == PackageSource.MAVEN_PLUGIN_INSTALLED
        configureButton.isVisible = isPlugin && pkg.isInstalled
        configureButton.isEnabled = isPlugin && pkg.isInstalled

        when {
            !pkg.isInstalled -> {
                // Not installed: show Install only
                installButton.isVisible = true
                installButton.isEnabled = true
                updateButton.isVisible = false
                downgradeButton.isVisible = false
                uninstallButton.isVisible = false
            }
            pkg.hasUpdate -> {
                // Installed with update available: show Update, Downgrade, Uninstall
                installButton.isVisible = false
                updateButton.isVisible = true
                updateButton.isEnabled = true
                downgradeButton.isVisible = true
                downgradeButton.isEnabled = true
                uninstallButton.isVisible = true
                uninstallButton.isEnabled = true
            }
            else -> {
                // Installed, up to date: show Downgrade, Uninstall
                installButton.isVisible = false
                updateButton.isVisible = false
                downgradeButton.isVisible = true
                downgradeButton.isEnabled = true
                uninstallButton.isVisible = true
                uninstallButton.isEnabled = true
            }
        }
    }

    private fun showDowngradePopup(pkg: UnifiedPackage) {
        onVersionsNeeded?.invoke(pkg) { versions ->
            SwingUtilities.invokeLater {
                // Filter to versions older than current
                val currentVersion = pkg.installedVersion ?: return@invokeLater
                val olderVersions = versions.filter { isVersionOlder(it, currentVersion) }

                if (olderVersions.isEmpty()) {
                    // No older versions available
                    return@invokeLater
                }

                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(olderVersions)
                    .setTitle(message("unified.details.select.version"))
                    .setItemChosenCallback { version ->
                        onDowngradeRequested?.invoke(pkg, version)
                    }
                    .createPopup()
                popup.showUnderneathOf(downgradeButton)
            }
        }
    }

    /**
     * Simple version comparison - returns true if v1 is older than v2.
     * This is a simplified comparison that works for common version formats.
     */
    private fun isVersionOlder(v1: String, v2: String): Boolean {
        val parts1 = v1.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 < p2) return true
            if (p1 > p2) return false
        }
        return false
    }

    private fun getSourceDisplayName(source: PackageSource): String {
        return when (source) {
            PackageSource.GRADLE_INSTALLED -> "Gradle"
            PackageSource.MAVEN_INSTALLED -> "Maven (pom.xml)"
            PackageSource.MAVEN_CENTRAL -> "Maven Central"
            PackageSource.NPM -> "NPM Registry"
            PackageSource.NEXUS -> "Nexus"
            PackageSource.LOCAL_MAVEN -> "Local Maven"
            PackageSource.GRADLE_PLUGIN -> "Gradle Plugin Portal"
            PackageSource.GRADLE_PLUGIN_INSTALLED -> "Gradle Plugin (Installed)"
            PackageSource.MAVEN_PLUGIN_INSTALLED -> "Maven Plugin (Installed)"
        }
    }

    private fun showInstallDialog(pkg: UnifiedPackage) {
        onVersionsNeeded?.invoke(pkg) { versions ->
            SwingUtilities.invokeLater {
                availableVersions = versions.ifEmpty { listOfNotNull(pkg.latestVersion) }
                val modules = onModulesNeeded?.invoke() ?: listOf("app")

                // Use the selected source repository
                val sourceRepo = selectedSourceRepo

                val dialog = InstallPackageDialog(
                    project,
                    pkg,
                    availableVersions,
                    modules,
                    getAvailableConfigurations(),
                    pkg.availableRepositories,
                    sourceRepo
                )
                if (dialog.showAndGet()) {
                    onInstallRequested?.invoke(
                        pkg,
                        dialog.selectedVersion,
                        dialog.selectedModule,
                        dialog.selectedConfiguration,
                        dialog.selectedSourceRepo?.url
                    )
                }
            }
        }
    }

    private fun showUpdatePopup(pkg: UnifiedPackage) {
        onVersionsNeeded?.invoke(pkg) { versions ->
            SwingUtilities.invokeLater {
                val versionsToShow = versions.ifEmpty { listOfNotNull(pkg.latestVersion) }
                if (versionsToShow.size == 1) {
                    onUpdateRequested?.invoke(pkg, versionsToShow.first())
                } else {
                    val popup = JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(versionsToShow)
                        .setTitle(message("unified.details.select.version"))
                        .setItemChosenCallback { version ->
                            onUpdateRequested?.invoke(pkg, version)
                        }
                        .createPopup()
                    popup.showUnderneathOf(updateButton)
                }
            }
        }
    }

    private fun performUninstall(pkg: UnifiedPackage) {
        onUninstallRequested?.invoke(pkg)
    }

    private fun getAvailableConfigurations(): List<String> {
        return listOf(
            "implementation",
            "api",
            "compileOnly",
            "runtimeOnly",
            "testImplementation",
            "testRuntimeOnly",
            "testCompileOnly",
            "annotationProcessor",
            "kapt"
        )
    }

    fun setPackage(pkg: UnifiedPackage?) {
        selectedPackage = pkg
    }

    fun clearSelection() {
        selectedPackage = null
    }

    /**
     * Prefetch metadata for installed dependencies.
     */
    fun prefetchInstalledMetadata(dependencies: List<DependencyCoordinates>) {
        metadataService.prefetchInstalledDependencies(dependencies)
    }
}

/**
 * Custom renderer for dependency list items.
 */
private class DependencyListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (component is JLabel) {
            component.icon = AllIcons.Nodes.PpLib
            component.border = JBUI.Borders.empty(2, 4)
        }
        return component
    }
}

/**
 * Dialog for installing a new package.
 */
private class InstallPackageDialog(
    project: Project,
    private val pkg: UnifiedPackage,
    private val versions: List<String>,
    private val modules: List<String>,
    private val configurations: List<String>,
    private val availableRepos: List<AvailableRepository>,
    defaultRepo: AvailableRepository?
) : com.intellij.openapi.ui.DialogWrapper(project) {

    private val propertyGraph = PropertyGraph()

    val selectedVersionProperty = propertyGraph.property(versions.firstOrNull() ?: "")
    var selectedVersion by selectedVersionProperty

    val selectedModuleProperty = propertyGraph.property(modules.firstOrNull() ?: "")
    var selectedModule by selectedModuleProperty

    val selectedConfigurationProperty = propertyGraph.property("implementation")
    var selectedConfiguration by selectedConfigurationProperty

    var selectedSourceRepo: AvailableRepository? = defaultRepo ?: availableRepos.firstOrNull()

    init {
        title = message("unified.details.install.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row(message("unified.details.label.group")) {
                label(pkg.publisher)
            }
            row(message("unified.details.label.artifact")) {
                label(pkg.name)
            }

            // Show source repository selector if multiple repos available
            if (availableRepos.size > 1) {
                row("Source Repository:") {
                    val repoNames = availableRepos.map { "${it.name} ${it.version?.let { v -> "($v)" } ?: ""}" }
                    comboBox(repoNames).applyToComponent {
                        selectedIndex = availableRepos.indexOfFirst { it.id == selectedSourceRepo?.id }.coerceAtLeast(0)
                        addActionListener {
                            selectedSourceRepo = availableRepos.getOrNull(selectedIndex)
                        }
                    }
                }
            } else if (availableRepos.size == 1) {
                row("Source Repository:") {
                    label(availableRepos.first().name).applyToComponent {
                        foreground = JBColor.GRAY
                    }
                }
            }

            row(message("unified.details.label.version")) {
                comboBox(versions).bindItem(selectedVersionProperty)
            }
            row(message("gradle.manager.column.module")) {
                comboBox(modules).bindItem(selectedModuleProperty)
            }
            row(message("gradle.manager.column.configuration")) {
                comboBox(configurations).bindItem(selectedConfigurationProperty)
            }
        }
    }
}

/**
 * A collapsible section panel with a header that can be clicked to expand/collapse.
 */
class CollapsibleSectionPanel(
    title: String,
    initiallyExpanded: Boolean = true
) : JPanel(BorderLayout()) {

    private var isExpanded = initiallyExpanded
    private val contentPanel = JPanel(BorderLayout())
    private val chevronLabel = JBLabel()
    private val titleLabel = JBLabel(title)
    private val countLabel = JBLabel()

    init {
        isOpaque = false

        // Header panel (clickable)
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 0, 4, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            add(chevronLabel)
            add(titleLabel.apply {
                font = font.deriveFont(Font.BOLD, 12f)
            })
            add(countLabel.apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    toggleExpanded()
                }
            })
        }

        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)

        updateState()
    }

    /**
     * Set the content component for this collapsible section.
     */
    fun setContent(content: JComponent) {
        contentPanel.removeAll()
        contentPanel.add(content, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    /**
     * Set the count to display in the header (e.g., "12").
     */
    fun setCount(count: Int) {
        countLabel.text = if (count > 0) "($count)" else ""
    }

    /**
     * Toggle the expanded state.
     */
    fun toggleExpanded() {
        isExpanded = !isExpanded
        updateState()
    }

    /**
     * Set the expanded state programmatically.
     */
    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        updateState()
    }

    /**
     * Check if the section is currently expanded.
     */
    fun isExpanded(): Boolean = isExpanded

    private fun updateState() {
        chevronLabel.icon = if (isExpanded) {
            AllIcons.General.ArrowDown
        } else {
            AllIcons.General.ArrowRight
        }
        contentPanel.isVisible = isExpanded
        revalidate()
        repaint()
    }
}
