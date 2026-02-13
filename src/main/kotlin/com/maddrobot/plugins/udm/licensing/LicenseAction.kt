package com.maddrobot.plugins.udm.licensing

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.maddrobot.plugins.udm.PackageFinderBundle.message

/**
 * Represents an action for managing the license through the UDM License dialog.
 *
 * This action allows users to open the license management dialog, enabling them to:
 * - View their current license status.
 * - Activate a direct license using an email and license key.
 * - Deactivate an existing direct license.
 * - See the benefits of upgrading to a Premium license.
 *
 * The UI adapts based on the current license tier:
 * - For "Free" tier: Presents an option to activate the license and displays available free and premium features.
 * - For "Premium" tier: Displays active license details and additional information about the premium benefits.
 *
 * Methods:
 * - `actionPerformed`: Opens the `LicenseDialog`, allowing the user to manage their license.
 * - `update`: Dynamically updates the presentation text of the action based on the current license tier.
 */
class LicenseAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        LicenseDialog(e.project).show()
    }

    override fun update(e: AnActionEvent) {
        val checker = LicenseChecker.getInstance()
        val tier = checker.getTier()

        e.presentation.text = when (tier) {
            LicenseChecker.Tier.FREE -> message("action.license.text")
            LicenseChecker.Tier.PREMIUM -> message("action.license.text.premium")
        }
    }
}
