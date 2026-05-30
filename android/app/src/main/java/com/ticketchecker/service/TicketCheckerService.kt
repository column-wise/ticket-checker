package com.ticketchecker.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.ticketchecker.checker.InterparkCheckResult
import com.ticketchecker.checker.InterparkChecker
import com.ticketchecker.checker.MelonCheckResult
import com.ticketchecker.checker.MelonChecker
import com.ticketchecker.notification.NotificationHelper
import com.ticketchecker.storage.TargetStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TicketCheckerService : Service() {

    companion object {
        private const val TAG = "TicketCheckerService"
        const val ACTION_START = "com.ticketchecker.START"
        const val ACTION_STOP = "com.ticketchecker.STOP"
        private const val MIN_INTERVAL_MS = 20_000L
        private const val MAX_INTERVAL_MS = 40_000L
        private const val MAX_CONSECUTIVE_ERRORS = 5

        @Volatile
        var isRunning = false
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var interparkJob: Job? = null
    private var melonJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var storage: TargetStorage
    private lateinit var interparkChecker: InterparkChecker
    private lateinit var melonChecker: MelonChecker

    override fun onCreate() {
        super.onCreate()
        storage = TargetStorage.getInstance(this)
        interparkChecker = InterparkChecker(okHttpClient)
        melonChecker = MelonChecker(okHttpClient)
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                    NotificationHelper.buildForegroundNotification(this)
                )
                isRunning = true
                startPollingLoops()
            }
        }
        return START_STICKY
    }

    private fun startPollingLoops() {
        // Cancel previous jobs if any
        interparkJob?.cancel()
        melonJob?.cancel()

        // Interpark polling loop
        interparkJob = serviceScope.launch {
            var consecutiveErrors = 0
            Log.d(TAG, "Interpark polling loop started")

            while (true) {
                val target = storage.loadInterparkTarget()
                if (target != null) {
                    try {
                        Log.d(TAG, "Checking Interpark: ${target.goodsCode}")
                        when (val result = interparkChecker.check(target)) {
                            is InterparkCheckResult.Available -> {
                                consecutiveErrors = 0
                                val totalCount = result.grades.sumOf { it.remainCnt }
                                val showName = target.goodsName.ifEmpty { "공연 (${target.goodsCode})" }
                                val gradeDetail = result.grades.joinToString("\n") { "  ${it.seatGradeName}: ${it.remainCnt}석 (${it.salesPrice}원)" }
                                Log.i(TAG, "★ 인터파크 잔여석 발견! 총 ${totalCount}석 → $gradeDetail")
                                NotificationHelper.sendCancelTicketAlert(
                                    applicationContext,
                                    showName,
                                    "총 ${totalCount}석 잔여\n$gradeDetail",
                                    "인터파크"
                                )
                            }
                            is InterparkCheckResult.NoTickets -> {
                                consecutiveErrors = 0
                                Log.d(TAG, "Interpark: no tickets")
                            }
                            is InterparkCheckResult.SessionExpired -> {
                                consecutiveErrors = 0
                                Log.w(TAG, "Interpark: session expired")
                                NotificationHelper.sendSessionExpiredAlert(applicationContext, "인터파크")
                                storage.clearInterparkTarget()
                            }
                            is InterparkCheckResult.Error -> {
                                consecutiveErrors++
                                Log.e(TAG, "Interpark error: ${result.message} ($consecutiveErrors)")
                                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                    NotificationHelper.sendErrorAlert(
                                        applicationContext,
                                        "인터파크 연속 오류 발생: ${result.message}"
                                    )
                                    consecutiveErrors = 0
                                }
                            }
                        }
                    } catch (e: Exception) {
                        consecutiveErrors++
                        Log.e(TAG, "Interpark exception", e)
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            NotificationHelper.sendErrorAlert(
                                applicationContext,
                                "인터파크 연속 오류 발생"
                            )
                            consecutiveErrors = 0
                        }
                    }
                } else {
                    consecutiveErrors = 0
                }

                val intervalMs = Random.nextLong(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                Log.d(TAG, "Interpark next check in ${intervalMs / 1000}s")
                delay(intervalMs)
            }
        }

        // Melon polling loop
        melonJob = serviceScope.launch {
            var consecutiveErrors = 0
            // Offset melon loop by 10 seconds to avoid both hitting at once
            delay(10_000L)
            Log.d(TAG, "Melon polling loop started")

            while (true) {
                val target = storage.loadMelonTarget()
                if (target != null) {
                    try {
                        Log.d(TAG, "Checking Melon: ${target.prodId}")
                        when (val result = melonChecker.check(target)) {
                            is MelonCheckResult.Available -> {
                                consecutiveErrors = 0
                                val showName = target.name.ifEmpty { "공연 (${target.prodId})" }
                                Log.i(TAG, "Melon tickets available! rmd=${result.rmdSeatCnt}")
                                NotificationHelper.sendCancelTicketAlert(
                                    applicationContext,
                                    showName,
                                    "${result.rmdSeatCnt}석 잔여",
                                    "멜론티켓"
                                )
                            }
                            is MelonCheckResult.NoTickets -> {
                                consecutiveErrors = 0
                                Log.d(TAG, "Melon: no tickets")
                            }
                            is MelonCheckResult.SessionExpired -> {
                                consecutiveErrors = 0
                                Log.w(TAG, "Melon: session expired")
                                NotificationHelper.sendSessionExpiredAlert(applicationContext, "멜론티켓")
                                storage.clearMelonTarget()
                            }
                            is MelonCheckResult.Error -> {
                                consecutiveErrors++
                                Log.e(TAG, "Melon error: ${result.message} ($consecutiveErrors)")
                                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                    NotificationHelper.sendErrorAlert(
                                        applicationContext,
                                        "멜론티켓 연속 오류 발생: ${result.message}"
                                    )
                                    consecutiveErrors = 0
                                }
                            }
                        }
                    } catch (e: Exception) {
                        consecutiveErrors++
                        Log.e(TAG, "Melon exception", e)
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            NotificationHelper.sendErrorAlert(
                                applicationContext,
                                "멜론티켓 연속 오류 발생"
                            )
                            consecutiveErrors = 0
                        }
                    }
                } else {
                    consecutiveErrors = 0
                }

                val intervalMs = Random.nextLong(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                Log.d(TAG, "Melon next check in ${intervalMs / 1000}s")
                delay(intervalMs)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        interparkJob?.cancel()
        melonJob?.cancel()
        serviceJob.cancel()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
