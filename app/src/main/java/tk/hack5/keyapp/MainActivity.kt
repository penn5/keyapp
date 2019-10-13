package tk.hack5.keyapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanSettings
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.*
import java.security.SecureRandom

@ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    private var serviceBinder: BleService.BleBinder? = null
    private var ongoingScan: ScanCallbackSub? = null
    private var dialog: AlertDialog? = null

    private var state: State = State.DISCONNECTED

    private fun setStatus(string: Int) {
        findViewById<TextView>(R.id.status).text =
            getText(R.string.status_pre).toString().format(getText(string))
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            Snackbar.make(view, "add new user", Snackbar.LENGTH_LONG)
                .setAction("meh") { scanForDevice() }.show()
        }
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!
    }

    override fun onPause() {
        ongoingScan?.let {
            dialog?.dismiss()
//            bluetoothAdapter.bluetoothLeScanner.stopScan(it)
        }
        super.onPause()
    }

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
                        scanForDevice()
                    }
                    .setNegativeButton(R.string.exit_button) { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                        this.finish()
                    }
                    .setCancelable(false)
                    .show()
                    .setCanceledOnTouchOutside(false)
            }
        }
        bluetoothAdapter.takeIf { !it.isEnabled }?.apply {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BT_REQUEST)
            return
        }
        val settingsBuilder = ScanSettings.Builder()
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
            .setReportDelay(0)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= 26) {
            settingsBuilder.setLegacy(false)
            settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        val settings = settingsBuilder.build()
        val adapter = ScanAdapter(this)
        val callback = ScanCallbackSub(this, adapter, BleService.SERVICE_UUID, DEVICE_NAME)
        ongoingScan = callback
        bluetoothAdapter.bluetoothLeScanner.startScan(emptyList(), settings, callback)
        dialog = AlertDialog.Builder(this).setTitle("Scan Results")
            .setAdapter(adapter) { dialog: DialogInterface, position: Int ->
                dialog.dismiss()
                adapter.getItem(position)?.let {
                    bindService(Intent(this, BleService::class.java), object : ServiceConnection {
                        override fun onServiceDisconnected(name: ComponentName) {
                            Log.e(tag, "Service disconnected")
                            setStatus(R.string.status_disconnected)
                        }

                        @SuppressLint("ApplySharedPref")
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            setStatus(R.string.status_connecting)
                            this@MainActivity.serviceBinder = binder as BleService.BleBinder
                            binder.registerCallbacks({
                                Log.d(tag, "Device connected!")
                                state = State.CONNECTED
                            }, {
                                runOnUiThread { setStatus(R.string.status_disconnected) }
                                Log.e(tag, "Device disconnected!")
                                state = State.DISCONNECTED
                            }, { data: ByteArray ->
                                if (data.toUByteArray()[0].toInt() == 0xE0 && state == State.CONNECTED) {
                                    val sharedPreferences =
                                        getSharedPreferences("", Context.MODE_PRIVATE)
                                    if (!sharedPreferences.contains("")) {
                                        sharedPreferences.edit()
                                            .putLong("", SecureRandom().nextLong()).commit()
                                    }
                                    val authKey = sharedPreferences.getLong("", 0).toULong()
                                    val authUBytes = UByteArray(4)
                                    for (i in 0..3) {
                                        authUBytes[i] = authKey.shr(i * 8).toUByte()
                                    }
                                    state = State.PAIRING
                                    binder.sendData(authUBytes.toByteArray())

                                } else if (data.toUByteArray()[0].toInt() == 0xFD && state == State.PAIRING) {
                                    state = State.IDLE
                                    // Device found, we are reconnected
                                    runOnUiThread { setStatus(R.string.status_connected) }
                                } else if (data.toUByteArray()[0].toInt() == 0xCE && state == State.PAIRING) {
                                    state = State.IDLE
                                    // Device created, we are connected for first time
                                    runOnUiThread { setStatus(R.string.status_connected_firsttime) }
                                } else if (data.toUByteArray()[0].toInt() == 0xED && state == State.PAIRING) {
                                    state = State.DISCONNECTED
                                    // All slots used, show error
                                    runOnUiThread { setStatus(R.string.status_error) }
                                } else if (state == State.PENDING_PING) {
                                    state = State.IDLE
                                    val builder = AlertDialog.Builder(this@MainActivity)
                                        .setTitle(R.string.ping_title)
                                        .setMessage(
                                            when (data.toUByteArray()[0].toInt()) {
                                                0xAC -> R.string.ping_ack
                                                else -> R.string.ping_not_ack
                                            }
                                        )
                                    runOnUiThread { builder.show() }
                                } else if (state == State.PENDING_CHECK) {
                                    state = State.IDLE
                                    val builder = AlertDialog.Builder(this@MainActivity)
                                    if (data.toUByteArray()[0].toInt() in 0..0b1111) {
                                        // Some keys as a bitmask
                                        builder.setMessage(data.toUByteArray()[0].toInt().toString(2))
                                    } else {
                                        builder.setMessage(R.string.check_fail)
                                    }
                                    builder.setTitle(R.string.check_title)
                                    runOnUiThread { builder.show() }
                                }
                                //runOnUiThread { findViewById<TextView>(R.id.status).text = findViewById<TextView>(R.id.status).text.toString() + "\n" + data.asUByteArray().contentToString() }

                                Log.e(
                                    tag,
                                    "Incoming Data! ${data.asUByteArray().contentToString()} ($state)"
                                )

                            })
                            binder.connectDevice(it.device)
                        }
                    }, Context.BIND_AUTO_CREATE)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == ENABLE_BT_REQUEST && resultCode == RESULT_OK)
            scanForDevice()
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("UNUSED_PARAMETER") // Required for the view to be used by reflection from the layout
    fun ping(view: View) {
        if (state == State.IDLE) {
            state = State.PENDING_PING
            serviceBinder?.sendData("p".toByteArray(Charsets.ISO_8859_1))
        }
    }

    @Suppress("UNUSED_PARAMETER") // Required for the view to be used by reflection from the layout
    fun check(view: View) {
        if (state == State.IDLE) {
            state = State.PENDING_CHECK
            serviceBinder?.sendData("c".toByteArray(Charsets.ISO_8859_1))
        }
    }

    companion object {
        private const val tag = "KeyHolderMainActivity"
        private const val ENABLE_BT_REQUEST = 1
        private const val DEVICE_NAME = "Key Holder"

        enum class State { DISCONNECTED, CONNECTED, PAIRING, IDLE, PENDING_PING, PENDING_CHECK }
    }
}
