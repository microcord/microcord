package com.discord.plugins

import com.discord.utilities.cache.SharedPreferencesProvider

object PluginLoadingPolicy {
    const val ENABLED_KEY = "plugins_loading_enabled"
    private const val PACKAGE_PREFIX = "plugin_package_enabled_"
    private const val PACKAGE_SIGNER_PREFIX = "plugin_package_signer_"
    private const val PLUGIN_PREFIX = "plugin_enabled_"
    private const val STARTING_PLUGIN_KEY = "plugin_starting_id"
    private const val STARTING_PACKAGE_KEY = "plugin_starting_package"

    fun isEnabled(): Boolean =
        SharedPreferencesProvider.getOrNull()?.getBoolean(ENABLED_KEY, true) ?: true

    fun setEnabled(enabled: Boolean) {
        SharedPreferencesProvider.get().edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun isPackageEnabled(packageName: String): Boolean =
        SharedPreferencesProvider.getOrNull()?.getBoolean(PACKAGE_PREFIX + packageName, false) ?: false

    fun setPackageEnabled(packageName: String, enabled: Boolean, signerSha256: String? = null) {
        SharedPreferencesProvider.get().edit()
            .putBoolean(PACKAGE_PREFIX + packageName, enabled)
            .apply {
                if (signerSha256 != null) putString(PACKAGE_SIGNER_PREFIX + packageName, signerSha256)
            }
            .apply()
    }

    fun approvedSigner(packageName: String): String? =
        SharedPreferencesProvider.getOrNull()?.getString(PACKAGE_SIGNER_PREFIX + packageName, null)

    fun enabledPackages(): Set<String> = SharedPreferencesProvider.getOrNull()?.all.orEmpty()
        .filter { (key, value) -> key.startsWith(PACKAGE_PREFIX) && value == true }
        .keys
        .mapTo(mutableSetOf()) { it.removePrefix(PACKAGE_PREFIX) }

    fun isPluginEnabled(pluginId: String): Boolean =
        SharedPreferencesProvider.getOrNull()?.getBoolean(PLUGIN_PREFIX + pluginId, true) ?: true

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        SharedPreferencesProvider.get().edit().putBoolean(PLUGIN_PREFIX + pluginId, enabled).apply()
    }

    fun markBlockingStart(pluginId: String, packageName: String?) {
        SharedPreferencesProvider.get().edit()
            .putString(STARTING_PLUGIN_KEY, pluginId)
            .putString(STARTING_PACKAGE_KEY, packageName)
            .commit()
    }

    fun clearBlockingStart() {
        SharedPreferencesProvider.get().edit()
            .remove(STARTING_PLUGIN_KEY)
            .remove(STARTING_PACKAGE_KEY)
            .commit()
    }

    fun recoverInterruptedBlockingStart() {
        val preferences = SharedPreferencesProvider.getOrNull() ?: return
        val pluginId = preferences.getString(STARTING_PLUGIN_KEY, null) ?: return
        val packageName = preferences.getString(STARTING_PACKAGE_KEY, null)
        preferences.edit()
            .putBoolean(PLUGIN_PREFIX + pluginId, false)
            .apply {
                if (packageName != null) putBoolean(PACKAGE_PREFIX + packageName, false)
            }
            .remove(STARTING_PLUGIN_KEY)
            .remove(STARTING_PACKAGE_KEY)
            .commit()
    }
}
