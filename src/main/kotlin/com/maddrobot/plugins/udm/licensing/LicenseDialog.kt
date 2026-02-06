package com.maddrobot.plugins.udm.licensing

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
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
        title = "UDM License"
        init()
        updateStatus()
    }

    override fun createCenterPanel(): JComponent {
        val info = checker.getLicenseInfo()

        return panel {
            // Current status
            row {
                label("Status:").bold()
                cell(statusLabel)
            }

            if (info.tier == LicenseChecker.Tier.PREMIUM) {
                row {
                    label("Licensed via:")
                    label(info.description)
                }

                // Show email/expiry only for direct licenses
                if (info.source == LicenseChecker.LicenseSource.DIRECT) {
                    row {
                        label("Email:")
                        label(info.email ?: "")
                    }
                    row {
                        label("Expires:")
                        label(info.expiresAt?.toString() ?: "Never")
                    }
                }

                separator()

                row {
                    text("You have Premium access. All features are unlocked.")
                }

                // Show note about JetBrains license
                if (info.source == LicenseChecker.LicenseSource.JETBRAINS) {
                    row {
                        comment("License managed through JetBrains Marketplace")
                    }
                }
            } else {
                separator()

                group("Activate Direct License") {
                    row("Email:") {
                        cell(emailField)
                            .columns(COLUMNS_LARGE)
                            .comment("The email your license was issued to")
                    }
                    row("License Key:") {
                        cell(licenseKeyField)
                            .columns(COLUMNS_LARGE)
                            .comment("Format: UDM-XXXXXXXX-XXXXXXXX")
                    }
                }

                separator()

                row {
                    text(
                        """
                        <b>Free tier includes:</b> Package search, view dependencies,
                        add/remove/update individual packages.
                        <br><br>
                        <b>Premium adds:</b> Vulnerability scanning, bulk upgrades,
                        dependency tree, private repos, and more.
                        """.trimIndent()
                    )
                }

                separator()

                row {
                    label("Get Premium:").bold()
                }
                row {
                    browserLink("Buy on JetBrains Marketplace", "https://plugins.jetbrains.com/plugin/YOUR_PLUGIN_ID")
                    label(" or ")
                    browserLink("Buy Direct", "https://github.com/maddrobot/udm#premium")
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
                    object : AbstractAction("Deactivate") {
                        override fun actionPerformed(e: ActionEvent?) {
                            val confirm = Messages.showYesNoDialog(
                                project,
                                "Are you sure you want to deactivate your direct license?",
                                "Deactivate License",
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
                    object : AbstractAction("Activate") {
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
            Messages.showErrorDialog(project, "Please enter your email address.", "Activation Error")
            return
        }

        if (key.isBlank()) {
            Messages.showErrorDialog(project, "Please enter your license key.", "Activation Error")
            return
        }

        val error = checker.activateLicense(email, key)
        if (error != null) {
            Messages.showErrorDialog(project, error, "Activation Error")
        } else {
            Messages.showInfoMessage(project, "License activated successfully! All Premium features are now unlocked.", "Activation Complete")
            close(OK_EXIT_CODE)
        }
    }

    private fun updateStatus() {
        val info = checker.getLicenseInfo()
        statusLabel.text = when (info.tier) {
            LicenseChecker.Tier.FREE -> "<html><b style='color:#888888'>Free</b></html>"
            LicenseChecker.Tier.PREMIUM -> "<html><b style='color:#4CAF50'>Premium</b></html>"
        }
    }
}
