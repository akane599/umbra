package io.github.akane599.umbra

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Test

/** Defaults of a fresh install (nothing stored yet). */
class AppSettingsTest {

    /** In-memory prefs: every getter returns what's stored or the caller's default. */
    private class MemoryPrefs(private val m: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(key: String, defValue: String?) = m[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = m[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = m[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = m[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = m[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = m[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in m
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val fresh = AppSettings(object : ContextWrapper(null) {
        override fun getPackageName() = "io.github.akane599.umbra"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = MemoryPrefs()
    })

    /** SEC-001: other apps can't start sessions until the user turns "Commands from other apps" on. */
    @Test
    fun commandsFromOtherAppsAreOffByDefault() {
        assertFalse(fresh.allowExternal)
        // The settings switch must show the same default as the getter.
        assertFalse(fresh.getBoolean(AppSettings.K_ALLOW_EXTERNAL, AppSettings.DEF_ALLOW_EXTERNAL))
    }
}
