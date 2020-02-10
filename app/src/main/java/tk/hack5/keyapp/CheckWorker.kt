package tk.hack5.keyapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.random.Random

@ExperimentalUnsignedTypes
class CheckWorker(private val context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
    private val checker = Checker(context)

    override fun startWork(): ListenableFuture<Result> {
        return CallbackToFutureAdapter.getFuture { completer: CallbackToFutureAdapter.Completer<Result> ->
            val ret = checker.startWork()
            ret.addListener({
                val result = ret.get()

                if (result.errorReason == ErrorReason.BT_OFF) {
                    val notificationManager =
                        NotificationManagerCompat.from(context)

                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBtIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        notificationManager.createNotificationChannel(
                            NotificationChannel(
                                CHANNEL_ID,
                                context.getString(R.string.notification_channel),
                                NotificationManager.IMPORTANCE_HIGH
                            )
                        )
                    notificationManager.notify(
                        0,
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .setContentTitle(context.getString(R.string.bt_notification_title))
                            .setContentText(context.getString(R.string.bt_notification_text))
                            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.bt_notification_text_long)))
                            .setContentIntent(PendingIntent.getActivity(context, 0, enableBtIntent, 0))
                            .build()
                    )
                } else if (result.errorReason != null) {
                    // TODO
                    completer.set(Result.failure())
                    return@addListener
                }

                val notificationManager = NotificationManagerCompat.from(context)

                if (result.keysBitmask shr result.keyId and 1 == 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        notificationManager.createNotificationChannel(
                            NotificationChannel(
                                CHANNEL_ID,
                                context.getString(R.string.notification_channel),
                                NotificationManager.IMPORTANCE_HIGH
                            )
                        )
                    notificationManager.notify(
                        0,
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .setContentTitle(context.getString(R.string.notification_title))
                            .setContentText(context.getString(R.string.notification_text))
                            .build()
                    )
                } else {
                    notificationManager.cancelAll()
                }

                completer.set(Result.success())
            }, taskExecutor::executeOnBackgroundThread)
        }
    }
    companion object {
        const val CHANNEL_ID = "keys"
    }
}

@ExperimentalUnsignedTypes
class Checker(private val context: Context) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serviceBinder: BleService.BleBinder? = null
    private var state: State = State.DISCONNECTED
    private var keyId = -1


    fun startWork(): ListenableFuture<Result> {
        return CallbackToFutureAdapter.getFuture { completer: CallbackToFutureAdapter.Completer<Result> ->
            thread(start = true) {
                lock.acquire()
                Log.d(tag, "Check starting")
                bluetoothAdapter.let {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        lock.release()
                        completer.set(Result(ErrorReason.PERMISSIONS_MISSING, 0, 0))
                        return@thread
                    }

                    val adapter: BluetoothAdapter
                    if (it == null)
                        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!.also { it1 ->
                            adapter = it1
                        }
                    else
                        adapter = it
                    val sharedPreferences = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        context.createDeviceProtectedStorageContext()
                    } else {
                        context
                    }.getSharedPreferences("prefs", Context.MODE_PRIVATE)

                    val device =
                        adapter.getRemoteDevice(sharedPreferences.getString("mac", null) ?: {
                            Log.e(tag, "Device not set in SharedPreferences")
                            lock.release()
                            completer.set(Result(ErrorReason.UNPAIRED, 0, 0))
                            null
                        }() ?: return@thread)

                    context.bindService(
                        Intent(context, BleService::class.java),
                        object : ServiceConnection {
                            override fun onServiceDisconnected(name: ComponentName) {
                                Log.d(tag, "Service disconnected")
                                lock.release()
                                completer.set(Result(ErrorReason.CONNECT_FAILED, 0, 0))
                            }

                            @SuppressLint("ApplySharedPref")
                            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                Log.d(tag, "Service connected")
                                serviceBinder = binder as BleService.BleBinder
                                binder.registerCallbacks({
                                    Log.d(tag, "Device connected!")
                                    state = State.CONNECTED
                                }, {
                                    Log.d(tag, "Device disconnected!")
                                    state = State.DISCONNECTED
                                    lock.release()
                                    completer.set(Result(ErrorReason.CONNECT_FAILED, 0, 0))
                                }, parser@{ data: ByteArray ->
                                    Log.v(
                                        tag,
                                        "Incoming Data! ${data.asUByteArray().contentToString()} ($state)"
                                    )
                                    data.toUByteArray().map { byte -> byte.toInt() }
                                        .forEachIndexed { index, byte ->
                                            if (byte == 0xE0 && state == State.CONNECTED) {
                                                Log.d(tag, "Got E0")
                                                if (!sharedPreferences.contains("secret")) {
                                                    sharedPreferences.edit()
                                                        .putLong("secret", Random.nextLong())
                                                        .commit()
                                                }
                                                val authKey =
                                                    sharedPreferences.getLong("secret", 0).toULong()
                                                val authUBytes = UByteArray(4)
                                                for (i in 0..3) {
                                                    authUBytes[i] = authKey.shr(i * 8).toUByte()
                                                }
                                                state = State.PAIRING
                                                binder.sendData(authUBytes.toByteArray())
                                                return@parser
                                            } else if (byte == 0xFD && state == State.PAIRING) {
                                                Log.d(tag, "Got FD")
                                                state = State.PENDING_CHECK
                                                keyId = data.toUByteArray()[index + 1].toInt()
                                                binder.sendData("c".toByteArray(Charsets.ISO_8859_1))
                                                return@parser
                                            } else if (byte == 0xCE && state == State.PAIRING) {
                                                Log.d(tag, "Got CE")
                                                // Device was created (that's wrong) but we'll continue anyway
                                                state = State.PENDING_CHECK
                                                keyId = data.toUByteArray()[index + 1].toInt()
                                                binder.sendData("c".toByteArray(Charsets.ISO_8859_1))
                                                return@parser
                                            } else if (byte == 0xED && state == State.PAIRING) {
                                                Log.d(tag, "Got ED")
                                                state = State.DISCONNECTED
                                                lock.release()
                                                completer.set(Result(ErrorReason.SLOTS_FULL, 0, 0))
                                                return@parser
                                                // All slots used, show error
                                            } else if (state == State.PENDING_CHECK) {
                                                Log.d(tag, "Got check response $byte")
                                                state = State.IDLE
                                                lock.release()
                                                completer.set(Result(null, keyId, byte))
                                                return@parser
                                            }
                                        }
                                })
                                if (!binder.connectDevice(device)) {
                                    lock.release()
                                    completer.set(Result(ErrorReason.BT_OFF, 0, 0))
                                }
                            }
                        },
                        Context.BIND_AUTO_CREATE
                    )
                }
            }
        }
    }

    companion object {
        const val tag = "KeyHolderCheckReceiver"
        private val lock = Semaphore(1) // We have to use a binary sem instead of a lock because
                                // its the only non-reentrant native locking construct
    }
}

data class Result(val errorReason: ErrorReason?, val keyId: Int, val keysBitmask: Int)

enum class ErrorReason {
    BT_OFF,
    CONNECT_FAILED,
    UNPAIRED,
    SLOTS_FULL,
    PERMISSIONS_MISSING
}