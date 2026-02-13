package com.maddrobot.plugins.udm.licensing

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Standalone license key generator for direct sales.
 *
 * Use cases:
 * - Customers who buy directly from your site
 * - Beta testers
 * - Students (educational discount/free)
 * - Friends & family
 * - Open source contributors
 * - Conference giveaways
 *
 * Run from command line:
 *   ./scripts/generate-license.sh john@example.com
 *   ./scripts/generate-license.sh --beta tester@example.com
 *   ./scripts/generate-license.sh --student student@university.edu
 */
object LicenseKeyGenerator {

    // IMPORTANT: Keep this in sync with LicenseChecker.LICENSE_SALT
    private const val LICENSE_SALT = "UDM-2026-MADDROBOT-SALT"

    /**
     * Generate a license key for a customer
     *
     * @param email Customer's email (used to tie the license to them)
     * @param expiryDate When the license expires (null = 2099-12-31, effectively perpetual)
     * @return License key in format UDM-YYYYMMDD-XXXXXXXX
     */
    fun generateKey(email: String, expiryDate: LocalDate? = null): String {
        val expiry = expiryDate ?: LocalDate.of(2099, 12, 31)
        val dateStr = expiry.format(DateTimeFormatter.BASIC_ISO_DATE)
        val normalizedEmail = email.trim().lowercase()
        val hash = computeHash("$normalizedEmail:$dateStr:$LICENSE_SALT").take(8).uppercase()
        return "UDM-$dateStr-$hash"
    }

    // ==================== Convenience Methods ====================

    /** Standard 1-year license (typical for paid customers) */
    fun generateYearlyKey(email: String): String =
        generateKey(email, LocalDate.now().plusYears(1))

    /** Perpetual license - never expires (lifetime purchase) */
    fun generatePerpetualKey(email: String): String =
        generateKey(email, LocalDate.of(2099, 12, 31))

    /** Beta tester license - 6 months */
    fun generateBetaKey(email: String): String =
        generateKey(email, LocalDate.now().plusMonths(6))

    /** Student license - 1 year (renew annually while enrolled) */
    fun generateStudentKey(email: String): String =
        generateKey(email, LocalDate.now().plusYears(1))

    /** Trial license - 30 days */
    fun generateTrialKey(email: String): String =
        generateKey(email, LocalDate.now().plusDays(30))

    /** Friend/Family license - perpetual */
    fun generateFriendKey(email: String): String =
        generatePerpetualKey(email)

    /** Open source contributor license - 2 years */
    fun generateContributorKey(email: String): String =
        generateKey(email, LocalDate.now().plusYears(2))

    /** Internal/dev license - perpetual, uses special email format */
    fun generateInternalKey(identifier: String): String =
        generateKey("internal-$identifier@maddrobot.com", LocalDate.of(2099, 12, 31))

    // ==================== Validation ====================

    private fun computeHash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Validate a license key format (doesn't check expiry or hash) */
    fun isValidFormat(key: String): Boolean =
        key.matches(Regex("^UDM-\\d{8}-[A-Z0-9]{8}$"))
}

/**
 * Command-line interface for generating license keys
 */
fun main(args: Array<String>) {
    println("=== UDM License Key Generator ===\n")

    when {
        args.isEmpty() -> printUsage()

        args[0] == "generate" && args.size >= 2 -> {
            val email = args[1]
            val expiry = if (args.size >= 3) {
                try {
                    LocalDate.parse(args[2])
                } catch (e: Exception) {
                    println("Error: Invalid date format. Use YYYY-MM-DD")
                    return
                }
            } else {
                LocalDate.now().plusYears(1)
            }
            printLicense(email, expiry, "Customer")
        }

        args[0] == "beta" && args.size >= 2 -> {
            val email = args[1]
            val key = LicenseKeyGenerator.generateBetaKey(email)
            val expiry = LocalDate.now().plusMonths(6)
            printLicense(email, expiry, "Beta Tester", key)
        }

        args[0] == "student" && args.size >= 2 -> {
            val email = args[1]
            val key = LicenseKeyGenerator.generateStudentKey(email)
            val expiry = LocalDate.now().plusYears(1)
            printLicense(email, expiry, "Student", key)
        }

        args[0] == "trial" && args.size >= 2 -> {
            val email = args[1]
            val key = LicenseKeyGenerator.generateTrialKey(email)
            val expiry = LocalDate.now().plusDays(30)
            printLicense(email, expiry, "Trial (30 days)", key)
        }

        args[0] == "friend" && args.size >= 2 -> {
            val email = args[1]
            val key = LicenseKeyGenerator.generateFriendKey(email)
            printLicense(email, LocalDate.of(2099, 12, 31), "Friend/Family (Perpetual)", key)
        }

        args[0] == "contributor" && args.size >= 2 -> {
            val email = args[1]
            val key = LicenseKeyGenerator.generateContributorKey(email)
            val expiry = LocalDate.now().plusYears(2)
            printLicense(email, expiry, "OSS Contributor", key)
        }

        args[0] == "perpetual" && args.size >= 2 -> {
            val email = args[1]
            val key = LicenseKeyGenerator.generatePerpetualKey(email)
            printLicense(email, LocalDate.of(2099, 12, 31), "Perpetual", key)
        }

        args[0] == "internal" && args.size >= 2 -> {
            val identifier = args[1]
            val email = "internal-$identifier@maddrobot.com"
            val key = LicenseKeyGenerator.generateInternalKey(identifier)
            println("Internal/Dev License")
            println("─".repeat(50))
            println("Identifier: $identifier")
            println("Email:      $email")
            println("Key:        $key")
            println("Expires:    Never")
        }

        args[0] == "batch" && args.size >= 2 -> {
            val type = if (args[1].startsWith("--")) args[1].removePrefix("--") else "yearly"
            val emails = if (args[1].startsWith("--")) args.drop(2) else args.drop(1)

            println("Generating ${emails.size} $type licenses:\n")
            println("email,key,expires,type")
            emails.forEach { email ->
                val (key, expiry) = when (type) {
                    "beta" -> LicenseKeyGenerator.generateBetaKey(email) to LocalDate.now().plusMonths(6)
                    "student" -> LicenseKeyGenerator.generateStudentKey(email) to LocalDate.now().plusYears(1)
                    "perpetual" -> LicenseKeyGenerator.generatePerpetualKey(email) to LocalDate.of(2099, 12, 31)
                    else -> LicenseKeyGenerator.generateYearlyKey(email) to LocalDate.now().plusYears(1)
                }
                println("$email,$key,$expiry,$type")
            }
        }

        else -> printUsage()
    }
}

private fun printLicense(email: String, expiry: LocalDate, type: String, key: String? = null) {
    val licenseKey = key ?: LicenseKeyGenerator.generateKey(email, expiry)
    val expiryStr = if (expiry.year >= 2099) "Never (Perpetual)" else expiry.toString()

    println("License Type: $type")
    println("─".repeat(50))
    println("Email:   $email")
    println("Key:     $licenseKey")
    println("Expires: $expiryStr")
    println()
    println("Send this to the recipient:")
    println("─".repeat(50))
    println("""
Your UDM Premium License ($type):

Email: $email
License Key: $licenseKey
Expires: $expiryStr

To activate:
1. Open IntelliJ IDEA
2. Go to Tools > UDM License...
3. Enter your email and license key
4. Click "Activate"

Thank you for using UDM!
    """.trimIndent())
}

private fun printUsage() {
    println("""
Usage:
  generate <email> [expiry]    Standard license (default: 1 year)
  beta <email>                 Beta tester (6 months)
  student <email>              Student (1 year, renewable)
  trial <email>                Trial (30 days)
  friend <email>               Friend/Family (perpetual)
  contributor <email>          OSS contributor (2 years)
  perpetual <email>            Lifetime license
  internal <identifier>        Internal dev license

  batch [--type] <emails...>   Generate multiple (CSV output)
                               Types: yearly, beta, student, perpetual

Examples:
  generate john@example.com              # 1-year license
  generate john@example.com 2027-12-31   # Custom expiry
  beta tester@gmail.com                  # Beta tester
  student alice@university.edu           # Student
  friend mom@family.com                  # Friend/family
  internal keystone-team                 # Internal dev
  batch --student a@edu b@edu c@edu      # Batch student licenses
    """.trimIndent())
}
