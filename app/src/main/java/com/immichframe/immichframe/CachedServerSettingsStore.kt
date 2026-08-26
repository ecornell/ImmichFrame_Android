package com.immichframe.immichframe

import android.content.Context
import com.google.gson.Gson

/** Last-known server rendering settings used to make the image cache useful at cold start. */
internal object CachedServerSettingsStore {
    private const val PREFS_NAME = "immichframe_offline_boot"
    private const val KEY_SETTINGS = "server_settings_v1"
    private val gson = Gson()

    fun write(context: Context, settings: Helpers.ServerSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SETTINGS, encode(settings))
            .apply()
    }

    fun read(context: Context): Helpers.ServerSettings? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SETTINGS, null)
        return decode(value)
    }

    internal fun encode(settings: Helpers.ServerSettings): String = gson.toJson(settings)

    internal fun decode(value: String?): Helpers.ServerSettings? = runCatching {
        value?.takeIf(String::isNotBlank)?.let {
            gson.fromJson(it, Helpers.ServerSettings::class.java)
        }
    }.getOrNull()
}
