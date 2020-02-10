package tk.hack5.keyapp

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import java.util.*


class ScanCallbackSub(
    private val context: Context, private val adapter: ArrayAdapter<ScanResult>,
    private val serviceMac: UUID?, private val deviceName: String?
) : ScanCallback() {
    private val shownResults =
        mutableListOf<ScanResult>() // We have to use a list because it's ordered
    private val shownKeys = mutableListOf<String>()

    override fun onScanFailed(errorCode: Int) {
        AlertDialog.Builder(context).setCancelable(false)
            .setPositiveButton(R.string.okay) { dialog, _ -> dialog.cancel() }
            .setTitle(R.string.scan_fail)
            .setMessage(context.getString(R.string.scan_fail_message).format(errorCode))
    }

    override fun onScanResult(callbackType: Int, result: ScanResult) {
        if (result.device.name.isNullOrBlank()) return
        Log.d(
            tag,
            result.scanRecord?.serviceUuids?.map { it?.uuid }?.joinToString() ?: "uuids null"
        )
        if (serviceMac != null && result.scanRecord?.serviceUuids?.none { it?.uuid == serviceMac } != false) return // Check the service as ScanFilters is broken on some devices
        if (deviceName != null && result.device.name != deviceName) return
        synchronized(this) {
            when (callbackType) {
                ScanSettings.CALLBACK_TYPE_MATCH_LOST -> {
                    adapter.remove(result)
                    val position = shownKeys.indexOf(result.device.address)
                    if (position >= 0) {
                        shownResults.removeAt(position)
                        shownKeys.removeAt(position)
                    }
                }
                ScanSettings.CALLBACK_TYPE_FIRST_MATCH -> {
                    adapter.add(result)
                    shownResults.add(result)
                    shownKeys.add(result.device.address)
                }
                ScanSettings.CALLBACK_TYPE_ALL_MATCHES -> {
                    val position = shownKeys.indexOf(result.device.address)
                    if (position == -1) {
                        adapter.add(result)
                        shownResults.add(result)
                        shownKeys.add(result.device.address)
                    } else {
                        val old = shownResults[position]
                        adapter.remove(old)
                        adapter.insert(result, position)
                        shownResults[position] = result
                    }
                }
                else -> Log.e(tag, "WTF! Callback type is not in documentation")
            }
            adapter.notifyDataSetChanged()
        }
    }

    companion object {
        private const val tag = "KeyHolderScanCBSub"
    }
}