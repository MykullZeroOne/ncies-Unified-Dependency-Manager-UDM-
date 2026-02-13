package com.maddrobot.plugins.udm.ui.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel
import com.maddrobot.plugins.udm.PackageFinderBundle
import com.maddrobot.plugins.udm.gradle.GradlePluginInfo
import com.maddrobot.plugins.udm.gradle.GradlePluginPortalService
import com.maddrobot.plugins.udm.ui.PaginatedTable
import java.awt.event.MouseEvent

/**
 * madd robot tech
 * @LastModified: 2025-07-14
 * @since 2025-07-14
 */
class GradlePluginTable : PaginatedTable<GradlePluginInfo>(GradlePluginInfoTableModel()) {

    private val propertyGraph: PropertyGraph = PropertyGraph()
    private val prevHrefProperty: GraphProperty<String> = propertyGraph.property("")
    private val nextHrefProperty: GraphProperty<String> = propertyGraph.property("")
    private var prevHref: String by prevHrefProperty
    private var nextHref: String by nextHrefProperty
    private val hasPrevHrefProperty: GraphProperty<Boolean> = propertyGraph.property(false)
    private val hasNextHrefProperty: GraphProperty<Boolean> = propertyGraph.property(false)

    override fun mouseClickedInTable(e: MouseEvent?, selectedRow: Int) {
    }

    override fun createPaginationPanel(): DialogPanel {
        return panel {
            row {
                // Previous page
                button(PackageFinderBundle.message("table.pagination.previous")) {
                    if (prevHref.isNotEmpty()) {
                        loadPageData(prevHref)
                    }
                }.visibleIf(hasPrevHrefProperty)
                // Next page
                button(PackageFinderBundle.message("table.pagination.next")) {
                    if (nextHref.isNotEmpty()) {
                        loadPageData(nextHref)
                    }
                }.visibleIf(hasNextHrefProperty)
            }
        }
    }

    fun refreshTable(data: Triple<List<GradlePluginInfo>, String, String>) {
        tableModel.updateTableData(data.first)
        refreshTable(1)

        // Update previous and next page links
        prevHref = data.second
        nextHref = data.third

        hasPrevHrefProperty.set(prevHref.isNotEmpty())
        hasNextHrefProperty.set(nextHref.isNotEmpty())
    }

    private fun loadPageData(pageLink: String) {
        showLoading(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val pluginInfoTriple = GradlePluginPortalService.searchPage(pageLink)
            ApplicationManager.getApplication().invokeLater {
                // Refresh the table and update all rows to the search results
                refreshTable(pluginInfoTriple)
                showLoading(false)
            }
        }
    }
}
