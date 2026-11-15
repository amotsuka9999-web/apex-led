package app.apexled.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The runtime permissions the LED boards need on this Android version. */
object LedPermissions {

    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean =
        required().all { context.checkPermission(it, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED }
}
