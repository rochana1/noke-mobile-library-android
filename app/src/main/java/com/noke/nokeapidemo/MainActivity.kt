package com.noke.nokeapidemo

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.noke.nokemobilelibrary.NokeDefines
import com.noke.nokemobilelibrary.NokeDevice
import com.noke.nokemobilelibrary.NokeDeviceManagerService
import com.noke.nokemobilelibrary.NokeMobileError
import com.noke.nokemobilelibrary.NokeServiceListener
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity(), DemoWebClient.DemoWebClientCallback {

    private lateinit var lockLayout: LinearLayout
    private lateinit var lockNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var emailEditText: EditText

    private var nokeService: NokeDeviceManagerService? = null

    private val handler = Handler(Looper.getMainLooper())

    private var currentNoke: NokeDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initiateNokeService()

        lockLayout = findViewById(R.id.lock_layout)
        lockNameText = findViewById(R.id.lock_text)
        statusText = findViewById(R.id.status_text)
        emailEditText = findViewById(R.id.email_input)
        emailEditText.visibility = View.GONE

        lockLayout.setOnClickListener {
            val noke = currentNoke
            if (noke == null) {
                setStatusText("No Device Connected")
            } else {
                val demoWebClient = DemoWebClient().apply { setWebClientCallback(this@MainActivity) }
                if (noke.hardwareVersion.lowercase().contains("f")) {
                    demoWebClient.requestFobSync(noke)
                } else {
                    demoWebClient.requestUnlock(noke, emailEditText.text.toString())
                }
            }
        }
    }

    private fun initiateNokeService() {
        val nokeServiceIntent = Intent(this, NokeDeviceManagerService::class.java)
        bindService(nokeServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, rawBinder: IBinder) {
            Log.w(TAG, "ON SERVICE CONNECTED")
            val binder = rawBinder as NokeDeviceManagerService.LocalBinder
            nokeService = binder.getService(NokeDefines.NOKE_LIBRARY_SANDBOX).apply {
                // register callback listener
                registerNokeListener(nokeServiceListener)

                val macs = listOf("XX:XX:XX:XX:XX:XX")
                macs.forEach { mac ->
                    addNokeDevice(NokeDevice(mac, mac))
                }

                startScanningForNokeDevices()
                this@MainActivity.setStatusText("Scanning for Noke Devices")

                if (!initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth")
                }
            }
        }

        override fun onServiceDisconnected(classname: ComponentName) {
            nokeService = null
        }
    }

    private val nokeServiceListener = object : NokeServiceListener {
        override fun onNokeDiscovered(noke: NokeDevice) {
            setLockLayoutColor(ContextCompat.getColor(this@MainActivity, R.color.nokeBlue))
            val lockState = when (noke.lockState) {
                NokeDefines.NOKE_LOCK_STATE_LOCKED -> "Locked"
                NokeDefines.NOKE_LOCK_STATE_UNLOCKED -> "Unlocked"
                NokeDefines.NOKE_LOCK_STATE_UNSHACKLED -> "Unshackled"
                else -> "Unknown"
            }

            currentNoke = noke
            setStatusText("NOKE DISCOVERED: ${noke.name} ($lockState)")
            nokeService?.connectToNoke(noke)
        }

        override fun onNokeConnecting(noke: NokeDevice) {
            setStatusText("NOKE CONNECTING: ${noke.name}")
        }

        override fun onNokeConnected(noke: NokeDevice) {
            setStatusText("NOKE CONNECTED: ${noke.name}")
            setLockNameText(noke.name)
            setLockLayoutColor(ContextCompat.getColor(this@MainActivity, R.color.nokeBlue))
            currentNoke = noke
            nokeService?.stopScanning()
        }

        override fun onNokeSyncing(noke: NokeDevice) {
            setStatusText("NOKE SYNCING: ${noke.name}")
        }

        override fun onNokeUnlocked(noke: NokeDevice) {
            setStatusText("NOKE UNLOCKED: ${noke.name}")
            setLockLayoutColor(ContextCompat.getColor(this@MainActivity, R.color.unlockGreen))
        }

        override fun onNokeShutdown(noke: NokeDevice, isLocked: Boolean, didTimeout: Boolean) {
            setStatusText("NOKE SHUTDOWN: ${noke.name} LOCKED: $isLocked TIMEOUT: $didTimeout")
        }

        override fun onNokeDisconnected(noke: NokeDevice) {
            setStatusText("NOKE DISCONNECTED: ${noke.name}")
            setLockLayoutColor(ContextCompat.getColor(this@MainActivity, R.color.disconnectGray))
            setLockNameText("No Lock Connected")
            currentNoke = null
            nokeService?.startScanningForNokeDevices()
            nokeService?.setBluetoothScanDuration(8000)
        }

        override fun onDataUploaded(result: Int, message: String) {
            Log.w(TAG, "DATA UPLOADED: $message")
            setStatusText(message)
        }

        override fun onBluetoothStatusChanged(bluetoothStatus: Int) {
            // no-op
        }

        override fun onError(noke: NokeDevice?, error: Int, message: String) {
            Log.e(TAG, "NOKE SERVICE ERROR $error: $message")
            when (error) {
                NokeMobileError.ERROR_LOCATION_SERVICES_DISABLED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        handler.post {
                            val alertDialog = AlertDialog.Builder(this@MainActivity).create()
                            alertDialog.setTitle(getString(R.string.location_access_required))
                            alertDialog.setMessage(getString(R.string.location_permission_request_message))
                            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK") { dialog, _ ->
                                dialog.dismiss()
                            }
                            alertDialog.setOnDismissListener {
                                requestPermissions(
                                    arrayOf(
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ),
                                    PERMISSION_REQUEST_COARSE_LOCATION
                                )
                            }
                            alertDialog.show()
                        }
                    }
                }

                NokeMobileError.ERROR_BLUETOOTH_SCAN_PERMISSION -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        handler.post {
                            val alertDialog = AlertDialog.Builder(this@MainActivity).create()
                            alertDialog.setTitle(getString(R.string.bluetooth_access_required))
                            alertDialog.setMessage(getString(R.string.bluetooth_permission_request_message))
                            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK") { dialog, _ ->
                                dialog.dismiss()
                            }
                            alertDialog.setOnDismissListener {
                                requestPermissions(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ),
                                    PERMISSION_REQUEST_BLUETOOTH_SCAN
                                )
                            }
                            alertDialog.show()
                        }
                    }
                }
            }
        }
    }

    private fun setStatusText(message: String) {
        Log.d(TAG, message)
        handler.post { statusText.text = message }
    }

    private fun setLockNameText(message: String) {
        handler.post { lockNameText.text = message }
    }

    private fun setLockLayoutColor(color: Int) {
        handler.post { lockLayout.setBackgroundColor(color) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        @NonNull permissions: Array<String>,
        @NonNull grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_COARSE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    nokeService?.startScanningForNokeDevices()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.functionality_limited))
                        .setMessage(getString(R.string.no_location_message))
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { }
                        .show()
                }
            }

            PERMISSION_REQUEST_BLUETOOTH_SCAN -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    nokeService?.startScanningForNokeDevices()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.functionality_limited))
                        .setMessage(getString(R.string.no_bluetooth_message))
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { }
                        .show()
                }
            }
        }
    }

    override fun onUnlockReceived(response: String, noke: NokeDevice) {
        try {
            val obj = JSONObject(response)
            if (obj.getBoolean("result")) {
                val data = obj.getJSONObject("data")
                val commandString = data.getString("commands")
                currentNoke?.sendCommands(commandString)
            } else {
                setStatusText("Access Denied")
                setLockLayoutColor(ContextCompat.getColor(this, R.color.alertRed))
            }
        } catch (exception: JSONException) {
            Log.e(TAG, "Failed to parse unlock response", exception)
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PERMISSION_REQUEST_COARSE_LOCATION = 1
        private const val PERMISSION_REQUEST_BLUETOOTH_SCAN = 3
    }
}
