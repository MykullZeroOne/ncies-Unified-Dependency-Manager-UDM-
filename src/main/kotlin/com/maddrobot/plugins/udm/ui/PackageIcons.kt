package com.maddrobot.plugins.udm.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Icon provider for package states in the Unified Dependency Manager.
 * Provides icons for installed, update available, vulnerable, transitive,
 * available to install, and deprecated states.
 */
object PackageIcons {

    // Base size for generated icons
    private const val ICON_SIZE = 16
    private const val BADGE_SIZE = 8

    // Color definitions for different states
    private val COLOR_INSTALLED = JBColor(Color(0x4CAF50), Color(0x81C784))
    private val COLOR_UPDATE = JBColor(Color(0x4CAF50), Color(0x81C784))
    private val COLOR_VULNERABLE = JBColor(Color(0xF44336), Color(0xE57373))
    private val COLOR_TRANSITIVE = JBColor(Color(0x9E9E9E), Color(0x757575))
    private val COLOR_AVAILABLE = JBColor(Color(0x2196F3), Color(0x64B5F6))
    private val COLOR_DEPRECATED = JBColor(Color(0xFF9800), Color(0xFFB74D))
    private val COLOR_PRERELEASE = JBColor(Color(0x9C27B0), Color(0xBA68C8))

    /**
     * Package state enumeration with associated icons.
     */
    enum class PackageState {
        /** Package is installed and up to date */
        INSTALLED,
        /** Package has an available update */
        UPDATE_AVAILABLE,
        /** Package has known security vulnerabilities */
        VULNERABLE,
        /** Package is a transitive (indirect) dependency */
        TRANSITIVE,
        /** Package is available to install but not installed */
        AVAILABLE,
        /** Package is deprecated */
        DEPRECATED,
        /** Package is a prerelease version */
        PRERELEASE
    }

    /**
     * Get the appropriate icon for a package based on its state.
     */
    fun getIcon(state: PackageState): Icon {
        return when (state) {
            PackageState.INSTALLED -> createInstalledIcon()
            PackageState.UPDATE_AVAILABLE -> createUpdateAvailableIcon()
            PackageState.VULNERABLE -> createVulnerableIcon()
            PackageState.TRANSITIVE -> createTransitiveIcon()
            PackageState.AVAILABLE -> createAvailableIcon()
            PackageState.DEPRECATED -> createDeprecatedIcon()
            PackageState.PRERELEASE -> createPrereleaseIcon()
        }
    }

    /**
     * Get icon for a package based on its properties.
     */
    fun getIconForPackage(
        isInstalled: Boolean,
        hasUpdate: Boolean = false,
        isVulnerable: Boolean = false,
        isTransitive: Boolean = false,
        isDeprecated: Boolean = false,
        isPrerelease: Boolean = false
    ): Icon {
        return when {
            isVulnerable -> getIcon(PackageState.VULNERABLE)
            hasUpdate -> getIcon(PackageState.UPDATE_AVAILABLE)
            isDeprecated -> getIcon(PackageState.DEPRECATED)
            isTransitive -> getIcon(PackageState.TRANSITIVE)
            isPrerelease && isInstalled -> getIcon(PackageState.PRERELEASE)
            isInstalled -> getIcon(PackageState.INSTALLED)
            else -> getIcon(PackageState.AVAILABLE)
        }
    }

    /**
     * Create a composite icon with a base icon and a status overlay badge.
     */
    fun createCompositeIcon(baseIcon: Icon, state: PackageState): Icon {
        val layeredIcon = LayeredIcon(2)
        layeredIcon.setIcon(baseIcon, 0)
        layeredIcon.setIcon(createStatusBadgeIcon(state), 1, 8, 8)
        return layeredIcon
    }

    // ========== Icon Creation Methods ==========

    /**
     * Green checkmark icon for installed packages.
     */
    private fun createInstalledIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_INSTALLED
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            // Draw checkmark
            val path = Path2D.Float()
            path.moveTo(3.0, size / 2.0)
            path.lineTo(6.0, size - 4.0)
            path.lineTo(size - 3.0, 4.0)
            g2d.draw(path)
        }
    }

    /**
     * Green up arrow icon for packages with updates.
     */
    private fun createUpdateAvailableIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_UPDATE
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val centerX = size / 2.0
            val arrowWidth = 4.0

            // Draw up arrow
            val path = Path2D.Float()
            // Vertical line
            path.moveTo(centerX, size - 4.0)
            path.lineTo(centerX, 4.0)
            // Arrow head
            path.moveTo(centerX - arrowWidth, 7.0)
            path.lineTo(centerX, 3.0)
            path.lineTo(centerX + arrowWidth, 7.0)
            g2d.draw(path)
        }
    }

    /**
     * Red shield with X icon for vulnerable packages.
     */
    private fun createVulnerableIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_VULNERABLE

            // Draw shield shape
            val shieldPath = Path2D.Float()
            shieldPath.moveTo(size / 2.0, 2.0)
            shieldPath.lineTo(size - 2.0, 5.0)
            shieldPath.lineTo(size - 2.0, 9.0)
            shieldPath.quadTo(size / 2.0, size.toDouble(), size / 2.0, size - 2.0)
            shieldPath.quadTo(size / 2.0, size.toDouble(), 2.0, 9.0)
            shieldPath.lineTo(2.0, 5.0)
            shieldPath.closePath()
            g2d.fill(shieldPath)

            // Draw X in white
            g2d.color = JBColor.WHITE
            g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val xPath = Path2D.Float()
            xPath.moveTo(5.0, 5.0)
            xPath.lineTo(11.0, 11.0)
            xPath.moveTo(11.0, 5.0)
            xPath.lineTo(5.0, 11.0)
            g2d.draw(xPath)
        }
    }

    /**
     * Gray lock icon for transitive dependencies.
     */
    private fun createTransitiveIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_TRANSITIVE

            // Draw lock body
            g2d.fillRoundRect(3, 8, 10, 7, 2, 2)

            // Draw lock shackle
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val shacklePath = Path2D.Float()
            shacklePath.moveTo(5.0, 9.0)
            shacklePath.lineTo(5.0, 5.0)
            shacklePath.quadTo(5.0, 2.0, 8.0, 2.0)
            shacklePath.quadTo(11.0, 2.0, 11.0, 5.0)
            shacklePath.lineTo(11.0, 9.0)
            g2d.draw(shacklePath)
        }
    }

    /**
     * Blue plus icon for available packages.
     */
    private fun createAvailableIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_AVAILABLE
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val center = size / 2.0
            val halfSize = 5.0

            // Draw plus sign
            val path = Path2D.Float()
            path.moveTo(center, center - halfSize)
            path.lineTo(center, center + halfSize)
            path.moveTo(center - halfSize, center)
            path.lineTo(center + halfSize, center)
            g2d.draw(path)
        }
    }

    /**
     * Orange warning triangle for deprecated packages.
     */
    private fun createDeprecatedIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_DEPRECATED

            // Draw triangle
            val trianglePath = Path2D.Float()
            trianglePath.moveTo(size / 2.0, 2.0)
            trianglePath.lineTo(size - 2.0, size - 2.0)
            trianglePath.lineTo(2.0, size - 2.0)
            trianglePath.closePath()
            g2d.fill(trianglePath)

            // Draw exclamation mark in white
            g2d.color = JBColor.WHITE
            g2d.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // Stem
            g2d.drawLine(size / 2, 6, size / 2, 10)
            // Dot
            g2d.fillOval(size / 2 - 1, 12, 2, 2)
        }
    }

    /**
     * Purple icon for prerelease packages.
     */
    private fun createPrereleaseIcon(): Icon {
        return createIcon { g2d, size ->
            g2d.color = COLOR_PRERELEASE

            // Draw a flask/beaker shape
            val flaskPath = Path2D.Float()
            flaskPath.moveTo(5.0, 2.0)
            flaskPath.lineTo(5.0, 6.0)
            flaskPath.lineTo(2.0, 13.0)
            flaskPath.lineTo(14.0, 13.0)
            flaskPath.lineTo(11.0, 6.0)
            flaskPath.lineTo(11.0, 2.0)
            flaskPath.closePath()
            g2d.fill(flaskPath)

            // Draw bubbles inside
            g2d.color = JBColor.WHITE
            g2d.fillOval(5, 8, 2, 2)
            g2d.fillOval(8, 10, 2, 2)
            g2d.fillOval(10, 8, 2, 2)
        }
    }

    /**
     * Create a small badge icon for overlays.
     */
    private fun createStatusBadgeIcon(state: PackageState): Icon {
        val color = when (state) {
            PackageState.INSTALLED -> COLOR_INSTALLED
            PackageState.UPDATE_AVAILABLE -> COLOR_UPDATE
            PackageState.VULNERABLE -> COLOR_VULNERABLE
            PackageState.TRANSITIVE -> COLOR_TRANSITIVE
            PackageState.AVAILABLE -> COLOR_AVAILABLE
            PackageState.DEPRECATED -> COLOR_DEPRECATED
            PackageState.PRERELEASE -> COLOR_PRERELEASE
        }

        return createIcon(BADGE_SIZE) { g2d, size ->
            // Draw filled circle
            g2d.color = color
            g2d.fillOval(0, 0, size - 1, size - 1)

            // Draw border
            g2d.color = JBColor.WHITE
            g2d.stroke = BasicStroke(1f)
            g2d.drawOval(0, 0, size - 1, size - 1)
        }
    }

    /**
     * Helper function to create icons programmatically.
     */
    private fun createIcon(size: Int = ICON_SIZE, painter: (Graphics2D, Int) -> Unit): Icon {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        painter(g2d, size)
        g2d.dispose()
        return ImageIcon(image)
    }

    // ========== Commonly Used Composite Icons ==========

    /**
     * Library icon with installed checkmark overlay.
     */
    val LibraryInstalled: Icon by lazy {
        createCompositeIcon(AllIcons.Nodes.PpLib, PackageState.INSTALLED)
    }

    /**
     * Library icon with update arrow overlay.
     */
    val LibraryUpdateAvailable: Icon by lazy {
        createCompositeIcon(AllIcons.Nodes.PpLib, PackageState.UPDATE_AVAILABLE)
    }

    /**
     * Library icon with vulnerability shield overlay.
     */
    val LibraryVulnerable: Icon by lazy {
        createCompositeIcon(AllIcons.Nodes.PpLib, PackageState.VULNERABLE)
    }

    /**
     * Library icon with transitive lock overlay.
     */
    val LibraryTransitive: Icon by lazy {
        createCompositeIcon(AllIcons.Nodes.PpLib, PackageState.TRANSITIVE)
    }

    /**
     * Library icon with available plus overlay.
     */
    val LibraryAvailable: Icon by lazy {
        createCompositeIcon(AllIcons.Nodes.PpLib, PackageState.AVAILABLE)
    }

    /**
     * Library icon with deprecated warning overlay.
     */
    val LibraryDeprecated: Icon by lazy {
        createCompositeIcon(AllIcons.Nodes.PpLib, PackageState.DEPRECATED)
    }

    /**
     * Get the appropriate library icon for a package.
     */
    fun getLibraryIcon(
        isInstalled: Boolean,
        hasUpdate: Boolean = false,
        isVulnerable: Boolean = false,
        isTransitive: Boolean = false,
        isDeprecated: Boolean = false
    ): Icon {
        return when {
            isVulnerable -> LibraryVulnerable
            hasUpdate -> LibraryUpdateAvailable
            isDeprecated -> LibraryDeprecated
            isTransitive -> LibraryTransitive
            isInstalled -> LibraryInstalled
            else -> LibraryAvailable
        }
    }
}
