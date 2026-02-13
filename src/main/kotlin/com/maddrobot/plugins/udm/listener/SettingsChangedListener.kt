package com.maddrobot.plugins.udm.listener

import com.intellij.util.messages.Topic

/**
 * Listener interface for observing changes to the settings in the Package Finder plugin.
 *
 * This interface can be implemented by components that need to react to changes
 * in the user-defined configuration settings. The implementation of the `onSettingsChanged`
 * method will execute whenever there is a change in the settings.
 *
 * The `TOPIC` companion object serves as the message bus topic for this listener, providing
 * a standardized channel for communicating settings change events within the plugin’s architecture.
 */
interface SettingsChangedListener {

    companion object {
        val TOPIC = Topic.create("PackageFinder.SettingsChanged", SettingsChangedListener::class.java)
    }

    fun onSettingsChanged()
}
