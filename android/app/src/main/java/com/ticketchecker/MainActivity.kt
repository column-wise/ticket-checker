package com.ticketchecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.ticketchecker.databinding.ActivityMainBinding
import com.ticketchecker.notification.NotificationHelper
import com.ticketchecker.notification.NotificationHelper.EXTRA_TAB
import com.ticketchecker.notification.NotificationHelper.TAB_INTERPARK
import com.ticketchecker.notification.NotificationHelper.TAB_MELON
import com.ticketchecker.ui.interpark.InterparkFragment
import com.ticketchecker.ui.melon.MelonFragment
import com.ticketchecker.ui.monitor.MonitorFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TAG_INTERPARK = "fragment_interpark"
        private const val TAG_MELON = "fragment_melon"
        private const val TAG_MONITOR = "fragment_monitor"
    }

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Notification permission granted: $granted")
        requestBatteryOptimizationExemption()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge 허용 후 insets를 직접 적용 (카메라 홀 포함)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // systemBars + displayCutout 모두 처리
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        NotificationHelper.createChannels(this)
        setupFragments(savedInstanceState)
        setupBottomNavigation()
        requestPermissions()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_TAB)) {
            TAB_INTERPARK -> {
                binding.bottomNavigation.selectedItemId = R.id.interparkFragment
            }
            TAB_MELON -> {
                binding.bottomNavigation.selectedItemId = R.id.melonFragment
            }
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        // savedInstanceState != null이면 FragmentManager가 이미 복원한 상태
        if (savedInstanceState == null) {
            val interparkFragment = InterparkFragment()
            val melonFragment = MelonFragment()
            val monitorFragment = MonitorFragment()

            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, interparkFragment, TAG_INTERPARK)
                .add(R.id.fragment_container, melonFragment, TAG_MELON)
                .hide(melonFragment)
                .add(R.id.fragment_container, monitorFragment, TAG_MONITOR)
                .hide(monitorFragment)
                .commit()
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.interparkFragment -> showFragment(TAG_INTERPARK)
                R.id.melonFragment     -> showFragment(TAG_MELON)
                R.id.monitorFragment   -> showFragment(TAG_MONITOR)
            }
            true
        }
    }

    private fun showFragment(tag: String) {
        val transaction = supportFragmentManager.beginTransaction()
        listOf(TAG_INTERPARK, TAG_MELON, TAG_MONITOR).forEach { t ->
            val fragment = supportFragmentManager.findFragmentByTag(t) ?: return@forEach
            if (t == tag) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commit()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("배터리 최적화 예외")
                .setMessage("취소표를 안정적으로 감지하려면 배터리 최적화를 비활성화해야 합니다.\n설정으로 이동하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open battery optimization settings", e)
                    }
                }
                .setNegativeButton("나중에") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }
}
