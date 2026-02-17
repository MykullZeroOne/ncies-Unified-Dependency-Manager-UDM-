package com.maddrobot.plugins.udm.ui

import com.intellij.ui.JBColor
import java.awt.Color

object UdmColors {
    val accent = JBColor.namedColor("Component.focusedBorderColor", JBColor(0x4A90D9, 0x589DF6))
    val link = JBColor.namedColor("Link.activeForeground", accent)
    val secondaryText = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)

    val success = JBColor.namedColor("Validation.infoForeground", JBColor(0x4CAF50, 0x81C784))
    val warning = JBColor.namedColor("Validation.warningForeground", JBColor(0xFF9800, 0xFFB74D))
    val error = JBColor.namedColor("Validation.errorForeground", JBColor(0xF44336, 0xE57373))
    val info = JBColor.namedColor("Validation.infoForeground", JBColor(0x2196F3, 0x64B5F6))
    val violet = JBColor.namedColor("Link.visitedForeground", JBColor(0x9C27B0, 0xBA68C8))
    val severityCritical = JBColor.namedColor("Udm.Severity.Critical", JBColor(0xD32F2F, 0xEF5350))
    val severityHigh = JBColor.namedColor("Udm.Severity.High", JBColor(0xE64A19, 0xFF7043))
    val severityMedium = JBColor.namedColor("Udm.Severity.Medium", JBColor(0xF57C00, 0xFFB74D))
    val severityLow = JBColor.namedColor("Udm.Severity.Low", JBColor(0xFBC02D, 0xFFF176))

    object Badges {
        val updateBackground = JBColor.namedColor("Udm.Badge.Update.background", JBColor(0x4CAF50, 0x81C784))
        val updateForeground = JBColor.namedColor("Udm.Badge.Update.foreground", JBColor(Color.WHITE, Color.WHITE))

        val outdatedBackground = JBColor.namedColor("Udm.Badge.Outdated.background", JBColor(0xFF9800, 0xFFB74D))
        val outdatedForeground = JBColor.namedColor("Udm.Badge.Outdated.foreground", JBColor(Color.WHITE, Color.WHITE))

        val vulnerableBackground = JBColor.namedColor("Udm.Badge.Vulnerable.background", JBColor(0xF44336, 0xE57373))
        val vulnerableForeground = JBColor.namedColor("Udm.Badge.Vulnerable.foreground", JBColor(Color.WHITE, Color.WHITE))

        val transitiveBackground = JBColor.namedColor("Udm.Badge.Transitive.background", JBColor(0x9E9E9E, 0x757575))
        val transitiveForeground = JBColor.namedColor("Udm.Badge.Transitive.foreground", JBColor(Color.WHITE, Color.WHITE))

        val prereleaseBackground = JBColor.namedColor("Udm.Badge.Prerelease.background", JBColor(0x9C27B0, 0xBA68C8))
        val prereleaseForeground = JBColor.namedColor("Udm.Badge.Prerelease.foreground", JBColor(Color.WHITE, Color.WHITE))

        val deprecatedBackground = JBColor.namedColor("Udm.Badge.Deprecated.background", JBColor(0x616161, 0x424242))
        val deprecatedForeground = JBColor.namedColor("Udm.Badge.Deprecated.foreground", JBColor(Color.WHITE, Color.WHITE))

        val installedBackground = JBColor.namedColor("Udm.Badge.Installed.background", JBColor(0x2196F3, 0x64B5F6))
        val installedForeground = JBColor.namedColor("Udm.Badge.Installed.foreground", JBColor(Color.WHITE, Color.WHITE))

        val versionBackground = JBColor.namedColor("Udm.Badge.Version.background", JBColor(0xF5F5F5, 0x424242))
        val versionForeground = JBColor.namedColor("Udm.Badge.Version.foreground", JBColor(0x424242, 0xBDBDBD))
        val versionBorder = JBColor.namedColor("Udm.Badge.Version.border", JBColor(0xBDBDBD, 0x616161))

        val latestBackground = JBColor.namedColor("Udm.Badge.Latest.background", JBColor(0xE8F5E9, 0x1B5E20))
        val latestForeground = JBColor.namedColor("Udm.Badge.Latest.foreground", JBColor(0x2E7D32, 0x81C784))
        val latestBorder = JBColor.namedColor("Udm.Badge.Latest.border", JBColor(0x4CAF50, 0x4CAF50))

        val pluginBackground = JBColor.namedColor("Udm.Badge.Plugin.background", JBColor(0xE3F2FD, 0x0D47A1))
        val pluginForeground = JBColor.namedColor("Udm.Badge.Plugin.foreground", JBColor(0x1976D2, 0x64B5F6))
        val pluginBorder = JBColor.namedColor("Udm.Badge.Plugin.border", JBColor(0x2196F3, 0x2196F3))
    }

    fun htmlColor(color: Color): String =
        String.format("%02x%02x%02x", color.red, color.green, color.blue)
}
