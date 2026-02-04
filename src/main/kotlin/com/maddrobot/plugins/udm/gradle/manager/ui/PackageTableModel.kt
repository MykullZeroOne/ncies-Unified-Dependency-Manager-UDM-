package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.ui.TableColumnInfo
import java.util.Comparator

/**
 * Table model for displaying unified packages in the main list panel.
 * Supports different column configurations based on the current filter mode.
 */
open class PackageTableModel : ListTableModel<UnifiedPackage>() {

    enum class DisplayMode {
        ALL,        // Name, Publisher, Installed, Latest
        INSTALLED,  // Name, Publisher, Version, Scope, Module
        UPDATES,    // Name, Publisher, Current, Latest, Module
        BROWSE      // Name, Publisher, Latest, Description
    }

    private var currentMode = DisplayMode.ALL

    init {
        setColumns(ALL_COLUMNS)
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (currentMode != mode) {
            currentMode = mode
            val columns = when (mode) {
                DisplayMode.ALL -> ALL_COLUMNS
                DisplayMode.INSTALLED -> INSTALLED_COLUMNS
                DisplayMode.UPDATES -> UPDATES_COLUMNS
                DisplayMode.BROWSE -> BROWSE_COLUMNS
            }
            setColumns(columns)
        }
    }

    private fun setColumns(columns: Array<ColumnInfo<UnifiedPackage, *>>) {
        columnInfos = columns
        fireTableStructureChanged()
    }

    companion object {
        // Column definitions
        private val NAME_COLUMN = TableColumnInfo<UnifiedPackage>(message("unified.table.column.name")) { it.name }
        private val PUBLISHER_COLUMN = TableColumnInfo<UnifiedPackage>(message("unified.table.column.publisher")) { it.publisher }
        private val INSTALLED_VERSION_COLUMN = TableColumnInfo<UnifiedPackage>(message("unified.table.column.installed")) { it.installedVersion ?: "-" }
        private val LATEST_VERSION_COLUMN = TableColumnInfo<UnifiedPackage>(message("unified.table.column.latest")) { it.latestVersion ?: "-" }
        private val VERSION_COLUMN = TableColumnInfo<UnifiedPackage>(message("gradle.manager.column.version")) { it.installedVersion ?: it.latestVersion ?: "-" }
        private val SCOPE_COLUMN = TableColumnInfo<UnifiedPackage>(message("unified.table.column.scope")) { it.scope ?: "-" }
        private val MODULE_COLUMN = TableColumnInfo<UnifiedPackage>(message("gradle.manager.column.module")) { it.modules.firstOrNull() ?: "-" }
        private val DESCRIPTION_COLUMN = object : ColumnInfo<UnifiedPackage, String>(message("unified.table.column.description")) {
            override fun valueOf(item: UnifiedPackage): String = item.description?.take(100) ?: "-"
            override fun getPreferredStringValue(): String = "A".repeat(50)
        }

        // Column sets for different modes
        private val ALL_COLUMNS = arrayOf<ColumnInfo<UnifiedPackage, *>>(
            NAME_COLUMN,
            PUBLISHER_COLUMN,
            INSTALLED_VERSION_COLUMN,
            LATEST_VERSION_COLUMN
        )

        private val INSTALLED_COLUMNS = arrayOf<ColumnInfo<UnifiedPackage, *>>(
            NAME_COLUMN,
            PUBLISHER_COLUMN,
            VERSION_COLUMN,
            SCOPE_COLUMN,
            MODULE_COLUMN
        )

        private val UPDATES_COLUMNS = arrayOf<ColumnInfo<UnifiedPackage, *>>(
            NAME_COLUMN,
            PUBLISHER_COLUMN,
            INSTALLED_VERSION_COLUMN,
            LATEST_VERSION_COLUMN,
            MODULE_COLUMN
        )

        private val BROWSE_COLUMNS = arrayOf<ColumnInfo<UnifiedPackage, *>>(
            NAME_COLUMN,
            PUBLISHER_COLUMN,
            LATEST_VERSION_COLUMN,
            DESCRIPTION_COLUMN
        )
    }
}

/**
 * Extended table model that supports filtering and sorting.
 */
class FilterablePackageTableModel : PackageTableModel() {

    private var allItems: List<UnifiedPackage> = emptyList()
    private var filterText: String = ""
    private var moduleFilter: String? = null

    /**
     * Set the full list of items (before filtering).
     */
    fun setAllItems(packages: List<UnifiedPackage>) {
        allItems = packages
        applyFilters()
    }

    /**
     * Set the search/filter text.
     */
    fun setFilterText(text: String) {
        filterText = text.lowercase().trim()
        applyFilters()
    }

    /**
     * Set the module filter (null means all modules).
     */
    fun setModuleFilter(module: String?) {
        moduleFilter = module
        applyFilters()
    }

    private fun applyFilters() {
        var filtered = allItems

        // Apply module filter
        if (moduleFilter != null) {
            filtered = filtered.filter { pkg ->
                pkg.modules.isEmpty() || pkg.modules.contains(moduleFilter)
            }
        }

        // Apply text filter
        if (filterText.isNotEmpty()) {
            filtered = filtered.filter { pkg ->
                pkg.name.lowercase().contains(filterText) ||
                    pkg.publisher.lowercase().contains(filterText) ||
                    (pkg.description?.lowercase()?.contains(filterText) == true)
            }
        }

        items = filtered
    }

    /**
     * Get the current filter text.
     */
    fun getFilterText(): String = filterText

    /**
     * Get all items (before filtering).
     */
    fun getAllItems(): List<UnifiedPackage> = allItems
}
