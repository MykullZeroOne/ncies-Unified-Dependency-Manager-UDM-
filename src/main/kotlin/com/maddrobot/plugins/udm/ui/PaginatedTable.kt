package com.maddrobot.plugins.udm.ui

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.toStringProperty
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.maddrobot.plugins.udm.PackageFinderBundle
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ListSelectionModel.SINGLE_SELECTION

/**
 * Generic paginated table view
 *
 * madd robot tech
 * @LastModified: 2025-07-13
 * @since 2025-01-27
 */
abstract class PaginatedTable<T>(val tableModel: PaginatedListTableModel<T>) {
    private val myDisposable = Disposer.newDisposable()
    private val loadingPanel = JBLoadingPanel(BorderLayout(), myDisposable)

    private val myPaginationPanelVisible = AtomicBooleanProperty(false)

    private val propertyGraph: PropertyGraph = PropertyGraph()
    private var currentPageProperty = propertyGraph.lazyProperty { tableModel.getCurrentPage() }
    private var totalPageProperty = propertyGraph.lazyProperty { tableModel.getTotalPages() }

    // Changing currentPage propagates to UI components bound to currentPageProperty
    private var currentPage: Int by currentPageProperty
    private var totalPage: Int by totalPageProperty

    fun dispose() {
        Disposer.dispose(myDisposable)
    }

    fun createTablePanel(): DialogPanel {
        return panel {
            val tableView: TableView<T> = createTableView()
            val toolbarDecorator = ToolbarDecorator.createDecorator(tableView)
                .disableAddAction()
                .disableRemoveAction()
                .disableUpDownActions()
            val tablePanel = toolbarDecorator.createPanel()

            // Wrap tablePanel within loadingPanel
            loadingPanel.add(tablePanel, BorderLayout.CENTER)

            row {
                cell(loadingPanel).align(Align.FILL)
            }.resizableRow()

            // Pagination bar
            val paginationPanel = createPaginationPanel()
            row {
                cell(paginationPanel).align(Align.CENTER)
            }.visibleIf(myPaginationPanelVisible) // Hidden by default; visible only when the table has data
        }
    }

    private fun createTableView(): TableView<T> {
        return TableView(tableModel).apply {
            setShowColumns(true)
            setSelectionMode(SINGLE_SELECTION)

            columnModel.getColumn(0).preferredWidth = 150
            columnModel.getColumn(0).maxWidth = 250

            // Mouse click listener for the table
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    mouseClickedInTable(e, selectedRow)
                }
            })
        }
    }

    abstract fun mouseClickedInTable(e: MouseEvent?, selectedRow: Int)

    open fun createPaginationPanel(): DialogPanel {
        return panel {
            row {
                // Previous page
                button(PackageFinderBundle.message("table.pagination.previous")) {
                    if (currentPage > 1) {
                        refreshTable(--currentPage)
                    }
                }
                // Current page
                label("1").bindText(currentPageProperty.toStringProperty())
                label("/")
                // Total pages
                label("1").bindText(totalPageProperty.toStringProperty())
                // Next page
                button(PackageFinderBundle.message("table.pagination.next")) {
                    if (currentPage < tableModel.getTotalPages()) {
                        refreshTable(++currentPage)
                    }
                }
            }
        }
    }

    fun refreshTable(page: Int) {
        // Update the table model's current page
        tableModel.setCurrentPage(page)
        // Update the table model's current page data
        tableModel.updateCurrentPageData()
        // Update the pagination bar
        notifyPaginationChanged(page)
    }

    fun showLoading(withLoading: Boolean) {
        if (withLoading) {
            // Show loading animation
            loadingPanel.startLoading()
        } else {
            // Hide loading animation
            loadingPanel.stopLoading()
        }
    }

    private fun notifyPaginationChanged(page: Int) {
        if (tableModel.getCurrentPageData().isNotEmpty()) {
            // Show the pagination bar only when the current page has data
            myPaginationPanelVisible.set(true)

            // Notify currentPageProperty and totalPageProperty that pagination info (current and total pages) has changed
            currentPage = page
            totalPage = tableModel.getTotalPages()
        } else {
            myPaginationPanelVisible.set(false)
        }
    }
}
