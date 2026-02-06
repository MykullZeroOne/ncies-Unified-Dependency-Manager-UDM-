package com.maddrobot.plugins.udm.licensing

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to open the license dialog.
 * Register in plugin.xml under Tools menu.
 */
class LicenseAction : AnAction("UDM License...") {

    override fun actionPerformed(e: AnActionEvent) {
        LicenseDialog(e.project).show()
    }

    override fun update(e: AnActionEvent) {
        val checker = LicenseChecker.getInstance()
        val tier = checker.getTier()

        e.presentation.text = when (tier) {
            LicenseChecker.Tier.FREE -> "UDM License..."
            LicenseChecker.Tier.PREMIUM -> "UDM License (Premium)"
        }
    }
}
