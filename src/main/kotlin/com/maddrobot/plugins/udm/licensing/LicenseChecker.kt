package com.maddrobot.plugins.udm.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.LicensingFacade
import com.intellij.util.xmlb.XmlSerializerUtil
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Hybrid licensing for UDM plugin.
 *
 * Supports TWO license sources:
 * 1. JetBrains Marketplace - users buy through JetBrains, they handle keys
 * 2. Direct/Custom - you issue keys for your site, beta testers, students, friends
 *
 * Two tiers:
 * - FREE: Basic package search, view installed/updates, single dependency operations
 * - PREMIUM: Everything in Free + vulnerability scanning, bulk ops, dependency tree, private repos
 */
@State(
    name = "UdmLicenseState",
    storages = [Storage("udm-license.xml")]
)
@Service(Service.Level.APP)
class LicenseChecker : PersistentStateComponent<LicenseChecker.LicenseState> {

    private val log = Logger.getInstance(LicenseChecker::class.java)
    private var state = LicenseState()

    companion object {
        // JetBrains Marketplace product code (get this from JetBrains when you register)
        // Leave empty until you register as a paid plugin vendor
        private const val JETBRAINS_PRODUCT_CODE = ""  // e.g., "PUDM"

        // Secret salt for custom license validation (change this for production!)
        private const val LICENSE_SALT = "UDM-2026-KEYSTONE-SALT"

        // Format: UDM-YYYYMMDD-XXXXXXXX (expiry date + hash)
        private val LICENSE_PATTERN = Regex("^UDM-(\\d{8})-([A-Z0-9]{8})$")

        fun getInstance(): LicenseChecker =
            ApplicationManager.getApplication().getService(LicenseChecker::class.java)

        /**
         * Generate a license key (use this offline to create keys for customers)
         */
        fun generateLicenseKey(email: String, expiryDate: LocalDate? = null): String {
            val expiry = expiryDate ?: LocalDate.of(2099, 12, 31)
            val dateStr = expiry.format(DateTimeFormatter.BASIC_ISO_DATE)
            val hash = computeHash("$email:$dateStr:$LICENSE_SALT").take(8).uppercase()
            return "UDM-$dateStr-$hash"
        }

        private fun computeHash(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    data class LicenseState(
        var licenseKey: String = "",
        var email: String = "",
        var activatedAt: Long = 0
    )

    override fun getState(): LicenseState = state

    override fun loadState(state: LicenseState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    // ==================== Public API ====================

    enum class Tier { FREE, PREMIUM }

    enum class LicenseSource {
        NONE,           // No license
        DEVELOPMENT,    // Dev mode bypass
        JETBRAINS,      // Purchased through JetBrains Marketplace
        DIRECT          // Your own license key (site, beta, student, friend)
    }

    /**
     * Current license tier
     */
    fun getTier(): Tier {
        // Dev mode bypass
        if (isDevelopmentMode()) return Tier.PREMIUM

        // Check JetBrains Marketplace license first
        if (hasJetBrainsLicense()) return Tier.PREMIUM

        // Check custom/direct license
        if (isCustomLicenseValid()) return Tier.PREMIUM

        return Tier.FREE
    }

    /**
     * Get the source of the current license
     */
    fun getLicenseSource(): LicenseSource {
        if (isDevelopmentMode()) return LicenseSource.DEVELOPMENT
        if (hasJetBrainsLicense()) return LicenseSource.JETBRAINS
        if (isCustomLicenseValid()) return LicenseSource.DIRECT
        return LicenseSource.NONE
    }

    /**
     * Check if user has premium access
     */
    fun isPremium(): Boolean = getTier() == Tier.PREMIUM

    /**
     * Check if a specific feature is available
     */
    fun canUse(feature: Feature): Boolean {
        return when (feature.tier) {
            Tier.FREE -> true
            Tier.PREMIUM -> isPremium()
        }
    }

    /**
     * Activate a custom license key (for direct sales, beta, students, etc.)
     *
     * @return null if successful, error message if failed
     */
    fun activateLicense(email: String, licenseKey: String): String? {
        val key = licenseKey.trim().uppercase()
        val mail = email.trim().lowercase()

        // Validate format
        val match = LICENSE_PATTERN.matchEntire(key)
            ?: return "Invalid license key format. Expected: UDM-XXXXXXXX-XXXXXXXX"

        val dateStr = match.groupValues[1]
        val providedHash = match.groupValues[2]

        // Check expiry
        val expiryDate = try {
            LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: Exception) {
            return "Invalid date in license key"
        }

        if (expiryDate.isBefore(LocalDate.now())) {
            return "License key has expired on $expiryDate"
        }

        // Validate hash
        val expectedHash = computeHash("$mail:$dateStr:$LICENSE_SALT").take(8).uppercase()
        if (providedHash != expectedHash) {
            return "License key is not valid for this email address"
        }

        // Success - store the license
        state.licenseKey = key
        state.email = mail
        state.activatedAt = System.currentTimeMillis()

        log.info("UDM: Direct license activated for $mail, expires $expiryDate")
        return null
    }

    /**
     * Remove current custom license (doesn't affect JetBrains license)
     */
    fun deactivateLicense() {
        state.licenseKey = ""
        state.email = ""
        state.activatedAt = 0
        log.info("UDM: Direct license deactivated")
    }

    /**
     * Get license info for display
     */
    fun getLicenseInfo(): LicenseInfo {
        val source = getLicenseSource()

        return when (source) {
            LicenseSource.NONE -> LicenseInfo(
                tier = Tier.FREE,
                source = source,
                email = null,
                expiresAt = null,
                description = "Free"
            )
            LicenseSource.DEVELOPMENT -> LicenseInfo(
                tier = Tier.PREMIUM,
                source = source,
                email = null,
                expiresAt = null,
                description = "Development Mode"
            )
            LicenseSource.JETBRAINS -> LicenseInfo(
                tier = Tier.PREMIUM,
                source = source,
                email = null,  // JetBrains doesn't expose this
                expiresAt = null,  // JetBrains handles expiry
                description = "JetBrains Marketplace"
            )
            LicenseSource.DIRECT -> {
                val match = LICENSE_PATTERN.matchEntire(state.licenseKey)
                val expiryDate = match?.groupValues?.get(1)?.let {
                    LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE)
                }
                LicenseInfo(
                    tier = Tier.PREMIUM,
                    source = source,
                    email = state.email,
                    expiresAt = expiryDate,
                    description = "Direct License"
                )
            }
        }
    }

    data class LicenseInfo(
        val tier: Tier,
        val source: LicenseSource,
        val email: String?,
        val expiresAt: LocalDate?,
        val description: String
    )

    // ==================== JetBrains Marketplace Integration ====================

    /**
     * Check if user has a valid JetBrains Marketplace license
     */
    private fun hasJetBrainsLicense(): Boolean {
        // Skip if product code not configured yet
        if (JETBRAINS_PRODUCT_CODE.isBlank()) return false

        return try {
            val facade = LicensingFacade.getInstance() ?: return false
            val stamp = facade.getConfirmationStamp(JETBRAINS_PRODUCT_CODE)
            stamp != null
        } catch (e: Exception) {
            // LicensingFacade might not be available in all IDE versions
            log.debug("UDM: Could not check JetBrains license: ${e.message}")
            false
        }
    }

    // ==================== Custom License Validation ====================

    private fun isCustomLicenseValid(): Boolean {
        if (state.licenseKey.isBlank()) return false

        val match = LICENSE_PATTERN.matchEntire(state.licenseKey) ?: return false
        val dateStr = match.groupValues[1]
        val providedHash = match.groupValues[2]

        // Check expiry
        val expiryDate = try {
            LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: Exception) {
            return false
        }

        if (expiryDate.isBefore(LocalDate.now())) {
            log.info("UDM: Direct license expired on $expiryDate")
            return false
        }

        // Validate hash
        val expectedHash = computeHash("${state.email}:$dateStr:$LICENSE_SALT").take(8).uppercase()
        return providedHash == expectedHash
    }

    // ==================== Development Mode ====================

    private fun isDevelopmentMode(): Boolean {
        return System.getProperty("udm.dev.mode") == "true" ||
               System.getenv("UDM_DEV_MODE") == "true" ||
               ApplicationManager.getApplication().isInternal ||
               isRunningFromBuildDir()
    }

    private fun isRunningFromBuildDir(): Boolean {
        val path = this::class.java.protectionDomain?.codeSource?.location?.path ?: return false
        return path.contains("/build/") || path.contains("\\build\\") ||
               path.contains("/sandbox/") || path.contains("\\sandbox\\")
    }
}

/**
 * Features and their required tier
 */
enum class Feature(val tier: LicenseChecker.Tier, val displayName: String) {
    // Free tier
    PACKAGE_SEARCH(LicenseChecker.Tier.FREE, "Package Search"),
    VIEW_INSTALLED(LicenseChecker.Tier.FREE, "View Installed Dependencies"),
    VIEW_UPDATES(LicenseChecker.Tier.FREE, "View Available Updates"),
    ADD_DEPENDENCY(LicenseChecker.Tier.FREE, "Add Dependency"),
    REMOVE_DEPENDENCY(LicenseChecker.Tier.FREE, "Remove Dependency"),
    UPDATE_SINGLE(LicenseChecker.Tier.FREE, "Update Single Dependency"),

    // Premium tier
    VULNERABILITY_SCAN(LicenseChecker.Tier.PREMIUM, "Vulnerability Scanning"),
    BULK_UPGRADE(LicenseChecker.Tier.PREMIUM, "Bulk Upgrade"),
    VERSION_CONSOLIDATION(LicenseChecker.Tier.PREMIUM, "Version Consolidation"),
    DEPENDENCY_TREE(LicenseChecker.Tier.PREMIUM, "Dependency Tree"),
    TRANSITIVE_ANALYSIS(LicenseChecker.Tier.PREMIUM, "Transitive Dependency Analysis"),
    EXCLUSION_MANAGEMENT(LicenseChecker.Tier.PREMIUM, "Dependency Exclusions"),
    PRIVATE_REPOS(LicenseChecker.Tier.PREMIUM, "Private Repositories"),
    REPO_CREDENTIALS(LicenseChecker.Tier.PREMIUM, "Repository Credentials"),
    VULNERABILITY_ALLOWLIST(LicenseChecker.Tier.PREMIUM, "Vulnerability Allowlist"),
    CACHE_MANAGEMENT(LicenseChecker.Tier.PREMIUM, "Cache Management")
}
