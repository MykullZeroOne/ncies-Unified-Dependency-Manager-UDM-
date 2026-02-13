package com.maddrobot.plugins.udm.licensing

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/**
 * Dialog for activating/viewing license status.
 * Supports both JetBrains Marketplace licenses and direct licenses.
 */
class LicenseDialog(private val project: Project?) : DialogWrapper(project) {

    private val checker = LicenseChecker.getInstance()
    private val emailField = JBTextField()
    private val licenseKeyField = JBTextField()
    private var statusLabel = JBLabel()

    init {
        title = message("license.dialog.title")
        init()
        updateStatus()
    }

    override fun createCenterPanel(): JComponent {
        val info = checker.getLicenseInfo()

        return panel {
            // Current status
            row {
                label(message("settings.license.status")).bold()
                cell(statusLabel)
            }

            if (info.tier == LicenseChecker.Tier.PREMIUM) {
                row {
                    label(message("settings.license.source"))
                    label(info.description)
                }

                // Show email/expiry only for direct licenses
                if (info.source == LicenseChecker.LicenseSource.DIRECT) {
                    row {
                        label(message("settings.license.email"))
                        label(info.email ?: "")
                    }
                    row {
                        label(message("settings.license.expires"))
                        label(info.expiresAt?.toString() ?: message("settings.license.never"))
                    }
                }

                separator()

                row {
                    text(message("license.dialog.premium.message"))
                }

                // Show note about JetBrains license
                if (info.source == LicenseChecker.LicenseSource.JETBRAINS) {
                    row {
                        comment(message("settings.license.jetbrains.note"))
                    }
                }
            } else {
                separator()

                group(message("license.dialog.activate.group")) {
                    row(message("settings.license.email")) {
                        cell(emailField)
                            .columns(COLUMNS_LARGE)
                            .comment(message("license.dialog.email.comment"))
                    }
                    row(message("settings.license.key")) {
                        cell(licenseKeyField)
                            .columns(COLUMNS_LARGE)
                            .comment(message("settings.license.key.comment"))
                    }
                }

                separator()

                row {
                    text(message("license.dialog.free.features"))
                }

                separator()

                row {
                    label(message("license.dialog.get.premium")).bold()
                }
                row {
                    browserLink(message("license.dialog.buy.marketplace"), "https://plugins.jetbrains.com/plugin/YOUR_PLUGIN_ID")
                    label(message("license.dialog.or"))
                    browserLink(message("license.dialog.buy.direct"), "https://github.com/maddrobot/udm#premium")
                }
            }
        }.withPreferredWidth(480).withBorder(JBUI.Borders.empty(10))
    }

    override fun createActions(): Array<Action> {
        val info = checker.getLicenseInfo()

        return when {
            // JetBrains license - can't deactivate through our UI
            info.source == LicenseChecker.LicenseSource.JETBRAINS -> {
                arrayOf(okAction)
            }
            // Direct license - can deactivate
            info.source == LicenseChecker.LicenseSource.DIRECT -> {
                arrayOf(
                    object : AbstractAction(message("license.dialog.button.deactivate")) {
                        override fun actionPerformed(e: ActionEvent?) {
                            val confirm = Messages.showYesNoDialog(
                                project,
                                message("settings.license.deactivate.confirm"),
                                message("settings.license.deactivate.title"),
                                Messages.getQuestionIcon()
                            )
                            if (confirm == Messages.YES) {
                                checker.deactivateLicense()
                                close(OK_EXIT_CODE)
                            }
                        }
                    },
                    okAction
                )
            }
            // Development mode - just OK
            info.source == LicenseChecker.LicenseSource.DEVELOPMENT -> {
                arrayOf(okAction)
            }
            // Free - show activate
            else -> {
                arrayOf(
                    object : AbstractAction(message("license.dialog.button.activate")) {
                        override fun actionPerformed(e: ActionEvent?) {
                            activateLicense()
                        }
                    },
                    cancelAction
                )
            }
        }
    }

    private fun activateLicense() {
        val email = emailField.text.trim()
        val key = licenseKeyField.text.trim()

        if (email.isBlank()) {
            Messages.showErrorDialog(project, message("settings.license.activate.error.email"), message("settings.license.activate.error.title"))
            return
        }

        if (key.isBlank()) {
            Messages.showErrorDialog(project, message("settings.license.activate.error.key"), message("settings.license.activate.error.title"))
            return
        }

        val error = checker.activateLicense(email, key)
        if (error != null) {
            Messages.showErrorDialog(project, error, message("settings.license.activate.error.title"))
        } else {
            Messages.showInfoMessage(project, message("settings.license.activate.success"), message("settings.license.activate.success.title"))
            close(OK_EXIT_CODE)
        }
    }

    private fun updateStatus() {
        val info = checker.getLicenseInfo()
        statusLabel.text = when (info.tier) {
            LicenseChecker.Tier.FREE -> "<html><b style='color:#888888'>${message("settings.license.status.free")}</b></html>"
            LicenseChecker.Tier.PREMIUM -> "<html><b style='color:#4CAF50'>${message("settings.license.status.premium")}</b></html>"
        }
    }
}
