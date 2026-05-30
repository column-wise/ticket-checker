package com.ticketchecker.ui.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ticketchecker.checker.InterparkChecker
import com.ticketchecker.model.InterparkTarget
import com.ticketchecker.model.MelonTarget
import com.ticketchecker.service.TicketCheckerService
import com.ticketchecker.storage.TargetStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = TargetStorage.getInstance(application)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val interparkChecker = InterparkChecker(okHttpClient)

    private val _interparkTarget = MutableLiveData<InterparkTarget?>()
    val interparkTarget: LiveData<InterparkTarget?> = _interparkTarget

    private val _melonTarget = MutableLiveData<MelonTarget?>()
    val melonTarget: LiveData<MelonTarget?> = _melonTarget

    private val _isServiceRunning = MutableLiveData<Boolean>()
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    fun refresh() {
        _interparkTarget.value = storage.loadInterparkTarget()
        _melonTarget.value = storage.loadMelonTarget()
        _isServiceRunning.value = TicketCheckerService.isRunning
    }

    fun onServiceStartRequested() {
        _isServiceRunning.value = true
    }

    fun onServiceStopRequested() {
        _isServiceRunning.value = false
    }

    fun clearInterparkTarget() {
        storage.clearInterparkTarget()
        _interparkTarget.value = null
    }

    fun clearMelonTarget() {
        storage.clearMelonTarget()
        _melonTarget.value = null
    }

    /** 저장된 세션으로 API를 호출해 전체 좌석 등급명 목록을 반환합니다. */
    suspend fun fetchGradeNames(): List<String> = withContext(Dispatchers.IO) {
        val target = _interparkTarget.value ?: return@withContext emptyList()
        interparkChecker.fetchAllGradeNames(target)
    }

    fun updateInterparkWatchGrades(grades: List<String>) {
        val current = _interparkTarget.value ?: return
        val updated = current.copy(watchGrades = grades)
        storage.saveInterparkTarget(updated)
        _interparkTarget.value = updated
    }

    fun updateInterparkGoodsName(name: String) {
        val current = _interparkTarget.value ?: return
        val updated = current.copy(goodsName = name)
        storage.saveInterparkTarget(updated)
        _interparkTarget.value = updated
    }

    fun updateInterparkPlayDate(date: String) {
        val current = _interparkTarget.value ?: return
        val updated = current.copy(playDate = date)
        storage.saveInterparkTarget(updated)
        _interparkTarget.value = updated
    }

    fun updateMelonName(name: String) {
        val current = _melonTarget.value ?: return
        val updated = current.copy(name = name)
        storage.saveMelonTarget(updated)
        _melonTarget.value = updated
    }
}
