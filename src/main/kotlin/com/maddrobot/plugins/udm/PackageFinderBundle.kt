package com.maddrobot.plugins.udm

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/**
 * Provides localized messages for the Package Finder plugin.
 *
 * The `PackageFinderBundle` object is responsible for managing and fetching localized strings
 * from the resource bundle `messages.PackageFinder`. It acts as a centralized utility to retrieve
 * plugin-specific messages or labels with support for localization and parameterized placeholders.
 *
 * Responsibilities:
 * - Manages access to localized resource strings using IntelliJ's `DynamicBundle`.
 * - Supports parameterized messages, where placeholders can be replaced with dynamic values at runtime.
 * - Ensures consistent and centralized use of resource strings across the plugin.
 *
 * Threading Notes:
 * - Methods intended for retrieving messages are thread-safe.
 *
 * Key Methods:
 * - `message`: Fetches the localized string for a given key and replaces placeholders with provided parameters.
 */
object PackageFinderBundle {
    private const val BUNDLE: String = "messages.PackageFinder"
    private val INSTANCE = DynamicBundle(PackageFinderBundle::class.java, BUNDLE)

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return INSTANCE.getMessage(key, *params)
    }
}
