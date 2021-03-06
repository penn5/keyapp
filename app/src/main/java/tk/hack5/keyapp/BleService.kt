package tk.hack5.keyapp

import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.min

class BleService : Service() {
    private lateinit var mGattCallback: BluetoothGattCallback
    private var mBluetoothGatt: BluetoothGatt? = null
    private var onConnect: (() -> Unit)? = null
    private var onDisconnect: (() -> Unit)? = null
    private var onRx: ((data: ByteArray) -> Unit)? = null
    private var dataSerialCharacteristic: BluetoothGattCharacteristic? = null
    @Suppress("PrivatePropertyName")
    private var ATSerialCharacteristic: BluetoothGattCharacteristic? = null

    private lateinit var mHandler: Handler
    private var isConnected = false

    private var onWritten: ((success: Boolean) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder? {
        Log.d(tag, "Binding")
        thread(start = true) {
            Looper.prepare()
            mHandler = Handler()
            Looper.loop()
        }
        return if (!isConnected)
            BleBinder()
        else
            null
    }

    override fun onDestroy() {
        Log.d(tag, "Destroying")
        mBluetoothGatt?.disconnect()
        mBluetoothGatt?.close()
        super.onDestroy()
    }

    private fun connectDevice(device: BluetoothDevice): Boolean {
        mGattCallback = GattCallback()
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback) ?: return false
        Log.d(tag, "Connecting device! It is $mBluetoothGatt")
        return true
    }

    private fun registerCallbacks(
        onConnect: () -> Unit,
        onDisconnect: () -> Unit,
        onRx: (data: ByteArray) -> Unit
    ) {
        this.onConnect = onConnect
        this.onDisconnect = onDisconnect
        this.onRx = onRx
    }

    private fun initializeAT() {
        if (!isConnected) {
            ATSerialCharacteristic!!.value = "AT+CURRUART=115200".toByteArray(Charsets.ISO_8859_1)
            mBluetoothGatt!!.writeCharacteristic(ATSerialCharacteristic)
        }
        mBluetoothGatt!!.setCharacteristicNotification(dataSerialCharacteristic, true)
        val wasConnected = isConnected
        isConnected = true
        onConnect?.invoke()
        if (wasConnected) {
            sendData(byteArrayOf('r'.toByte()), {})
        }
    }

    private fun sendData(
        data: ByteArray,
        onComplete: (success: Boolean) -> Unit,
        _callbackFailed: Boolean? = null
    ) {
        if (!isConnected) {
            onComplete(false)
            return
        }
        if (_callbackFailed != null) {
            onComplete(!_callbackFailed)
            return
        }
        mHandler.post {
            onWritten = { success ->
                sendData(
                    data.sliceArray(
                        IntRange(
                            MAX_TRANSMISSION_UNIT,
                            data.lastIndex
                        )
                    ), onComplete, success
                )
            }
            dataSerialCharacteristic!!.value =
                data.sliceArray(IntRange(0, min(MAX_TRANSMISSION_UNIT, data.lastIndex)))
            mBluetoothGatt!!.writeCharacteristic(dataSerialCharacteristic)
            if (data.isEmpty()) {
                onComplete(true)
            }
        }
    }

    inner class BleBinder : Binder() {
        fun connectDevice(device: BluetoothDevice): Boolean {
            return this@BleService.connectDevice(device)
        }

        fun registerCallbacks(
            onConnect: () -> Unit,
            onDisconnect: () -> Unit,
            onRx: (data: ByteArray) -> Unit
        ) {
            this@BleService.registerCallbacks(onConnect, onDisconnect, onRx)
        }

        fun sendData(data: ByteArray, onComplete: (success: Boolean) -> Unit = {}) {
            this@BleService.sendData(data, onComplete)
        }
    }

    inner class GattCallback : BluetoothGattCallback() {
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (this != this@BleService.mGattCallback) return // We are from an old device
            Log.d(
                tag,
                "Received data on ${characteristic.uuid}: ${characteristic.value?.contentToString()}"
            )
            if (characteristic.uuid == DATA_SERIAL_UUID) {
                if (characteristic.value?.contentEquals(DISCONNECT_STR) == true) {
                    isConnected = false
                    onDisconnect?.invoke()
                } else
                    onRx?.invoke(characteristic.value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (this != this@BleService.mGattCallback) return // We are from an old device
            Log.d(
                tag,
                "Wrote data on ${characteristic.uuid}: ${characteristic.value?.contentToString()}"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Failed to write data! $status")
            }
            onWritten?.let {
                onWritten = null
                it.invoke(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this != this@BleService.mGattCallback) return // We are from an old device
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && isConnected) {
                isConnected = false
                onDisconnect?.invoke()
                gatt.disconnect() // Otherwise the callback remains registered and all events are duplicated
            } else if (status == 0x85) { // https://android.googlesource.com/platform/external/bluetooth/bluedroid/+/adc9f28ad418356cb81640059b59eee4d862e6b4/stack/include/gatt_api.h#54
                Log.e(tag, "Connection Failed! $status:$newState (${gatt.device.address})")
                onDisconnect?.invoke()
            } else if (status == 0x80) {
                // GATT_NO_RESOURCES
                Log.e(tag, "Device has no resources - someone else is already using it!")
                onDisconnect?.invoke()
            } else {
                Log.e(tag, "unknown status $status:$newState")
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (this != this@BleService.mGattCallback) return // We are from an old device
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (this != this@BleService.mGattCallback) return // We are from an old device
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this != this@BleService.mGattCallback) return // We are from an old device
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "onServicesDiscovered failed with $status")
                return
            }
            Log.v(
                tag,
                "Discovered services ${gatt.services.map { it.uuid.toString() + ":" + it.type.toString() + ":" + it.characteristics.map { service -> service.uuid.toString() } }}"
            )
            val service = gatt.getService(SERVICE_UUID) ?: {
                gatt.disconnect()
                Log.e(tag, "UUID for service not found!")
                Log.e(
                    tag,
                    "Discovered services ${gatt.services.map { it.uuid.toString() + ":" + it.type.toString() + ":" + it.characteristics.map { service -> service.uuid.toString() } }}"
                )
                null
            }() ?: return
            dataSerialCharacteristic = service.getCharacteristic(DATA_SERIAL_UUID) ?: {
                gatt.disconnect()
                Log.e(tag, "UUID for serial char not found!")
                Log.e(
                    tag,
                    "Discovered services ${gatt.services.map { it.uuid.toString() + ":" + it.type.toString() + ":" + it.characteristics.map { service -> service.uuid.toString() } }}"
                )
                null
            }() ?: return
            ATSerialCharacteristic = service.getCharacteristic(AT_SERIAL_UUID) ?: {
                gatt.disconnect()
                Log.e(tag, "UUID for serial char not found!")
                Log.e(
                    tag,
                    "Discovered services ${gatt.services.map { it.uuid.toString() + ":" + it.type.toString() + ":" + it.characteristics.map { service -> service.uuid.toString() } }}"
                )
                null
            }() ?: return
            Log.d(tag, "Successfully got the service & chars")
            initializeAT()
        }
    }

    companion object {
        private const val tag = "KeyHolderBleService"
        val SERVICE_UUID = UUID.fromString("0000dfb0-0000-1000-8000-00805f9b34fb")!!
        private val DATA_SERIAL_UUID = UUID.fromString("0000dfb1-0000-1000-8000-00805f9b34fb")!!
        private val AT_SERIAL_UUID = UUID.fromString("0000dfb2-0000-1000-8000-00805f9b34fb")!!
        private const val MAX_TRANSMISSION_UNIT = 17 // Bluno official example app does this
        // ASCII for `Restarting, goodbye!`
        private val DISCONNECT_STR = byteArrayOf(82, 101, 115, 116, 97, 114, 116, 105, 110, 103, 44, 32, 103, 111, 111, 100, 98, 121, 101, 33)
    }
}
