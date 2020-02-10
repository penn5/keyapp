package tk.hack5.keyapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import tk.hack5.keyapp.Utils.ENABLE_BT_REQUEST
import tk.hack5.keyapp.Utils.GET_PERMISSION_REQUEST
import tk.hack5.keyapp.Utils.SHARED_PREFS_NAME
import java.util.concurrent.TimeUnit


@ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    private var ongoingScan: ScanCallbackSub? = null
    private var dialog: AlertDialog? = null

    private var loaded = false

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var handler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!

        fab.setOnClickListener {
            update()
        }

        enqueueWork()
    }

    override fun onStart() {
        update()
        super.onStart()
    }

    override fun onPause() {
        ongoingScan?.let {
            dialog?.dismiss()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == GET_PERMISSION_REQUEST) {
            update() // It will check whether we have the permission now and re-request if needed
        }
    }

    private fun update() {
        loading(true)
        Utils.update(this, {
            for (i in 0..3) {
                val view = this.findViewById<View>(
                    when (i) {
                        0 -> R.id.box1
                        1 -> R.id.box2
                        2 -> R.id.box3
                        3 -> R.id.box4
                        else -> null!!
                    }
                )
                val color =
                    if (it.keysBitmask shr i and 1 == 1) R.color.key_present else R.color.key_missing
                view.setBackgroundColor(ResourcesCompat.getColor(resources, color, theme))
            }
            loaded = true
            loading(!loaded)
        }, {
            loading(!loaded)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == ENABLE_BT_REQUEST && resultCode == RESULT_OK)
            update()
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    @SuppressLint("ApplySharedPref")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_reset -> {
                createDeviceProtectedStorageContext().getSharedPreferences(SHARED_PREFS_NAME,
                    Context.MODE_PRIVATE).edit().clear().commit()
                update()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loading(loading: Boolean) {
        if (loading) {
            progressBar.visibility = View.VISIBLE
            mainView.visibility = View.GONE
        } else {
            progressBar.visibility = View.GONE
            mainView.visibility = View.VISIBLE
        }
    }

    private fun enqueueWork() {
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(CheckWorker::class.java, 15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                    .setConstraints(Constraints.NONE)
                    .build()
            )
    }
}