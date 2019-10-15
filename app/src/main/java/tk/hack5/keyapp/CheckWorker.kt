package tk.hack5.keyapp

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

@ExperimentalUnsignedTypes
class CheckWorker(private val context: Context, params: WorkerParameters) :
    ListenableWorker(context, params) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serviceBinder: BleService.BleBinder? = null
    private var state: State = State.DISCONNECTED
    private var broadcastSelf: PendingIntent? = null


    override fun startWork(): ListenableFuture<Result> {
        Log.d(tag, "Check starting")
        if (broadcastSelf == null)
            broadcastSelf =
                PendingIntent.getBroadcast(context, 0, Intent(context, CheckWorker::class.java), 0)
        return CallbackToFutureAdapter.getFuture { completer: CallbackToFutureAdapter.Completer<Result> ->
            bluetoothAdapter.let {
                val adapter: BluetoothAdapter
                if (it == null)
                    bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!.also { it1 ->
                        adapter = it1
                    }
                else
                    adapter = it
                val sharedPreferences = context.getSharedPreferences("", Context.MODE_PRIVATE)

                val device = adapter.getRemoteDevice(sharedPreferences.getString("mac", null) ?: {
                    Log.e(tag, "Device not set in SharedPreferences")
                    completer.setException(RuntimeException("Device not set in SharedPreferences"))
                    null
                }() ?: return@getFuture null)

                context.getSystemService(AlarmManager::class.java)!!.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1), broadcastSelf
                )

                context.bindService(
                    Intent(context, BleService::class.java),
                    object : ServiceConnection {
                        override fun onServiceDisconnected(name: ComponentName) {
                            Log.e(tag, "Service disconnected")
                            completer.setException(RuntimeException("Service disconnected unexpectedly!"))
                        }

                        @SuppressLint("ApplySharedPref")
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            Log.d(tag, "Service connected")
                            this@CheckWorker.serviceBinder = binder as BleService.BleBinder
                            binder.registerCallbacks({
                                Log.d(tag, "Device connected!")
                                state = State.CONNECTED
                            }, {
                                Log.e(tag, "Device disconnected!")
                                if (state != State.IDLE)
                                    completer.setException(RuntimeException("Device disconnected unexpectedly!"))
                                state = State.DISCONNECTED
                            }, { data: ByteArray ->
                                if (data.toUByteArray()[0].toInt() == 0xE0 && state == State.CONNECTED) {
                                    Log.d(tag, "Got E0")
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
                                    Log.d(tag, "Got FD")
                                    state = State.PENDING_PING
                                    binder.sendData("p".toByteArray(Charsets.ISO_8859_1))
                                    // Device found, we are reconnected
                                } else if (data.toUByteArray()[0].toInt() == 0xCE && state == State.PAIRING) {
                                    Log.e(tag, "Got CE")
                                    state = State.DISCONNECTED
                                    completer.setException(RuntimeException("Device was created, that's wrong"))
                                    // Device created, we are connected for first time
                                } else if (data.toUByteArray()[0].toInt() == 0xED && state == State.PAIRING) {
                                    Log.e(tag, "Got ED")
                                    state = State.DISCONNECTED
                                    completer.setException(RuntimeException("All slots full, that's wrong"))
                                    // All slots used, show error
                                } else if (state == State.PENDING_PING) {
                                    state = State.PENDING_CHECK
                                    binder.sendData("c".toByteArray(Charsets.ISO_8859_1))
                                } else if (state == State.PENDING_CHECK) {
                                    Log.d(
                                        tag,
                                        "Got check response ${data.toUByteArray()[0].toInt()}"
                                    )
                                    state = State.IDLE
                                    completer.set(Result.success()) // TODO
                                }
                                Log.e(
                                    tag,
                                    "Incoming Data! ${data.asUByteArray().contentToString()} ($state)"
                                )

                            })
                            binder.connectDevice(device)
                        }
                    },
                    Context.BIND_AUTO_CREATE
                )
            }
        }
    }

    companion object {
        const val tag = "KeyHolderCheckReceiver"
    }
}
