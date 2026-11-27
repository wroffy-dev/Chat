package app.vaultchat.ui.navigation

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.abs

/**
 * Quick hide (owner 2026-11-26: "phone ulta rakho … to chats band hokar notes screen aa jaye"): calls [onFaceDown] once
 * when the phone lies screen-down for half a second. Listens only while the app is in front and [enabled].
 */
@Composable
fun FaceDownWatcher(enabled: Boolean, onFaceDown: () -> Unit) {
    val context = LocalContext.current
    val fire by rememberUpdatedState(onFaceDown)
    LifecycleResumeEffect(enabled) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private var downSince = 0L
            private var fired = false

            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                // Screen-down and nearly flat: gravity points out of the screen's back towards the table.
                val faceDown = z < -8f && abs(x) < 3f && abs(y) < 3f
                val now = SystemClock.elapsedRealtime()
                if (!faceDown) {
                    downSince = 0L
                    fired = false
                    return
                }
                if (downSince == 0L) downSince = now
                if (!fired && now - downSince >= 500L) {
                    fired = true
                    fire()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && sensors != null && sensor != null) {
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onPauseOrDispose { sensors?.unregisterListener(listener) }
    }
}
