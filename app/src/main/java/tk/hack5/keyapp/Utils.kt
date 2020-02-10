package tk.hack5.keyapp

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object Utils {
    @ExperimentalUnsignedTypes
    fun update(context: Activity, callback: (Result) -> Unit, failure: () -> Unit, openSetup: Boolean = true) {
        val ret = Checker(context.applicationContext).startWork()
        ret.addListener({
            val result = ret.get()
            when (result.errorReason) {
                ErrorReason.CONNECT_FAILED -> {
                    Toast.makeText(context, R.string.cant_connect, Toast.LENGTH_SHORT).show()
                    failure()
                    return@addListener
                }
                ErrorReason.BT_OFF -> {
                    Toast.makeText(context, R.string.bt_off, Toast.LENGTH_SHORT).show()
                    failure()
                    return@addListener
                }
                ErrorReason.SLOTS_FULL -> {
                    if (openSetup)
                        context.startActivity(Intent(context, SetupActivity::class.java)
                            .apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                    Toast.makeText(context, R.string.slots_full, Toast.LENGTH_LONG).show()
                    failure()
                    return@addListener
                }
                ErrorReason.UNPAIRED -> {
                    if (openSetup)
                        context.startActivity(Intent(context, SetupActivity::class.java)
                            .apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                    Toast.makeText(context, R.string.unpaired, Toast.LENGTH_LONG).show()
                    failure()
                    return@addListener
                }
                ErrorReason.PERMISSIONS_MISSING -> {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    ) {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.permission_request)
                            .setMessage(R.string.permission_request_location)
                            .setPositiveButton(R.string.continue_button) { dialog: DialogInterface, _: Int ->
                                dialog.dismiss()
                                context.startActivity(Intent().apply {
                                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                    data = Uri.fromParts("package", context.packageName, null)
                                })
                            }
                            .setNegativeButton(R.string.exit_button) { dialog: DialogInterface, _: Int ->
                                dialog.dismiss()
                                context.finish()
                            }
                            .setCancelable(false)
                            .show()
                            .setCanceledOnTouchOutside(false)
                    } else {
                        ActivityCompat.requestPermissions(
                            context,
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                            GET_PERMISSION_REQUEST
                        )
                    }
                    return@addListener
                }
            }
            callback(result)
        }, ContextCompat.getMainExecutor(context)::execute)
    }

    const val ENABLE_BT_REQUEST = 1
    const val GET_PERMISSION_REQUEST = 2
    const val SHARED_PREFS_NAME = "prefs"
    const val SHARED_PREFS_MAC = "mac"

}