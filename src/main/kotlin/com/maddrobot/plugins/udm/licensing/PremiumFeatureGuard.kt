package com.maddrobot.plugins.udm.licensing

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Helper for gating premium features with user-friendly prompts
 */
object PremiumFeatureGuard {

    private const val PURCHASE_URL = "https://github.com/maddrobot/udm#premium"

    /**
     * Check if feature is available, show upgrade dialog if not
     *
     * @return true if feature is available, false if blocked
     */
    fun checkOrPrompt(project: Project?, feature: Feature): Boolean {
        val checker = LicenseChecker.getInstance()

        if (checker.canUse(feature)) {
            return true
        }

        showUpgradePrompt(project, feature)
        return false
    }

    /**
     * Run an action only if the feature is available
     */
    inline fun withFeature(project: Project?, feature: Feature, action: () -> Unit) {
        if (checkOrPrompt(project, feature)) {
            action()
        }
    }

    /**
     * Show upgrade prompt for a specific feature
     */
    fun showUpgradePrompt(project: Project?, feature: Feature) {
        val result = Messages.showYesNoCancelDialog(
            project,
            """
                "${feature.displayName}" requires a Premium license.

                Premium includes:
                • Vulnerability scanning & security alerts
                • Bulk upgrade operations
                • Dependency tree visualization
                • Private repository support
                • Version consolidation across modules
                • And more!
            """.trimIndent(),
            "Premium Feature",
            "Get License",
            "Enter Key",
            "Cancel",
            Messages.getInformationIcon()
        )

        when (result) {
            Messages.YES -> BrowserUtil.browse(PURCHASE_URL)
            Messages.NO -> LicenseDialog(project).show()
        }
    }

    /**
     * Show generic upgrade prompt
     */
    fun showUpgradePrompt(project: Project?) {
        val result = Messages.showYesNoDialog(
            project,
            """
                Upgrade to UDM Premium for full access:

                • Vulnerability scanning & security alerts
                • Bulk upgrade operations
                • Dependency tree visualization
                • Private repository support
                • Version consolidation
                • Dependency exclusion management
                • Cache controls
            """.trimIndent(),
            "Upgrade to Premium",
            "Get License",
            "Cancel",
            Messages.getInformationIcon()
        )

        if (result == Messages.YES) {
            BrowserUtil.browse(PURCHASE_URL)
        }
    }
}
