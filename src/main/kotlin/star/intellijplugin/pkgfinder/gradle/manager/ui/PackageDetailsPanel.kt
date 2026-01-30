package star.intellijplugin.pkgfinder.gradle.manager.ui

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
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import star.intellijplugin.pkgfinder.gradle.manager.GradleDependencyModifier
import star.intellijplugin.pkgfinder.gradle.manager.model.PackageMetadata
import star.intellijplugin.pkgfinder.gradle.manager.model.PackageSource
import star.intellijplugin.pkgfinder.gradle.manager.model.UnifiedPackage
import star.intellijplugin.pkgfinder.gradle.manager.service.DependencyCoordinates
import star.intellijplugin.pkgfinder.gradle.manager.service.PackageDetails
import star.intellijplugin.pkgfinder.gradle.manager.service.PackageMetadataService
import star.intellijplugin.pkgfinder.gradle.manager.service.TransitiveDependencyService
import java.awt.*
import javax.swing.*
import javax.swing.border.TitledBorder

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

    // === DESCRIPTION SECTION ===
    private val descriptionLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, 12f)
        text = message("unified.details.section.description")
    }
    private val descriptionText = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4)
        font = UIUtil.getLabelFont()
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

    // === DEPENDENCIES SECTION (Expandable) ===
    private val dependenciesPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            message("unified.details.section.dependencies"),
            TitledBorder.LEFT,
            TitledBorder.TOP
        )
        isVisible = false
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

    // === BUILD FILE SECTION (Expandable) ===
    private val buildFilePanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            message("unified.details.section.buildfile"),
            TitledBorder.LEFT,
            TitledBorder.TOP
        )
        isVisible = false
    }
    private val buildFilePathLabel = JBLabel()
    private val showInBuildFileButton = JButton(message("unified.details.show.in.buildfile")).apply {
        icon = AllIcons.Actions.OpenNewTab
    }

    // === ACTION BUTTONS ===
    private val primaryButtonColor = JBColor(0x4A90D9, 0x3574B0)
    private val primaryButtonTextColor = JBColor.WHITE

    private val installButton = JButton(message("unified.details.button.install")).apply {
        preferredSize = Dimension(0, 40)
        background = primaryButtonColor
        foreground = primaryButtonTextColor
        isOpaque = true
        isBorderPainted = false
        font = font.deriveFont(Font.BOLD)
    }
    private val updateButton = JButton(message("unified.details.button.update")).apply {
        preferredSize = Dimension(0, 40)
        background = primaryButtonColor
        foreground = primaryButtonTextColor
        isOpaque = true
        isBorderPainted = false
        font = font.deriveFont(Font.BOLD)
    }
    private val downgradeButton = JButton(message("unified.details.button.downgrade")).apply {
        preferredSize = Dimension(0, 40)
    }
    private val uninstallButton = JButton(message("unified.details.button.uninstall")).apply {
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
    var onInstallRequested: ((UnifiedPackage, String, String, String) -> Unit)? = null
    var onUpdateRequested: ((UnifiedPackage, String) -> Unit)? = null
    var onDowngradeRequested: ((UnifiedPackage, String) -> Unit)? = null
    var onUninstallRequested: ((UnifiedPackage) -> Unit)? = null
    var onVersionsNeeded: ((UnifiedPackage, (List<String>) -> Unit) -> Unit)? = null
    var onModulesNeeded: (() -> List<String>)? = null

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
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(16)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 80)

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
            }
            add(nameVersionPanel, BorderLayout.CENTER)
            add(loadingLabel, BorderLayout.EAST)
        }

        // === DESCRIPTION ===
        val descriptionPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(12)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 120)

            add(descriptionLabel, BorderLayout.NORTH)
            val descScroll = JBScrollPane(descriptionText).apply {
                preferredSize = Dimension(0, 80)
                border = JBUI.Borders.empty()
            }
            add(descScroll, BorderLayout.CENTER)
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

        // === METADATA GRID ===
        val metadataPanel = createMetadataGrid().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        // === DEPENDENCIES SECTION ===
        setupDependenciesPanel()
        dependenciesPanel.alignmentX = Component.LEFT_ALIGNMENT
        dependenciesPanel.maximumSize = Dimension(Int.MAX_VALUE, 150)

        // === BUILD FILE SECTION ===
        setupBuildFilePanel()
        buildFilePanel.alignmentX = Component.LEFT_ALIGNMENT
        buildFilePanel.maximumSize = Dimension(Int.MAX_VALUE, 80)

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
            downgradeButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            downgradeButton.alignmentX = Component.LEFT_ALIGNMENT
            uninstallButton.maximumSize = Dimension(Int.MAX_VALUE, 40)
            uninstallButton.alignmentX = Component.LEFT_ALIGNMENT

            add(installButton)
            add(Box.createVerticalStrut(8))
            add(updateButton)
            add(Box.createVerticalStrut(8))
            add(downgradeButton)
            add(Box.createVerticalStrut(8))
            add(uninstallButton)
        }

        // Assemble main panel
        mainPanel.add(headerPanel)
        mainPanel.add(descriptionPanel)
        mainPanel.add(publisherPanel)
        mainPanel.add(metadataPanel)
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(dependenciesPanel)
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(buildFilePanel)
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

        // Basic metadata from package
        descriptionText.text = pkg.description ?: message("unified.details.no.description")
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

        // Build file info
        updateBuildFileInfo(pkg)

        // Show dependencies section for installed packages
        dependenciesPanel.isVisible = true

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
            descriptionText.text = desc
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
            else -> null
        }

        if (buildFilePath != null && pkg.isInstalled) {
            buildFilePanel.isVisible = true
            val fileName = buildFilePath.substringAfterLast("/")
            buildFilePathLabel.text = "${message("unified.details.declared.in")}: $fileName"
        } else {
            buildFilePanel.isVisible = false
        }
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
        }
    }

    private fun showInstallDialog(pkg: UnifiedPackage) {
        onVersionsNeeded?.invoke(pkg) { versions ->
            SwingUtilities.invokeLater {
                availableVersions = versions.ifEmpty { listOfNotNull(pkg.latestVersion) }
                val modules = onModulesNeeded?.invoke() ?: listOf("app")
                val dialog = InstallPackageDialog(
                    project,
                    pkg,
                    availableVersions,
                    modules,
                    getAvailableConfigurations()
                )
                if (dialog.showAndGet()) {
                    onInstallRequested?.invoke(
                        pkg,
                        dialog.selectedVersion,
                        dialog.selectedModule,
                        dialog.selectedConfiguration
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
    private val configurations: List<String>
) : com.intellij.openapi.ui.DialogWrapper(project) {

    private val propertyGraph = PropertyGraph()

    val selectedVersionProperty = propertyGraph.property(versions.firstOrNull() ?: "")
    var selectedVersion by selectedVersionProperty

    val selectedModuleProperty = propertyGraph.property(modules.firstOrNull() ?: "")
    var selectedModule by selectedModuleProperty

    val selectedConfigurationProperty = propertyGraph.property("implementation")
    var selectedConfiguration by selectedConfigurationProperty

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
