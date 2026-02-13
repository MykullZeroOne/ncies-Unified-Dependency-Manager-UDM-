package com.maddrobot.plugins.udm.ui.maven

import com.intellij.util.ui.ColumnInfo
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.maven.CentralDependency
import com.maddrobot.plugins.udm.maven.Dependency
import com.maddrobot.plugins.udm.maven.LocalDependency
import com.maddrobot.plugins.udm.maven.NexusDependency
import com.maddrobot.plugins.udm.ui.PaginatedListTableModel
import com.maddrobot.plugins.udm.ui.TableColumnInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Data model for the Maven dependency table
 *
 * madd robot tech
 * @LastModified: 2025-07-13
 * @since 2025-01-20
 */
class MavenDependencyTableModel : PaginatedListTableModel<Dependency>() {

    private val localTableColumnInfos: Array<ColumnInfo<Dependency, Any?>> = arrayOf(
        TableColumnInfo(message("maven.table.column.artifactId")) { it.artifactId },
        TableColumnInfo(message("maven.table.column.groupId")) { it.groupId },
        TableColumnInfo(message("maven.table.column.version")) { it.version },
        TableColumnInfo(message("maven.table.column.name")) { (it as? LocalDependency)?.name },
        TableColumnInfo(message("maven.table.column.description")) { (it as? LocalDependency)?.description }
    )

    private val centralTableColumnInfos: Array<ColumnInfo<Dependency, Any?>> = arrayOf(
        TableColumnInfo(message("maven.table.column.artifactId")) { it.artifactId },
        TableColumnInfo(message("maven.table.column.groupId")) { it.groupId },
        TableColumnInfo(message("maven.table.column.version")) { it.version },
        TableColumnInfo(message("maven.table.column.packaging")) { (it as? CentralDependency)?.packaging },
        TableColumnInfo(message("maven.table.column.date")) { (it as? CentralDependency)?.timestamp?.toDateString() }
    )

    private val nexusTableColumnInfos: Array<ColumnInfo<Dependency, Any?>> = arrayOf(
        TableColumnInfo(message("maven.table.column.artifactId")) { it.artifactId },
        TableColumnInfo(message("maven.table.column.groupId")) { it.groupId },
        TableColumnInfo(message("maven.table.column.version")) { it.version },
        TableColumnInfo(message("maven.table.column.uploaderIp")) { (it as? NexusDependency)?.uploaderIp },
    )

    init {
        columnInfos = centralTableColumnInfos
    }

    fun switchToLocalDependencyColumnInfo() {
        columnInfos = localTableColumnInfos
        fireTableStructureChanged()
    }

    fun switchToCentralDependencyColumnInfo() {
        columnInfos = centralTableColumnInfos
        fireTableStructureChanged()
    }

    fun switchToNexusDependencyColumnInfo() {
        columnInfos = nexusTableColumnInfos
        fireTableStructureChanged()
    }
}

private fun Long.toDateString(zoneId: ZoneId = ZoneId.systemDefault()): String {
    return Instant.ofEpochMilli(this)
        .atZone(zoneId)
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}
