package com.biosense

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.biosense.ui.RootShell
import com.biosense.ui.theme.BiosenseTheme
import com.biosense.vm.MonitorViewModel

@androidx.compose.material3.ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (hasBlePermissions()) vm.connect() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Proceed if BLE perms are granted; notification perm is best-effort
        // and its denial should not block connecting.
        if (hasBlePermissions()) tryEnableBluetoothThenConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BiosenseTheme {
                RootShell(
                    vm = vm,
                    onRequestConnect = { requestConnectFlow() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Auto-reconnect: if the wearable is already paired and BLE is usable,
        // kick off connectGatt(autoConnect=true) so the OS pounces the moment
        // the device advertises in range — user doesn't have to tap Connect.
        if (vm.canOperateBle() && hasBlePermissions() && vm.isBluetoothEnabled()) {
            vm.autoConnectIfPaired()
        }
    }

    private fun requestConnectFlow() {
        if (!vm.canOperateBle()) return
        if (!hasBlePermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        tryEnableBluetoothThenConnect()
    }

    private fun tryEnableBluetoothThenConnect() {
        if (!vm.isBluetoothEnabled()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        vm.connect()
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        // Foreground-service notification permission (Android 13+); if denied
        // the service still runs but its notification is hidden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun blePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasBlePermissions(): Boolean = blePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
