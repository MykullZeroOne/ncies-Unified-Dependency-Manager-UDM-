package com.maddrobot.plugins.udm.ui.npm

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SideBorder
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.NamedColorUtil
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.npm.NpmPackageManager
import com.maddrobot.plugins.udm.npm.NpmRegistryService
import com.maddrobot.plugins.udm.ui.PackageFinderListCellRenderer
import com.maddrobot.plugins.udm.ui.borderPanel
import com.maddrobot.plugins.udm.ui.boxPanel
import com.maddrobot.plugins.udm.ui.scrollPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.JPanel

/**
 * madd robot tech
 * @LastModified: 2025-07-18
 * @since 2025-01-27
 */
class NpmToolWindow(parentDisposable: Disposable) {
    // view
    val contentPanel: JPanel
    private val npmTable = NpmTable()
    private val searchTextField: SearchTextField

    private val propertyGraph: PropertyGraph = PropertyGraph()
    private var packageManagerProperty = propertyGraph.lazyProperty { NpmPackageManager.NPM }

    init {
        searchTextField = createSearchTextField()

        contentPanel = borderPanel {
            val scrollPanel = scrollPanel {
                viewport.view = borderPanel {
                    val topToolbar = boxPanel {
                        border = SideBorder(NamedColorUtil.getBoundsColor(), SideBorder.BOTTOM)
                        // Set the height of topToolbar to 50
                        preferredSize = Dimension(preferredSize.width, 50)
                        minimumSize = Dimension(minimumSize.width, 50)
                        maximumSize = Dimension(maximumSize.width, 50)
                        add(searchTextField)
                        add(Box.createRigidArea(Dimension(10, 0)))
                        add(packageManagerPanel())
                    }
                    add(topToolbar, BorderLayout.NORTH)
                    val component = npmTable.createTablePanel()
                    add(component, BorderLayout.CENTER)
                }
            }
            add(scrollPanel, BorderLayout.CENTER)
        }

        // Resource cleanup
        Disposer.register(parentDisposable) {
            npmTable.dispose()
        }
    }

    private fun createSearchTextField(): SearchTextField {
        return SearchTextField().apply {
            // placeholder
            textEditor.emptyText.text = message("npm.table.searchField.emptyText")
            textEditor.putClientProperty(
                "StatusVisibleFunction",
                java.util.function.Function<javax.swing.JTextField, Boolean> { true })

            // Width
            preferredSize = Dimension(550, preferredSize.height)
            minimumSize = Dimension(550, minimumSize.height)
            maximumSize = Dimension(550, maximumSize.height)

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
        npmTable.showLoading(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val npmObjects = NpmRegistryService.search(text)
            ApplicationManager.getApplication().invokeLater {
                // Refresh the table and update all rows with the search results
                npmTable.refreshTable(npmObjects)
                npmTable.showLoading(false)
            }
        }
    }

    private fun packageManagerPanel(): DialogPanel {
        return panel {
            row {
                comboBox(NpmPackageManager.entries, PackageFinderListCellRenderer)
                    .label(message("npm.PackageManager.label"))
                    .bindItem(packageManagerProperty)
                    .onChanged {
                        npmTable.packageManagerName = it.item
                    }
            }
        }
    }
}
