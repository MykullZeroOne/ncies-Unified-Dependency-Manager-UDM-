package com.maddrobot.plugins.udm.ui.npm

import com.intellij.openapi.ide.CopyPasteManager
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.npm.NpmObject
import com.maddrobot.plugins.udm.npm.NpmPackageManager
import com.maddrobot.plugins.udm.ui.PaginatedTable
import com.maddrobot.plugins.udm.util.showInformationNotification
import java.awt.event.MouseEvent

/**
 * Npm Package 表格
 *
 * @author drawsta
 * @LastModified: 2025-07-18
 * @since 2025-01-27
 */
class NpmTable : PaginatedTable<NpmObject>(NpmPackageTableModel()) {

    var packageManagerName: NpmPackageManager = NpmPackageManager.NPM

    override fun mouseClickedInTable(e: MouseEvent?, selectedRow: Int) {
        val selectedNpmObject = tableModel.getItem(selectedRow)
        if (e?.clickCount == 2) {
            CopyPasteManager.copyTextToClipboard("${packageManagerName.displayName} add ${selectedNpmObject.`package`.packageName}")
            showInformationNotification(message("notification.copyToClipboard"))
        }
    }

    fun refreshTable(data: List<NpmObject>) {
        tableModel.updateTableData(data)
        refreshTable(1)
    }
}
