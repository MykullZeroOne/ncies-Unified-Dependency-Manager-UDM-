package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.ui.table.TableView
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.ColumnInfo
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.InstalledDependency
import com.maddrobot.plugins.udm.ui.TableColumnInfo

class InstalledDependencyTableModel : ListTableModel<InstalledDependency>(
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.artifactId")) { it.artifactId },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.groupId")) { it.groupId },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.version")) { it.version },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.configuration")) { it.configuration },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.module")) { it.moduleName }
)
