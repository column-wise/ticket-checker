package com.ticketchecker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ticketchecker.storage.TargetStorage

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val storage = TargetStorage.getInstance(context)
        val hasInterpark = storage.loadInterparkTarget() != null
        val hasMelon = storage.loadMelonTarget() != null

        if (hasInterpark || hasMelon) {
            Log.d(TAG, "Boot completed, starting TicketCheckerService")
            val serviceIntent = Intent(context, TicketCheckerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
