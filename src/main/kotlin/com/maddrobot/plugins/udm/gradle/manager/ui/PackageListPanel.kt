package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.maddrobot.plugins.udm.ui.SELECTED_PACKAGE_KEY
import com.maddrobot.plugins.udm.ui.SELECTED_PACKAGES_KEY
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.ui.PackageContextMenuBuilder
import com.maddrobot.plugins.udm.ui.PackageIcons
import com.maddrobot.plugins.udm.ui.StatusBadge
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Left panel containing the unified package list with collapsible sections.
 * Displays packages in a NuGet-style list with descriptions and status badges.
 * Shows Installed, Transitive, Updates, and Available sections in a single unified view.
 */
class PackageListPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) {
    // FilterMode is kept for backward compatibility but is no longer shown as tabs
    @Deprecated("Use unified view instead - filter modes are no longer displayed as tabs")
    enum class FilterMode {
        ALL,        // All packages (installed + search results)
        INSTALLED,  // Only installed packages
        UPDATES,    // Only packages with updates
        BROWSE      // Search results from repositories
    }

    /**
     * Section types for grouping packages.
     * Order matters: sections are displayed in enum order.
     */
    enum class SectionType {
        VULNERABLE,     // Packages with security vulnerabilities (highest priority)
        UPDATES,        // Packages with available updates
        INSTALLED,      // Directly installed packages
        PLUGINS,        // Gradle and Maven plugins
        TRANSITIVE,     // Implicitly installed (transitive) dependencies
        AVAILABLE       // Search results / available packages
    }

    // List items can be either a section header or a package
    sealed class ListItem {
        data class SectionHeader(
            val title: String,
            val count: Int = 0,
            val sectionType: SectionType,
            val isCollapsed: Boolean = false,
            val learnMoreText: String? = null
        ) : ListItem()
        data class PackageItem(val pkg: UnifiedPackage) : ListItem()
    }

    // Track collapsed sections
    private val collapsedSections = mutableSetOf<SectionType>()

    private val listModel = DefaultListModel<ListItem>()
    private val packageList = JBList(listModel)
    private var currentFilterMode = FilterMode.INSTALLED

    // All packages (before filtering)
    private var allPackages: List<UnifiedPackage> = emptyList()
    private var moduleFilter: String? = null

    // Search with debounce
    private val searchField = SearchTextField(true)
    private var searchTimer: javax.swing.Timer? = null
    private val searchDebounceMs = 300

    // Loading indicator
    private val loadingLabel = JBLabel(message("unified.list.loading")).apply {
        icon = AllIcons.Process.Step_1
        isVisible = false
    }

    // Empty state
    private val emptyPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            foreground = JBColor.GRAY
        }, BorderLayout.CENTER)
    }

    // Callbacks
    var onSelectionChanged: ((UnifiedPackage?) -> Unit)? = null
    var onFilterModeChanged: ((FilterMode) -> Unit)? = null
    var onSearchRequested: ((String) -> Unit)? = null
    var onRefreshRequested: (() -> Unit)? = null
    var onLearnMoreClicked: ((String) -> Unit)? = null

    // Context menu callbacks (set these from parent to enable actions)
    var contextMenuCallbacks: PackageContextMenuBuilder.ContextMenuCallbacks =
        PackageContextMenuBuilder.ContextMenuCallbacks()

    val component: JComponent

    init {
        component = createMainPanel()
        setupList()
        setupSearch()

        Disposer.register(parentDisposable) {
            searchTimer?.stop()
        }
    }

    private fun createMainPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            // Top: Filter tabs + Search
            add(createHeaderPanel(), BorderLayout.NORTH)

            // Center: List with scroll
            val contentPanel = JPanel(CardLayout()).apply {
                add(JBScrollPane(packageList).apply {
                    border = JBUI.Borders.empty()
                }, "list")
                add(emptyPanel, "empty")
            }
            add(contentPanel, BorderLayout.CENTER)

            // Bottom: Loading indicator
            add(loadingLabel.apply {
                border = JBUI.Borders.empty(4, 8)
            }, BorderLayout.SOUTH)
        }
    }

    private fun createHeaderPanel(): JPanel {
        // Note: The main toolbar is in UnifiedDependencyPanel.
        // This header is intentionally minimal - just for local list controls.
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            isOpaque = false
            // No filter tabs - the unified view shows all sections
        }
    }

    private fun setupList() {
        packageList.apply {
            cellRenderer = PackageListCellRenderer()
            // Enable multi-selection with Ctrl+Click and Shift+Click
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            fixedCellHeight = -1 // Variable height

            // Handle selection change
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val selectedItems = getSelectedPackages()
                    if (selectedItems.size == 1) {
                        onSelectionChanged?.invoke(selectedItems.first())
                    } else if (selectedItems.isEmpty()) {
                        onSelectionChanged?.invoke(null)
                    }
                    // For multi-select, keep the first selected package displayed
                    // but could update details panel to show "X packages selected"
                }
            }

            // Handle clicks on section headers (collapse/expand) and "Learn more" links
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = listModel.getElementAt(index)
                        if (item is ListItem.SectionHeader) {
                            val cellBounds = getCellBounds(index, index)
                            if (cellBounds != null) {
                                // Check if click was on the "Learn more" text
                                if (item.learnMoreText != null && isClickOnLearnMore(e.point, cellBounds, item)) {
                                    onLearnMoreClicked?.invoke(item.title)
                                } else {
                                    // Click on header - toggle collapse
                                    toggleSectionCollapse(item.sectionType)
                                }
                            }
                        }
                    }
                }
            })

            // Add right-click context menu
            addMouseListener(object : PopupHandler() {
                override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                    val index = locationToIndex(Point(x, y))
                    if (index >= 0) {
                        val item = listModel.getElementAt(index)
                        if (item is ListItem.PackageItem) {
                            // Ensure clicked item is selected
                            if (!isSelectedIndex(index)) {
                                selectedIndex = index
                            }
                            showContextMenu(comp, x, y)
                        }
                    }
                }
            })

            // Register keyboard shortcuts
            registerKeyboardShortcuts()
        }
    }

    /**
     * Register keyboard shortcuts for common actions.
     */
    private fun registerKeyboardShortcuts() {
        val inputMap = packageList.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = packageList.actionMap

        // DELETE - Remove selected package
        inputMap.put(KeyStroke.getKeyStroke("DELETE"), "deletePackage")
        actionMap.put("deletePackage", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val selected = getSelectedPackages()
                if (selected.isNotEmpty() && selected.all { it.isInstalled }) {
                    contextMenuCallbacks.onRemoveSelected(selected)
                }
            }
        })

        // ENTER - Toggle section collapse or show details
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "enterAction")
        actionMap.put("enterAction", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val index = packageList.selectedIndex
                if (index >= 0) {
                    val item = listModel.getElementAt(index)
                    if (item is ListItem.SectionHeader) {
                        toggleSectionCollapse(item.sectionType)
                    }
                }
            }
        })

        // Ctrl+D - Show dependency tree
        inputMap.put(KeyStroke.getKeyStroke("control D"), "showDependencyTree")
        actionMap.put("showDependencyTree", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val selected = getSelectedPackage()
                if (selected != null) {
                    contextMenuCallbacks.onShowDependencyTree(selected)
                }
            }
        })

        // Ctrl+U - Update selected package
        inputMap.put(KeyStroke.getKeyStroke("control U"), "updateSelected")
        actionMap.put("updateSelected", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val selected = getSelectedPackages().filter { it.getStatus().hasUpdate }
                if (selected.isNotEmpty()) {
                    contextMenuCallbacks.onUpgradeSelected(selected)
                }
            }
        })

        // Ctrl+I - Install selected package
        inputMap.put(KeyStroke.getKeyStroke("control I"), "installSelected")
        actionMap.put("installSelected", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val selected = getSelectedPackage()
                if (selected != null && !selected.isInstalled) {
                    contextMenuCallbacks.onInstall(selected)
                }
            }
        })
    }

    /**
     * Show context menu for the selected packages.
     */
    private fun showContextMenu(component: java.awt.Component, x: Int, y: Int) {
        val selectedPackagesList = getSelectedPackages()
        if (selectedPackagesList.isEmpty()) return

        val selectedPackage = selectedPackagesList.first()
        val actionGroup = PackageContextMenuBuilder.createContextMenu(
            project = project,
            selectedPackage = selectedPackage,
            selectedPackages = selectedPackagesList,
            callbacks = contextMenuCallbacks
        )

        // Create a DataContext that includes the selected package data
        val dataContext = object : DataContext {
            override fun getData(dataId: String): Any? {
                return when (dataId) {
                    SELECTED_PACKAGE_KEY.name -> selectedPackage
                    SELECTED_PACKAGES_KEY.name -> selectedPackagesList
                    else -> null
                }
            }
        }

        val popup = ActionManager.getInstance().createActionPopupMenu(
            ActionPlaces.POPUP,
            actionGroup
        )
        popup.setDataContext { dataContext }
        popup.component.show(component, x, y)
    }

    /**
     * Get all selected packages (supporting multi-select).
     */
    fun getSelectedPackages(): List<UnifiedPackage> {
        return packageList.selectedValuesList
            .filterIsInstance<ListItem.PackageItem>()
            .map { it.pkg }
    }

    private fun isClickOnLearnMore(point: Point, cellBounds: Rectangle, header: ListItem.SectionHeader): Boolean {
        // Approximate location of "Learn more" link
        val learnMoreX = cellBounds.x + cellBounds.width - 100
        return point.x >= learnMoreX && header.learnMoreText != null
    }

    private fun setupSearch() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                debounceSearch()
            }
        })
    }

    private fun debounceSearch() {
        searchTimer?.stop()
        searchTimer = javax.swing.Timer(searchDebounceMs) {
            val text = searchField.text.trim()
            if (text.isNotEmpty()) {
                // Trigger API search for non-empty text
                onSearchRequested?.invoke(text)
            } else {
                // Empty search - just apply local filters to show installed packages
                applyFilters()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * Set the current filter mode.
     * @deprecated Filter modes are no longer used - unified view always shows all sections
     */
    @Deprecated("Filter modes are no longer used in unified view")
    @Suppress("DEPRECATION")
    fun setFilterMode(mode: FilterMode) {
        currentFilterMode = mode
        // No-op - unified view always shows all sections
        // Kept for backward compatibility
        onFilterModeChanged?.invoke(mode)
    }

    /**
     * Get the current filter mode.
     * @deprecated Filter modes are no longer used - unified view always shows all sections
     */
    @Deprecated("Filter modes are no longer used in unified view")
    @Suppress("DEPRECATION")
    fun getFilterMode(): FilterMode = currentFilterMode

    /**
     * Set the packages to display.
     */
    fun setPackages(packages: List<UnifiedPackage>) {
        allPackages = packages
        applyFilters()
    }

    /**
     * Add packages to the current list (for incremental loading).
     */
    fun addPackages(packages: List<UnifiedPackage>) {
        allPackages = allPackages + packages
        applyFilters()
    }

    /**
     * Update a single package in the list (e.g., after ignoring a vulnerability).
     */
    fun updatePackage(updatedPkg: UnifiedPackage) {
        allPackages = allPackages.map { pkg ->
            if (pkg.id == updatedPkg.id) updatedPkg else pkg
        }
        applyFilters()
    }

    /**
     * Clear all packages.
     */
    fun clearPackages() {
        allPackages = emptyList()
        applyFilters()
    }

    /**
     * Set the module filter.
     */
    fun setModuleFilter(module: String?) {
        moduleFilter = module
        applyFilters()
    }

    /**
     * Apply all filters and rebuild the list with section headers.
     */
    private fun applyFilters() {
        var filtered = allPackages

        // Apply module filter
        if (moduleFilter != null) {
            filtered = filtered.filter { pkg ->
                pkg.modules.isEmpty() || pkg.modules.contains(moduleFilter)
            }
        }

        // Apply text filter
        val filterText = searchField.text.trim().lowercase()
        if (filterText.isNotEmpty()) {
            filtered = filtered.filter { pkg ->
                pkg.name.lowercase().contains(filterText) ||
                    pkg.publisher.lowercase().contains(filterText) ||
                    (pkg.description?.lowercase()?.contains(filterText) == true)
            }
        }

        // Build the list model with sections
        buildListModel(filtered)
        updateEmptyState()
    }

    /**
     * Build the list model with section headers and package items.
     * Uses a unified view showing all sections in priority order:
     * Vulnerable > Updates > Installed > Plugins > Transitive > Available.
     * Respects the collapsed state of each section.
     */
    @Suppress("DEPRECATION")
    private fun buildListModel(packages: List<UnifiedPackage>) {
        listModel.clear()

        // Unified view: Always show all relevant sections

        // 1. Separate packages into categories
        val vulnerable = packages.filter { it.isInstalled && it.isVulnerable }
        val withUpdates = packages.filter { it.isInstalled && it.hasUpdate && !it.isVulnerable }
        val installedDirect = packages.filter { it.isInstalled && !it.isTransitive && !it.isVulnerable && !it.hasUpdate && !isPlugin(it) }
        val plugins = packages.filter { it.isInstalled && isPlugin(it) && !it.isVulnerable && !it.hasUpdate }
        val installedTransitive = packages.filter { it.isInstalled && it.isTransitive && !it.isVulnerable }
        val available = packages.filter { !it.isInstalled }

        // 2. Add "Vulnerable" section (highest priority - security issues)
        if (vulnerable.isNotEmpty()) {
            val isCollapsed = collapsedSections.contains(SectionType.VULNERABLE)
            listModel.addElement(ListItem.SectionHeader(
                title = message("unified.list.section.vulnerable"),
                count = vulnerable.size,
                sectionType = SectionType.VULNERABLE,
                isCollapsed = isCollapsed
            ))
            if (!isCollapsed) {
                vulnerable.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }

        // 3. Add "Updates Available" section
        if (withUpdates.isNotEmpty()) {
            val isCollapsed = collapsedSections.contains(SectionType.UPDATES)
            listModel.addElement(ListItem.SectionHeader(
                title = message("unified.list.section.updates"),
                count = withUpdates.size,
                sectionType = SectionType.UPDATES,
                isCollapsed = isCollapsed
            ))
            if (!isCollapsed) {
                withUpdates.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }

        // 4. Add "Installed Packages" section (direct dependencies)
        if (installedDirect.isNotEmpty()) {
            val isCollapsed = collapsedSections.contains(SectionType.INSTALLED)
            listModel.addElement(ListItem.SectionHeader(
                title = message("unified.list.section.installed"),
                count = installedDirect.size,
                sectionType = SectionType.INSTALLED,
                isCollapsed = isCollapsed
            ))
            if (!isCollapsed) {
                installedDirect.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }

        // 5. Add "Plugins" section (Gradle and Maven plugins)
        if (plugins.isNotEmpty()) {
            val isCollapsed = collapsedSections.contains(SectionType.PLUGINS)
            listModel.addElement(ListItem.SectionHeader(
                title = message("unified.list.section.plugins"),
                count = plugins.size,
                sectionType = SectionType.PLUGINS,
                isCollapsed = isCollapsed
            ))
            if (!isCollapsed) {
                plugins.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }

        // 6. Add "Implicit (Transitive Packages)" section
        if (installedTransitive.isNotEmpty()) {
            val isCollapsed = collapsedSections.contains(SectionType.TRANSITIVE)
            listModel.addElement(ListItem.SectionHeader(
                title = message("unified.list.section.transitive"),
                count = installedTransitive.size,
                sectionType = SectionType.TRANSITIVE,
                isCollapsed = isCollapsed,
                learnMoreText = message("unified.list.learn.more")
            ))
            if (!isCollapsed) {
                installedTransitive.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }

        // 7. Add "Available Packages" section (search results / not installed)
        if (available.isNotEmpty()) {
            val isCollapsed = collapsedSections.contains(SectionType.AVAILABLE)
            listModel.addElement(ListItem.SectionHeader(
                title = message("unified.list.section.available"),
                count = available.size,
                sectionType = SectionType.AVAILABLE,
                isCollapsed = isCollapsed
            ))
            if (!isCollapsed) {
                available.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }
    }

    /**
     * Check if a package is a plugin (Gradle or Maven plugin).
     */
    private fun isPlugin(pkg: UnifiedPackage): Boolean {
        return pkg.source == com.maddrobot.plugins.udm.gradle.manager.model.PackageSource.GRADLE_PLUGIN_INSTALLED ||
            pkg.source == com.maddrobot.plugins.udm.gradle.manager.model.PackageSource.MAVEN_PLUGIN_INSTALLED ||
            pkg.scope == "plugin"
    }

    /**
     * Toggle the collapsed state of a section.
     */
    fun toggleSectionCollapse(sectionType: SectionType) {
        if (collapsedSections.contains(sectionType)) {
            collapsedSections.remove(sectionType)
        } else {
            collapsedSections.add(sectionType)
        }
        applyFilters()
    }

    /**
     * Collapse all sections.
     */
    fun collapseAllSections() {
        collapsedSections.addAll(SectionType.entries)
        applyFilters()
    }

    /**
     * Expand all sections.
     */
    fun expandAllSections() {
        collapsedSections.clear()
        applyFilters()
    }

    /**
     * Get the currently selected package (first selected if multi-select).
     */
    fun getSelectedPackage(): UnifiedPackage? {
        return getSelectedPackages().firstOrNull()
    }

    /**
     * Select a package by its ID.
     */
    fun selectPackage(packageId: String) {
        for (i in 0 until listModel.size()) {
            val item = listModel.getElementAt(i)
            if (item is ListItem.PackageItem && item.pkg.id == packageId) {
                packageList.selectedIndex = i
                packageList.ensureIndexIsVisible(i)
                break
            }
        }
    }

    /**
     * Show/hide loading indicator.
     */
    fun setLoading(loading: Boolean) {
        loadingLabel.isVisible = loading
    }

    @Suppress("DEPRECATION")
    private fun updateEmptyState() {
        val isEmpty = listModel.isEmpty
        val contentPanel = component.getComponent(1) as JPanel
        val layout = contentPanel.layout as CardLayout

        if (isEmpty) {
            val emptyLabel = (emptyPanel.getComponent(0) as JBLabel)
            emptyLabel.text = message("unified.list.empty")
            layout.show(contentPanel, "empty")
        } else {
            layout.show(contentPanel, "list")
        }
    }

    /**
     * Get the search text.
     */
    fun getSearchText(): String = searchField.text.trim()

    /**
     * Clear the search text.
     */
    fun clearSearch() {
        searchField.text = ""
        applyFilters()
    }

    /**
     * Custom cell renderer for the package list.
     * Renders section headers and package items differently.
     */
    private inner class PackageListCellRenderer : ListCellRenderer<ListItem> {
        private val headerPanel = SectionHeaderPanel()
        private val packagePanel = PackageItemPanel()

        override fun getListCellRendererComponent(
            list: JList<out ListItem>,
            value: ListItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return when (value) {
                is ListItem.SectionHeader -> headerPanel.apply {
                    setHeader(value)
                }
                is ListItem.PackageItem -> packagePanel.apply {
                    setPackage(value.pkg, isSelected, cellHasFocus)
                }
            }
        }
    }

    /**
     * Panel for rendering section headers with collapse/expand chevron.
     */
    private inner class SectionHeaderPanel : JPanel(BorderLayout()) {
        private val chevronLabel = JBLabel()
        private val titleLabel = JBLabel()
        private val countLabel = JBLabel()
        private val learnMoreLabel = JBLabel()

        init {
            border = JBUI.Borders.empty(12, 8, 8, 8)
            isOpaque = true
            background = UIUtil.getPanelBackground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Left: Chevron, Title and count
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(chevronLabel.apply {
                    icon = AllIcons.General.ArrowDown
                })
                add(titleLabel.apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                })
                add(countLabel.apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(11f)
                })
            }

            // Right: Learn more link
            learnMoreLabel.apply {
                foreground = JBColor(0x4A90D9, 0x589DF6)
                font = font.deriveFont(11f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            add(leftPanel, BorderLayout.WEST)
            add(learnMoreLabel, BorderLayout.EAST)
        }

        fun setHeader(header: ListItem.SectionHeader) {
            // Show chevron indicating collapsed/expanded state
            chevronLabel.icon = if (header.isCollapsed) {
                AllIcons.General.ArrowRight
            } else {
                AllIcons.General.ArrowDown
            }
            titleLabel.text = header.title
            countLabel.text = if (header.count > 0) "(${header.count})" else ""
            learnMoreLabel.text = header.learnMoreText ?: ""
            learnMoreLabel.isVisible = header.learnMoreText != null
        }
    }

    /**
     * Panel for rendering package items with status badges and icons.
     */
    private inner class PackageItemPanel : JPanel(BorderLayout()) {
        private val iconLabel = JBLabel()
        private val nameLabel = JBLabel()
        private val publisherLabel = JBLabel()
        private val descriptionLabel = JBLabel()
        private val badgePanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))

        // Status colors
        private val vulnerableColor = JBColor(0xF44336, 0xE57373)
        private val updateColor = JBColor(0x4CAF50, 0x81C784)
        private val deprecatedColor = JBColor(0xFF9800, 0xFFB74D)
        private val transitiveColor = JBColor(0x9E9E9E, 0x757575)
        private val prereleaseColor = JBColor(0x9C27B0, 0xBA68C8)

        init {
            border = JBUI.Borders.empty(8, 8, 8, 8)
            isOpaque = true

            // Left: Icon (will be set based on package status)
            iconLabel.apply {
                icon = AllIcons.Nodes.PpLib
                border = JBUI.Borders.emptyRight(8)
            }

            // Center: Name, publisher, description
            val textPanel = JPanel(GridBagLayout()).apply {
                isOpaque = false
                val gbc = GridBagConstraints().apply {
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    gridx = 0
                }

                // Row 1: Name and publisher
                val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(nameLabel.apply {
                        font = font.deriveFont(Font.BOLD, 13f)
                    })
                    add(JBLabel("by").apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(11f)
                    })
                    add(publisherLabel.apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(11f)
                    })
                }
                gbc.gridy = 0
                add(topRow, gbc)

                // Row 2: Description
                gbc.gridy = 1
                gbc.insets = JBUI.insets(2, 0, 0, 0)
                add(descriptionLabel.apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(12f)
                }, gbc)
            }

            // Right: Status badges panel
            badgePanel.apply {
                isOpaque = false
            }

            add(iconLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
            add(badgePanel, BorderLayout.EAST)
        }

        fun setPackage(pkg: UnifiedPackage, isSelected: Boolean, cellHasFocus: Boolean) {
            background = if (isSelected) UIUtil.getListSelectionBackground(cellHasFocus) else UIUtil.getListBackground()
            val textColor = if (isSelected) UIUtil.getListSelectionForeground(cellHasFocus) else UIUtil.getListForeground()

            // Set icon based on package status
            val status = pkg.getStatus()
            iconLabel.icon = PackageIcons.getLibraryIcon(
                isInstalled = pkg.isInstalled,
                hasUpdate = status.hasUpdate,
                isVulnerable = status.isVulnerable,
                isTransitive = status.isTransitive,
                isDeprecated = status.isDeprecated
            )

            nameLabel.text = pkg.name
            nameLabel.foreground = when {
                status.isVulnerable -> vulnerableColor
                status.isDeprecated -> deprecatedColor
                else -> textColor
            }

            publisherLabel.text = pkg.publisher
            publisherLabel.foreground = if (isSelected) textColor else JBColor.GRAY

            // Description - truncate if too long
            val desc = pkg.description?.take(100)?.let {
                if (pkg.description!!.length > 100) "$it..." else it
            } ?: message("unified.details.no.description")
            descriptionLabel.text = desc
            descriptionLabel.foreground = if (isSelected) textColor else JBColor.GRAY

            // Clear and rebuild badge panel
            badgePanel.removeAll()

            // Add version badge (with special formatting for transitive)
            val version = pkg.installedVersion ?: pkg.latestVersion ?: ""
            if (version.isNotEmpty()) {
                val versionText = if (status.isTransitive) "($version)" else version
                val versionBadge = StatusBadge(StatusBadge.BadgeType.VERSION, versionText)
                badgePanel.add(versionBadge)
            }

            // Add status badges in priority order
            if (status.isVulnerable) {
                badgePanel.add(StatusBadge(StatusBadge.BadgeType.VULNERABLE))
            }
            if (status.hasUpdate && status.updateVersion != null) {
                badgePanel.add(StatusBadge.updateBadge(status.updateVersion))
            }
            if (status.isDeprecated) {
                badgePanel.add(StatusBadge(StatusBadge.BadgeType.DEPRECATED))
            }
            if (status.isTransitive) {
                badgePanel.add(StatusBadge(StatusBadge.BadgeType.TRANSITIVE))
            }
            if (status.isPrerelease && !status.hasUpdate) {
                badgePanel.add(StatusBadge(StatusBadge.BadgeType.PRERELEASE))
            }

            // Add plugin badge for plugins (helps distinguish from dependencies with same name)
            if (isPlugin(pkg)) {
                badgePanel.add(StatusBadge(StatusBadge.BadgeType.PLUGIN))
            }

            // Set tooltip with detailed status info
            toolTipText = buildStatusTooltip(pkg, status)

            badgePanel.revalidate()
            badgePanel.repaint()
        }

        private fun buildStatusTooltip(pkg: UnifiedPackage, status: com.maddrobot.plugins.udm.gradle.manager.model.PackageStatus): String {
            val sb = StringBuilder("<html>")
            sb.append("<b>${pkg.id}</b><br>")

            if (pkg.isInstalled) {
                sb.append("Installed: ${pkg.installedVersion}<br>")
            }
            if (status.hasUpdate) {
                sb.append("<font color='green'>Update available: ${status.updateVersion}</font><br>")
            }
            if (status.isVulnerable) {
                sb.append("<font color='red'>Security vulnerability detected</font><br>")
                status.vulnerabilityInfo?.let { vuln ->
                    vuln.cveId?.let { sb.append("CVE: $it<br>") }
                    sb.append("Severity: ${vuln.severity}<br>")
                    vuln.fixedVersion?.let { sb.append("Fixed in: $it<br>") }
                }
            }
            if (status.isDeprecated) {
                sb.append("<font color='orange'>Deprecated</font><br>")
                status.deprecationMessage?.let { sb.append(it) }
            }
            if (status.isTransitive) {
                sb.append("<font color='gray'>Transitive dependency (cannot be modified directly)</font><br>")
            }
            if (status.isPrerelease) {
                sb.append("<font color='purple'>Prerelease version</font><br>")
            }

            sb.append("</html>")
            return sb.toString()
        }
    }

    /**
     * Custom JLabel that can overlay a diagonal update arrow on its icon.
     */
    private class UpdateIndicatorLabel : JBLabel() {
        var showUpdateIndicator: Boolean = false

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            if (showUpdateIndicator && icon != null) {
                val g2d = g.create() as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // Draw a small diagonal arrow in the bottom-right corner of the icon
                val iconWidth = icon.iconWidth
                val iconHeight = icon.iconHeight

                // Arrow position (bottom-right corner)
                val arrowSize = 8
                val arrowX = iconWidth - arrowSize + 2
                val arrowY = iconHeight - arrowSize + 2

                // Draw arrow background circle
                g2d.color = JBColor(0x4CAF50, 0x81C784) // Green
                g2d.fillOval(arrowX - 1, arrowY - 1, arrowSize + 2, arrowSize + 2)

                // Draw diagonal arrow pointing up-right
                g2d.color = Color.WHITE
                g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                val path = Path2D.Float()
                val cx = arrowX + arrowSize / 2f
                val cy = arrowY + arrowSize / 2f
                val offset = 2.5f

                // Arrow line (diagonal)
                path.moveTo((cx - offset).toDouble(), (cy + offset).toDouble())
                path.lineTo((cx + offset).toDouble(), (cy - offset).toDouble())
                g2d.draw(path)

                // Arrow head
                val headPath = Path2D.Float()
                headPath.moveTo((cx + offset - 2).toDouble(), (cy - offset).toDouble())
                headPath.lineTo((cx + offset).toDouble(), (cy - offset).toDouble())
                headPath.lineTo((cx + offset).toDouble(), (cy - offset + 2).toDouble())
                g2d.draw(headPath)

                g2d.dispose()
            }
        }
    }
}
