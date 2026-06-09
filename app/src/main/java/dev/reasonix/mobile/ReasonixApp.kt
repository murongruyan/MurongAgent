package dev.reasonix.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.net.HttpURLConnection
import java.net.URL

@HiltAndroidApp
class ReasonixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // #region debug-point B:reasonix-app-oncreate
        Thread {
            try {
                val connection =
                    (URL("http://192.168.2.3:7777/event").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(
                        """{"sessionId":"app-launch-crash","runId":"pre-fix","hypothesisId":"B","location":"ReasonixApp:onCreate","msg":"[DEBUG] ReasonixApp onCreate entered","data":{"process":"com.murong.agent"},"ts":${System.currentTimeMillis()}}"""
                    )
                }
                connection.inputStream.close()
                connection.disconnect()
            } catch (_: Exception) {
            }
        }.start()
        // #endregion
    }
}
