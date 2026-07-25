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
            gson.fromJson(json, InterparkTarget::class.java)?.let {
                // Gson은 리플렉션으로 객체를 생성해 Kotlin 기본값을 건너뛰므로,
                // 필드 추가 이전에 저장된 JSON을 로드하면 non-null 필드가 null이 될 수 있음
                @Suppress("SENSELESS_COMPARISON")
                it.copy(
                    goodsName = if (it.goodsName == null) "" else it.goodsName,
                    playDate = if (it.playDate == null) "" else it.playDate,
                    watchGrades = if (it.watchGrades == null) emptyList() else it.watchGrades,
                    cookies = if (it.cookies == null) emptyMap() else it.cookies
                )
            }
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
            gson.fromJson(json, MelonTarget::class.java)?.let {
                @Suppress("SENSELESS_COMPARISON")
                it.copy(
                    pocCode = if (it.pocCode == null) "" else it.pocCode,
                    seatId = if (it.seatId == null) "" else it.seatId,
                    seatGradeName = if (it.seatGradeName == null) "" else it.seatGradeName,
                    playDate = if (it.playDate == null) "" else it.playDate,
                    volume = if (it.volume == null) "1" else it.volume,
                    selectedGradeVolume = if (it.selectedGradeVolume == null) "1" else it.selectedGradeVolume,
                    name = if (it.name == null) "" else it.name,
                    cookies = if (it.cookies == null) emptyMap() else it.cookies
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clearMelonTarget() {
        prefs.edit().remove(KEY_MELON_TARGET).apply()
    }
}
