package com.maddrobot.plugins.udm.ui

import com.maddrobot.plugins.udm.maven.DependencyFormat
import com.maddrobot.plugins.udm.maven.DependencyScope
import com.maddrobot.plugins.udm.maven.MavenRepositorySource
import com.maddrobot.plugins.udm.npm.NpmPackageManager
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * madd robot tech
 * @LastModified: 2025-07-18
 * @since 2025-01-23
 */
object PackageFinderListCellRenderer : DefaultListCellRenderer() {

    // DefaultListCellRenderer implements Serializable; implement readResolve to ensure singleton behavior after deserialization
    @Suppress
    private fun readResolve(): Any = PackageFinderListCellRenderer

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val component = super.getListCellRendererComponent(
            list, value, index, isSelected, cellHasFocus
        )
        when (value) {
            is MavenRepositorySource -> {
                text = value.displayName
                icon = value.icon
            }

            is DependencyFormat -> {
                text = value.displayName
            }

            is DependencyScope -> {
                text = value.displayName
            }

            is NpmPackageManager -> {
                text = value.displayName
            }
        }
        return component
    }
}
