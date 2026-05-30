package com.ticketchecker.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ticketchecker.model.InterparkTarget
import com.ticketchecker.model.MelonTarget

class TargetStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ticket_checker_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_INTERPARK_TARGET = "interpark_target"
        private const val KEY_MELON_TARGET = "melon_target"

        @Volatile
        private var instance: TargetStorage? = null

        fun getInstance(context: Context): TargetStorage {
            return instance ?: synchronized(this) {
                instance ?: TargetStorage(context.applicationContext).also { instance = it }
            }
        }
    }

    fun saveInterparkTarget(target: InterparkTarget) {
        prefs.edit().putString(KEY_INTERPARK_TARGET, gson.toJson(target)).apply()
    }

    fun loadInterparkTarget(): InterparkTarget? {
        val json = prefs.getString(KEY_INTERPARK_TARGET, null) ?: return null
        return try {
            gson.fromJson(json, InterparkTarget::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clearInterparkTarget() {
        prefs.edit().remove(KEY_INTERPARK_TARGET).apply()
    }

    fun saveMelonTarget(target: MelonTarget) {
        prefs.edit().putString(KEY_MELON_TARGET, gson.toJson(target)).apply()
    }

    fun loadMelonTarget(): MelonTarget? {
        val json = prefs.getString(KEY_MELON_TARGET, null) ?: return null
        return try {
            gson.fromJson(json, MelonTarget::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clearMelonTarget() {
        prefs.edit().remove(KEY_MELON_TARGET).apply()
    }
}
