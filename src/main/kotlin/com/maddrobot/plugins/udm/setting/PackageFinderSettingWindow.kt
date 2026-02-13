package com.maddrobot.plugins.udm.setting

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.licensing.LicenseChecker
import java.awt.Font
import javax.swing.JButton

/**
 * Settings window for configuring the Unified Dependency Manager plugin.
 *
 * Sections:
 * - General: preview diffs before applying changes
 * - License: activate/view license status
 * - Vulnerability Scanning: enable/disable scanning, scan on load, GitHub token
 */
class PackageFinderSettingWindow : BoundConfigurable(
    message("settings.name")
) {

    private val setting = PackageFinderSetting.instance
    private val checker = LicenseChecker.getInstance()

    private lateinit var statusLabel: JBLabel
    private lateinit var sourceLabel: JBLabel
    private lateinit var emailField: JBTextField
    private lateinit var licenseKeyField: JBTextField
    private lateinit var activateButton: JButton
    private lateinit var deactivateButton: JButton

    override fun createPanel(): DialogPanel {
        return panel {
            group(message("settings.general.group.title")) {
                row {
                    checkBox(message("settings.general.showPreview"))
                        .bindSelected(
                            getter = { setting.showPreviewBeforeChanges },
                            setter = { setting.showPreviewBeforeChanges = it }
                        )
                        .comment(message("settings.general.showPreview.tooltip"))
                }
            }

            group(message("settings.license.group.title")) {
                row(message("settings.license.status")) {
                    statusLabel = JBLabel().apply {
                        font = font.deriveFont(Font.BOLD)
                    }
                    cell(statusLabel)
                }
                row(message("settings.license.source")) {
                    sourceLabel = JBLabel()
                    cell(sourceLabel)
                }

                separator()

                val info = checker.getLicenseInfo()
                if (info.source == LicenseChecker.LicenseSource.DEVELOPMENT) {
                    row {
                        comment(message("settings.license.dev.mode.note"))
                    }
                } else if (info.source == LicenseChecker.LicenseSource.JETBRAINS) {
                    row {
                        comment(message("settings.license.jetbrains.note"))
                    }
                } else {
                    row(message("settings.license.email")) {
                        emailField = JBTextField(info.email ?: "")
                        cell(emailField).columns(COLUMNS_LARGE)
                    }
                    row(message("settings.license.key")) {
                        licenseKeyField = JBTextField(if (info.source == LicenseChecker.LicenseSource.DIRECT) checker.getState().licenseKey else "")
                        cell(licenseKeyField)
                            .columns(COLUMNS_LARGE)
                            .comment(message("settings.license.key.comment"))
                    }
                    row {
                        activateButton = JButton(message("settings.license.activate"))
                        activateButton.addActionListener { activateLicense() }
                        cell(activateButton)

                        deactivateButton = JButton(message("settings.license.deactivate"))
                        deactivateButton.isEnabled = info.source == LicenseChecker.LicenseSource.DIRECT
                        deactivateButton.addActionListener { deactivateLicense() }
                        cell(deactivateButton)
                    }
                }

                updateLicenseStatus()
            }

            group(message("settings.vulnerability.group.title")) {
                row {
                    checkBox(message("settings.vulnerability.enable"))
                        .bindSelected(
                            getter = { setting.enableVulnerabilityScanning },
                            setter = { setting.enableVulnerabilityScanning = it }
                        )
                        .comment(message("settings.vulnerability.enable.tooltip"))
                }
                indent {
                    row {
                        checkBox(message("settings.vulnerability.scan.on.load"))
                            .bindSelected(
                                getter = { setting.vulnerabilityScanOnLoad },
                                setter = { setting.vulnerabilityScanOnLoad = it }
                            )
                            .comment(message("settings.vulnerability.scan.on.load.tooltip"))
                    }
                    row(message("settings.vulnerability.github.token")) {
                        passwordField()
                            .bindText(
                                getter = { setting.githubToken ?: "" },
                                setter = { setting.githubToken = it.ifBlank { null } }
                            )
                            .comment(message("settings.vulnerability.github.token.info"))
                    }
                }
            }
        }
    }

    private fun updateLicenseStatus() {
        val info = checker.getLicenseInfo()

        if (::statusLabel.isInitialized) {
            statusLabel.text = when (info.tier) {
                LicenseChecker.Tier.FREE -> message("settings.license.status.free")
                LicenseChecker.Tier.PREMIUM -> message("settings.license.status.premium")
            }
            statusLabel.foreground = when (info.tier) {
                LicenseChecker.Tier.FREE -> JBColor.GRAY
                LicenseChecker.Tier.PREMIUM -> JBColor(0x4CAF50, 0x81C784)
            }
        }

        if (::sourceLabel.isInitialized) {
            sourceLabel.text = info.description
        }

        if (::deactivateButton.isInitialized) {
            deactivateButton.isEnabled = info.source == LicenseChecker.LicenseSource.DIRECT
        }
    }

    private fun activateLicense() {
        val email = emailField.text.trim()
        val key = licenseKeyField.text.trim()

        if (email.isBlank()) {
            Messages.showErrorDialog(message("settings.license.activate.error.email"), message("settings.license.activate.error.title"))
            return
        }
        if (key.isBlank()) {
            Messages.showErrorDialog(message("settings.license.activate.error.key"), message("settings.license.activate.error.title"))
            return
        }

        val error = checker.activateLicense(email, key)
        if (error != null) {
            Messages.showErrorDialog(error, message("settings.license.activate.error.title"))
        } else {
            Messages.showInfoMessage(message("settings.license.activate.success"), message("settings.license.activate.success.title"))
            updateLicenseStatus()
        }
    }

    private fun deactivateLicense() {
        val confirm = Messages.showYesNoDialog(
            message("settings.license.deactivate.confirm"),
            message("settings.license.deactivate.title"),
            Messages.getQuestionIcon()
        )
        if (confirm == Messages.YES) {
            checker.deactivateLicense()
            emailField.text = ""
            licenseKeyField.text = ""
            updateLicenseStatus()
        }
    }
}
