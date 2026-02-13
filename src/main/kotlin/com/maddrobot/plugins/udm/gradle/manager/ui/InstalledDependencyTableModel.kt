package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.ui.table.TableView
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.ColumnInfo
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.InstalledDependency
import com.maddrobot.plugins.udm.ui.TableColumnInfo

/**
 * A table model for displaying installed dependencies in a Gradle project.
 *
 * This class extends `ListTableModel` to provide table functionalities for listing
 * instances of `InstalledDependency`. Each column of the table corresponds to a specific
 * property of the `InstalledDependency` data class, such as artifact ID, group ID, version,
 * configuration, and module name.
 *
 * Columns:
 * - Artifact ID: Displays the artifact identifier of the dependency.
 * - Group ID: Displays the group identifier of the dependency.
 * - Version: Displays the version of the dependency.
 * - Configuration: Displays the configuration through which the dependency is provided.
 * - Module Name: Displays the module name associated with the dependency.
 *
 * The model uses `TableColumnInfo` to define the properties mapped to each column.
 */
class InstalledDependencyTableModel : ListTableModel<InstalledDependency>(
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.artifactId")) { it.artifactId },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.groupId")) { it.groupId },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.version")) { it.version },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.configuration")) { it.configuration },
    TableColumnInfo<InstalledDependency>(message("gradle.manager.column.module")) { it.moduleName }
)
