package com.ftptv.app

import android.content.Context
import android.content.SharedPreferences

class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ftp_config", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "10.20.30.40") ?: "10.20.30.40"
        set(v) = prefs.edit().putString("server", v).apply()

    var port: Int
        get() = prefs.getInt("port", 80)
        set(v) = prefs.edit().putInt("port", v).apply()

    fun getFavorites(): Set<String> {
        val raw = prefs.getString("favorites2", "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split("\n").toSet()
    }

    fun toggleFavorite(streamUrl: String): Boolean {
        val favs = getFavorites().toMutableSet()
        return if (favs.contains(streamUrl)) {
            favs.remove(streamUrl)
            prefs.edit().putString("favorites2", favs.joinToString("\n")).apply()
            false
        } else {
            favs.add(streamUrl)
            prefs.edit().putString("favorites2", favs.joinToString("\n")).apply()
            true
        }
    }

    fun isFavorite(streamUrl: String): Boolean {
        return getFavorites().contains(streamUrl)
    }
}
