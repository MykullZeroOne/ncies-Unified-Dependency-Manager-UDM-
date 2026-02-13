package com.maddrobot.plugins.udm.ui

import com.intellij.util.ui.ListTableModel
import kotlin.math.ceil

/**
 * Paginated wrapper around [ListTableModel]
 *
 * Tip: Native Swing [javax.swing.JTable] has no pagination; a table has one page and `items` contains all data.
 * With pagination enabled, `items` becomes the current page's data, while [Pagination.data] holds all rows.
 *
 * madd robot tech
 * @LastModified: 2025-01-21
 * @since 2025-01-21
 */
abstract class PaginatedListTableModel<T> : ListTableModel<T>() {

    init {
        items = emptyList()
    }

    private val pagination: Pagination<T> = Pagination()

    /**
     * Update all table data
     */
    fun updateTableData(data: List<T>) {
        pagination.data = data
    }

    /**
     * Update current page data
     */
    fun updateCurrentPageData() {
        items = pagination.getCurrentPageData()
        fireTableDataChanged()
    }

    /**
     * Get current page data
     */
    fun getCurrentPageData(): List<T> = items

    /**
     * Get current page number
     */
    fun getCurrentPage(): Int = pagination.currentPage

    /**
     * Set current page number
     */
    fun setCurrentPage(page: Int) {
        pagination.currentPage = page
    }

    /**
     * Get total number of pages
     */
    fun getTotalPages(): Int = pagination.totalPages
}

/**
 * Pagination model
 */
private class Pagination<T>(
    // Items per page
    var pageSize: Int = 10,
    // All table data: the initial full set of local Maven dependencies, or the full search results for a keyword
    var data: List<T> = emptyList()
) {
    var currentPage: Int = 1

    val totalPages: Int
        get() = ceil(data.size.toDouble() / pageSize).toInt()

    fun getCurrentPageData(): List<T> {
        val fromIndex = (currentPage - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, data.size)
        return data.subList(fromIndex, toIndex)
    }
}
