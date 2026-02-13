package com.maddrobot.plugins.udm.ui

import com.intellij.util.ui.ColumnInfo

/**
 * madd robot tech
 * @LastModified: 2025-01-21
 * @since 2025-01-21
 */
class TableColumnInfo<T>(
    name: String,
    private val valueProvider: (T) -> Any?
) : ColumnInfo<T, Any?>(name) {
    override fun valueOf(item: T): Any? = valueProvider(item)
}
