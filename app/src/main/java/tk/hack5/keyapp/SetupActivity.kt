package tk.hack5.keyapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_setup.*
import kotlinx.android.synthetic.main.content_setup.*
import tk.hack5.keyapp.Utils.ENABLE_BT_REQUEST
import tk.hack5.keyapp.Utils.GET_PERMISSION_REQUEST
import tk.hack5.keyapp.Utils.SHARED_PREFS_MAC
import tk.hack5.keyapp.Utils.SHARED_PREFS_NAME

@ExperimentalUnsignedTypes
class SetupActivity : AppCompatActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var ongoingScan: ScanCallbackSub? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        setSupportActionBar(toolbar)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!

        fab.setOnClickListener {
            scanForDevice()
        }
    }

    override fun onPause() {
        ongoingScan?.let {
            dialog?.dismiss()
        }
        super.onPause()
    }

    @SuppressLint("ApplySharedPref")
    private fun scanForDevice() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_request)
                    .setMessage(R.string.permission_request_location)
                    .setPositiveButton(R.string.continue_button) { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                        startActivity(Intent().apply {
                            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            data = Uri.fromParts("package", packageName, null)
                        })
                    }
                    .setNegativeButton(R.string.exit_button) { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                        this.finish()
                    }
                    .setCancelable(false)
                    .show()
                    .setCanceledOnTouchOutside(false)
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    GET_PERMISSION_REQUEST
                )
            }
            return
        }
        bluetoothAdapter.takeIf { !it.isEnabled }?.apply {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BT_REQUEST)
            return
        }

        val settingsBuilder = ScanSettings.Builder()
            .setReportDelay(0)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        if (Build.VERSION.SDK_INT >= 23)
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)

        if (Build.VERSION.SDK_INT >= 26)
            settingsBuilder.setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)

        val settings = settingsBuilder.build()
        val adapter = ScanAdapter(this)
        val callback = ScanCallbackSub(
            this, adapter, BleService.SERVICE_UUID,
            DEVICE_NAME
        )
        ongoingScan = callback
        bluetoothAdapter.bluetoothLeScanner.startScan(emptyList(), settings, callback)
        dialog = AlertDialog.Builder(this).setTitle("Scan Results")
            .setAdapter(adapter) { dialog: DialogInterface, position: Int ->
                dialog.dismiss()
                adapter.getItem(position)?.let {
                    val sharedPreferences = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        createDeviceProtectedStorageContext()
                    } else {
                        this
                    }.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                    sharedPreferences.edit().putString(SHARED_PREFS_MAC, it.device.address).commit()
                    loading(true)
                    Utils.update(this, {
                        startActivity(Intent(this, MainActivity::class.java)
                            .apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                    }, {
                        loading(false)
                        sharedPreferences.edit().clear().commit()
                    }, false)
                }
            }
            .setOnDismissListener {
                Log.d(tag, "Dismissing scan")
                ongoingScan?.let {
                    Log.d(tag, "Cancelling scan")
                    bluetoothAdapter.bluetoothLeScanner.stopScan(it)
                }
            }
            .show()
    }

    private fun loading(loading: Boolean) {
        if (loading) {
            connectProgress.visibility = View.VISIBLE
            mainText.visibility = View.GONE
        } else {
            connectProgress.visibility = View.GONE
            mainText.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val tag = "KeyHolderSetup"
        private const val DEVICE_NAME = "Key Holder"
    }

}
