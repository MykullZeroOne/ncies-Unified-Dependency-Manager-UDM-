package com.maddrobot.plugins.udm.ui.gradle

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SideBorder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.maddrobot.plugins.udm.PackageFinderBundle
import com.maddrobot.plugins.udm.gradle.GradlePluginPortalService
import com.maddrobot.plugins.udm.ui.borderPanel
import com.maddrobot.plugins.udm.ui.boxPanel
import com.maddrobot.plugins.udm.ui.scrollPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JPanel

/**
 * madd robot tech
 * @LastModified: 2025-07-14
 * @since 2025-07-14
 */
class GradlePluginToolWindow(parentDisposable: Disposable) {
    val contentPanel: JPanel
    private val gradlePluginTable = GradlePluginTable()
    private val searchTextField: SearchTextField

    init {
        searchTextField = createSearchTextField()

        contentPanel = borderPanel {
            val scrollPanel = scrollPanel {
                viewport.view = borderPanel {
                    val topToolbar = boxPanel {
                        border = JBUI.Borders.compound(
                            SideBorder(NamedColorUtil.getBoundsColor(), SideBorder.BOTTOM),
                            JBUI.Borders.empty(6, 8)
                        )
                        add(searchTextField)
                    }
                    add(topToolbar, BorderLayout.NORTH)
                    val component = gradlePluginTable.createTablePanel()
                    add(component, BorderLayout.CENTER)
                }
            }
            add(scrollPanel, BorderLayout.CENTER)
        }

        // Resource cleanup
        Disposer.register(parentDisposable) {
            gradlePluginTable.dispose()
        }
    }

    private fun createSearchTextField(): SearchTextField {
        return SearchTextField().apply {
            // placeholder
            textEditor.emptyText.text =
                PackageFinderBundle.message("GradlePlugin.table.searchField.emptyText")
            textEditor.putClientProperty(
                "StatusVisibleFunction",
                java.util.function.Function<javax.swing.JTextField, Boolean> { true })

            // Width
            textEditor.columns = 24

            addKeyboardListener(object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) {
                    // Listen for Enter key to trigger search from the search field
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        handleSearch(text.trim())
                    }
                }
            })
        }
    }

    private fun handleSearch(text: String) {
        gradlePluginTable.showLoading(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            val pluginInfoTriple = GradlePluginPortalService.searchBy(text)
            ApplicationManager.getApplication().invokeLater {
                // Refresh the table and update all rows with the search results
                gradlePluginTable.refreshTable(pluginInfoTriple)
                gradlePluginTable.showLoading(false)
            }
        }
    }
}
