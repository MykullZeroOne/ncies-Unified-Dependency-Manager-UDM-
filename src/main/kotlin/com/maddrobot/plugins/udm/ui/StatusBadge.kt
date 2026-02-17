package com.maddrobot.plugins.udm.ui

import com.intellij.ui.JBColor
import com.maddrobot.plugins.udm.ui.UdmColors
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Badge/pill component for displaying package status indicators.
 * Provides visual indicators for update, outdated, vulnerable, transitive,
 * prerelease, and deprecated states.
 */
class StatusBadge(
    private val badgeType: BadgeType,
    text: String? = null
) : JLabel(text ?: badgeType.defaultText) {

    /**
     * Badge types with associated color schemes.
     */
    enum class BadgeType(
        val defaultText: String,
        val backgroundColor: JBColor,
        val foregroundColor: JBColor,
        val borderColor: JBColor? = null
    ) {
        /** Green badge for available updates */
        UPDATE(
            "Update",
            UdmColors.Badges.updateBackground,
            UdmColors.Badges.updateForeground,
            null
        ),

        /** Orange badge for outdated packages */
        OUTDATED(
            "Outdated",
            UdmColors.Badges.outdatedBackground,
            UdmColors.Badges.outdatedForeground,
            null
        ),

        /** Red badge for security vulnerabilities */
        VULNERABLE(
            "Vulnerable",
            UdmColors.Badges.vulnerableBackground,
            UdmColors.Badges.vulnerableForeground,
            null
        ),

        /** Gray badge for transitive/implicit dependencies */
        TRANSITIVE(
            "Transitive",
            UdmColors.Badges.transitiveBackground,
            UdmColors.Badges.transitiveForeground,
            null
        ),

        /** Purple badge for prerelease versions (alpha/beta/RC) */
        PRERELEASE(
            "Prerelease",
            UdmColors.Badges.prereleaseBackground,
            UdmColors.Badges.prereleaseForeground,
            null
        ),

        /** Dark gray badge for deprecated packages */
        DEPRECATED(
            "Deprecated",
            UdmColors.Badges.deprecatedBackground,
            UdmColors.Badges.deprecatedForeground,
            null
        ),

        /** Blue badge for installed packages */
        INSTALLED(
            "Installed",
            UdmColors.Badges.installedBackground,
            UdmColors.Badges.installedForeground,
            null
        ),

        /** Light badge with border for version display */
        VERSION(
            "",
            UdmColors.Badges.versionBackground,
            UdmColors.Badges.versionForeground,
            UdmColors.Badges.versionBorder
        ),

        /** Green outline badge for new/latest version */
        LATEST(
            "Latest",
            UdmColors.Badges.latestBackground,
            UdmColors.Badges.latestForeground,
            UdmColors.Badges.latestBorder
        ),

        /** Blue outline badge for plugins */
        PLUGIN(
            "Plugin",
            UdmColors.Badges.pluginBackground,
            UdmColors.Badges.pluginForeground,
            UdmColors.Badges.pluginBorder
        )
    }

    companion object {
        private const val CORNER_RADIUS = 10
        private const val HORIZONTAL_PADDING = 6
        private const val VERTICAL_PADDING = 2

        /**
         * Create an update badge with version info.
         */
        fun updateBadge(version: String): StatusBadge {
            return StatusBadge(BadgeType.UPDATE, "↑ $version")
        }

        /**
         * Create a version badge.
         */
        fun versionBadge(version: String): StatusBadge {
            return StatusBadge(BadgeType.VERSION, version)
        }

        /**
         * Create a badge based on package status.
         */
        fun forPackageStatus(
            hasUpdate: Boolean = false,
            isVulnerable: Boolean = false,
            isTransitive: Boolean = false,
            isDeprecated: Boolean = false,
            isPrerelease: Boolean = false,
            updateVersion: String? = null
        ): StatusBadge? {
            return when {
                isVulnerable -> StatusBadge(BadgeType.VULNERABLE)
                hasUpdate && updateVersion != null -> updateBadge(updateVersion)
                hasUpdate -> StatusBadge(BadgeType.UPDATE)
                isDeprecated -> StatusBadge(BadgeType.DEPRECATED)
                isTransitive -> StatusBadge(BadgeType.TRANSITIVE)
                isPrerelease -> StatusBadge(BadgeType.PRERELEASE)
                else -> null
            }
        }
    }

    init {
        isOpaque = false
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = badgeType.foregroundColor
        horizontalAlignment = CENTER
        border = JBUI.Borders.empty(VERTICAL_PADDING, HORIZONTAL_PADDING)
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val width = width.toFloat()
        val height = height.toFloat()

        // Draw background
        val shape = RoundRectangle2D.Float(
            0f, 0f, width - 1, height - 1,
            CORNER_RADIUS.toFloat(), CORNER_RADIUS.toFloat()
        )

        g2d.color = badgeType.backgroundColor
        g2d.fill(shape)

        // Draw border if specified
        badgeType.borderColor?.let { borderColor ->
            g2d.color = borderColor
            g2d.stroke = BasicStroke(1f)
            g2d.draw(shape)
        }

        g2d.dispose()

        // Draw text
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val textWidth = fm.stringWidth(text ?: "")
        val textHeight = fm.height
        return Dimension(
            textWidth + (HORIZONTAL_PADDING * 2) + 2,
            textHeight + (VERTICAL_PADDING * 2)
        )
    }

    override fun getMinimumSize(): Dimension = preferredSize
}

/**
 * A panel that can display multiple status badges horizontally.
 */
class StatusBadgePanel : JComponent() {
    private val badges = mutableListOf<StatusBadge>()

    init {
        layout = FlowLayout(FlowLayout.LEFT, 4, 0)
        isOpaque = false
    }

    fun addBadge(badge: StatusBadge) {
        badges.add(badge)
        add(badge)
    }

    fun clearBadges() {
        badges.clear()
        removeAll()
    }

    fun setBadges(vararg newBadges: StatusBadge?) {
        clearBadges()
        newBadges.filterNotNull().forEach { addBadge(it) }
        revalidate()
        repaint()
    }

    /**
     * Set badges based on package status.
     */
    fun setPackageStatus(
        hasUpdate: Boolean = false,
        isVulnerable: Boolean = false,
        isTransitive: Boolean = false,
        isDeprecated: Boolean = false,
        isPrerelease: Boolean = false,
        updateVersion: String? = null,
        installedVersion: String? = null
    ) {
        clearBadges()

        // Add version badge first if installed
        if (installedVersion != null) {
            addBadge(StatusBadge.versionBadge(installedVersion))
        }

        // Add status badges in priority order
        if (isVulnerable) {
            addBadge(StatusBadge(StatusBadge.BadgeType.VULNERABLE))
        }
        if (hasUpdate && updateVersion != null) {
            addBadge(StatusBadge.updateBadge(updateVersion))
        } else if (hasUpdate) {
            addBadge(StatusBadge(StatusBadge.BadgeType.UPDATE))
        }
        if (isDeprecated) {
            addBadge(StatusBadge(StatusBadge.BadgeType.DEPRECATED))
        }
        if (isTransitive) {
            addBadge(StatusBadge(StatusBadge.BadgeType.TRANSITIVE))
        }
        if (isPrerelease) {
            addBadge(StatusBadge(StatusBadge.BadgeType.PRERELEASE))
        }

        revalidate()
        repaint()
    }
}
