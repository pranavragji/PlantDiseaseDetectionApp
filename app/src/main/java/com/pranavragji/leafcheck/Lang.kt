package com.pranavragji.leafcheck

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

// In-app language switch. Every screen extends BaseActivity, which rebuilds its
// resources in the chosen locale (this also flips the layout to right-to-left for Arabic).
object Lang {
    val tags = listOf("en", "hi", "es", "zh-CN", "ar")
    val names = listOf("English", "हिन्दी", "Español", "中文", "العربية")

    fun current(context: Context): String {
        val saved = context.getSharedPreferences("prefs", 0).getString("lang", null)
        if (saved != null) return saved
        val system = Locale.getDefault().language
        return tags.firstOrNull { it.substringBefore('-') == system } ?: "en"
    }

    fun set(context: Context, tag: String) {
        context.getSharedPreferences("prefs", 0).edit().putString("lang", tag).apply()
    }

    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(current(base))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

open class BaseActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    // Plant and condition texts live in plants.xml, keyed by the model label parts.
    fun text(name: String): String {
        val id = resources.getIdentifier(name, "string", packageName)
        return if (id == 0) name else getString(id)
    }

    fun cropName(label: String) = text("crop_" + cropOf(label))
    fun conditionName(label: String) = text("cond_" + conditionOf(label))
}
