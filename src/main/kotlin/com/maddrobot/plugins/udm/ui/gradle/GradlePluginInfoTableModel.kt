package com.maddrobot.plugins.udm.ui.gradle

import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.GradlePluginInfo
import com.maddrobot.plugins.udm.ui.TableColumnInfo
import com.maddrobot.plugins.udm.ui.PaginatedListTableModel

/**
 * madd robot tech
 * @LastModified: 2025-07-14
 * @since 2025-07-14
 */
class GradlePluginInfoTableModel : PaginatedListTableModel<GradlePluginInfo>() {
    init {
        columnInfos = arrayOf(
            TableColumnInfo<GradlePluginInfo>(message("GradlePlugin.table.column.pluginName")) { it.pluginName },
            TableColumnInfo(message("GradlePlugin.table.column.latestVersion")) { it.latestVersion },
            TableColumnInfo(message("GradlePlugin.table.column.releaseDate")) { it.releaseDate },
            TableColumnInfo(message("GradlePlugin.table.column.description")) { it.description },
        )
    }
}
