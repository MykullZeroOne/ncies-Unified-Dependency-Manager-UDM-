package star.intellijplugin.pkgfinder.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import star.intellijplugin.pkgfinder.gradle.manager.model.UnifiedPackage
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Left panel containing the package list with filter tabs, search, and section headers.
 * Displays packages in a NuGet-style list with descriptions and "Learn more" links.
 */
class PackageListPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) {
    enum class FilterMode {
        ALL,        // All packages (installed + search results)
        INSTALLED,  // Only installed packages
        UPDATES,    // Only packages with updates
        BROWSE      // Search results from repositories
    }

    // List items can be either a section header or a package
    sealed class ListItem {
        data class SectionHeader(val title: String, val count: Int = 0, val learnMoreText: String? = null) : ListItem()
        data class PackageItem(val pkg: UnifiedPackage) : ListItem()
    }

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

    // Filter tabs
    private val filterButtons = mutableMapOf<FilterMode, JToggleButton>()
    private val buttonGroup = ButtonGroup()

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

    val component: JComponent

    init {
        component = createMainPanel()
        setupList()
        setupSearch()
        setFilterMode(FilterMode.INSTALLED)

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
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)

            // Filter tabs
            val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                for (mode in FilterMode.entries) {
                    val button = createFilterButton(mode)
                    filterButtons[mode] = button
                    buttonGroup.add(button)
                    add(button)
                }
            }

            // Search field
            searchField.apply {
                textEditor.emptyText.text = message("unified.list.search.placeholder")
                preferredSize = Dimension(200, preferredSize.height)
            }

            // Refresh button
            val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = message("gradle.manager.button.refresh")
                isBorderPainted = false
                isContentAreaFilled = false
                addActionListener { onRefreshRequested?.invoke() }
            }

            // Right side panel
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(searchField)
                add(refreshButton)
            }

            add(filterPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    private fun createFilterButton(mode: FilterMode): JToggleButton {
        val text = when (mode) {
            FilterMode.ALL -> message("unified.filter.all")
            FilterMode.INSTALLED -> message("unified.filter.installed")
            FilterMode.UPDATES -> message("unified.filter.updates")
            FilterMode.BROWSE -> message("unified.filter.browse")
        }

        return JToggleButton(text).apply {
            isFocusPainted = false
            margin = JBUI.insets(4, 12)
            addActionListener {
                if (isSelected) {
                    setFilterMode(mode)
                }
            }
        }
    }

    private fun setupList() {
        packageList.apply {
            cellRenderer = PackageListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = -1 // Variable height

            // Handle selection
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val selectedValue = selectedValue
                    if (selectedValue is ListItem.PackageItem) {
                        onSelectionChanged?.invoke(selectedValue.pkg)
                    }
                }
            }

            // Handle click on "Learn more" links
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index >= 0) {
                        val item = listModel.getElementAt(index)
                        if (item is ListItem.SectionHeader && item.learnMoreText != null) {
                            // Check if click was on the "Learn more" text
                            val cellBounds = getCellBounds(index, index)
                            if (cellBounds != null && isClickOnLearnMore(e.point, cellBounds, item)) {
                                onLearnMoreClicked?.invoke(item.title)
                            }
                        }
                    }
                }
            })
        }
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
            if (currentFilterMode == FilterMode.BROWSE) {
                // In Browse mode, trigger API search
                onSearchRequested?.invoke(text)
            } else {
                // In other modes, filter locally
                applyFilters()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * Set the current filter mode.
     */
    fun setFilterMode(mode: FilterMode) {
        if (currentFilterMode != mode || !filterButtons[mode]!!.isSelected) {
            currentFilterMode = mode
            filterButtons[mode]?.isSelected = true

            // Update search placeholder
            searchField.textEditor.emptyText.text = when (mode) {
                FilterMode.BROWSE -> message("unified.list.search.placeholder.browse")
                else -> message("unified.list.search.placeholder")
            }

            onFilterModeChanged?.invoke(mode)
        }
    }

    /**
     * Get the current filter mode.
     */
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
     */
    private fun buildListModel(packages: List<UnifiedPackage>) {
        listModel.clear()

        when (currentFilterMode) {
            FilterMode.INSTALLED, FilterMode.ALL -> {
                // Separate direct and transitive dependencies
                val directDeps = packages.filter { !it.isTransitive }
                val transitiveDeps = packages.filter { it.isTransitive }

                // Add "Installed Packages" section
                if (directDeps.isNotEmpty()) {
                    listModel.addElement(ListItem.SectionHeader(
                        message("unified.list.section.installed"),
                        directDeps.size
                    ))
                    directDeps.forEach { pkg ->
                        listModel.addElement(ListItem.PackageItem(pkg))
                    }
                }

                // Add "Implicitly Installed (Transitive)" section
                if (transitiveDeps.isNotEmpty()) {
                    listModel.addElement(ListItem.SectionHeader(
                        message("unified.list.section.transitive"),
                        transitiveDeps.size,
                        message("unified.list.learn.more")
                    ))
                    transitiveDeps.forEach { pkg ->
                        listModel.addElement(ListItem.PackageItem(pkg))
                    }
                }
            }

            FilterMode.UPDATES -> {
                if (packages.isNotEmpty()) {
                    listModel.addElement(ListItem.SectionHeader(
                        message("unified.filter.updates"),
                        packages.size
                    ))
                    packages.forEach { pkg ->
                        listModel.addElement(ListItem.PackageItem(pkg))
                    }
                }
            }

            FilterMode.BROWSE -> {
                // No section headers for search results
                packages.forEach { pkg ->
                    listModel.addElement(ListItem.PackageItem(pkg))
                }
            }
        }
    }

    /**
     * Get the currently selected package.
     */
    fun getSelectedPackage(): UnifiedPackage? {
        val selected = packageList.selectedValue
        return if (selected is ListItem.PackageItem) selected.pkg else null
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

    private fun updateEmptyState() {
        val isEmpty = listModel.isEmpty
        val contentPanel = component.getComponent(1) as JPanel
        val layout = contentPanel.layout as CardLayout

        if (isEmpty) {
            val emptyLabel = (emptyPanel.getComponent(0) as JBLabel)
            emptyLabel.text = when (currentFilterMode) {
                FilterMode.INSTALLED -> message("gradle.manager.installed.empty")
                FilterMode.UPDATES -> message("gradle.manager.updates.empty")
                FilterMode.BROWSE -> message("unified.list.browse.empty")
                FilterMode.ALL -> message("unified.list.all.empty")
            }
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
     * Panel for rendering section headers.
     */
    private inner class SectionHeaderPanel : JPanel(BorderLayout()) {
        private val titleLabel = JBLabel()
        private val countLabel = JBLabel()
        private val learnMoreLabel = JBLabel()

        init {
            border = JBUI.Borders.empty(12, 8, 8, 8)
            isOpaque = true
            background = UIUtil.getPanelBackground()

            // Left: Title and count
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
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
            titleLabel.text = header.title
            countLabel.text = if (header.count > 0) "(${header.count})" else ""
            learnMoreLabel.text = header.learnMoreText ?: ""
            learnMoreLabel.isVisible = header.learnMoreText != null
        }
    }

    /**
     * Panel for rendering package items.
     */
    private inner class PackageItemPanel : JPanel(BorderLayout()) {
        private val iconLabel = JBLabel()
        private val nameLabel = JBLabel()
        private val publisherLabel = JBLabel()
        private val descriptionLabel = JBLabel()
        private val versionBadge = JBLabel()
        private val updateBadge = JBLabel()

        init {
            border = JBUI.Borders.empty(8, 8, 8, 8)
            isOpaque = true

            // Left: Icon
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

            // Right: Version badges
            val badgePanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(versionBadge.apply {
                    font = font.deriveFont(11f)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor.border(), 1, true),
                        JBUI.Borders.empty(2, 6)
                    )
                })
                add(updateBadge.apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor(0x107C10, 0x4CAF50) // Green
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(0x107C10, 0x4CAF50), 1, true),
                        JBUI.Borders.empty(2, 6)
                    )
                })
            }

            add(iconLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
            add(badgePanel, BorderLayout.EAST)
        }

        fun setPackage(pkg: UnifiedPackage, isSelected: Boolean, cellHasFocus: Boolean) {
            background = if (isSelected) UIUtil.getListSelectionBackground(cellHasFocus) else UIUtil.getListBackground()
            val textColor = if (isSelected) UIUtil.getListSelectionForeground(cellHasFocus) else UIUtil.getListForeground()

            nameLabel.text = pkg.name
            nameLabel.foreground = textColor
            publisherLabel.text = pkg.publisher
            publisherLabel.foreground = if (isSelected) textColor else JBColor.GRAY

            // Description - truncate if too long
            val desc = pkg.description?.take(100)?.let {
                if (pkg.description!!.length > 100) "$it..." else it
            } ?: message("unified.details.no.description")
            descriptionLabel.text = desc
            descriptionLabel.foreground = if (isSelected) textColor else JBColor.GRAY

            // Version badge
            val version = pkg.installedVersion ?: pkg.latestVersion ?: ""
            versionBadge.text = version
            versionBadge.isVisible = version.isNotEmpty()

            // Update badge
            val hasUpdate = pkg.installedVersion != null && pkg.latestVersion != null &&
                pkg.installedVersion != pkg.latestVersion
            if (hasUpdate) {
                updateBadge.text = "↑ ${pkg.latestVersion}"
                updateBadge.isVisible = true
            } else {
                updateBadge.isVisible = false
            }
        }
    }
}
