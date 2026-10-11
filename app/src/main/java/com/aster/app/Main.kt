package com.aster.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(this).apply {
            text = "Aster\n\nSay \"Hey Aster\" to open your default assistant. " +
                "Set ChatGPT as the default digital assistant app in Android settings."
            textSize = 18f
        }
        val start = Button(this).apply {
            text = "Start listening"
            setOnClickListener { startAster() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, AsterService::class.java))
            }
        }
        layout.addView(info)
        layout.addView(start)
        layout.addView(stop)
        setContentView(layout)
    }

    private fun startAster() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\", then tap Start again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startForegroundService(Intent(this, AsterService::class.java))
        Toast.makeText(this, "Aster is starting. Say \"Hey Aster\" in a minute.", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startAster()
        }
    }
}

class AsterService : Service(), RecognitionListener {

    companion object {
        // Any of these heard in speech triggers the assistant. Add more here if needed.
        private val KEYWORDS = listOf("aster", "astor", "easter", "a star", "astro")
        // How long to stop listening so ChatGPT can use the microphone.
        private const val PAUSE_MS = 60000L
    }

    private var model: Model? = null
    private var speech: SpeechService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "aster"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Aster listening", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Aster is listening")
            .setContentText("Say \"Hey Aster\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        if (model == null) {
            StorageService.unpack(
                this, "model-en-us", "model",
                { m: Model ->
                    model = m
                    listen()
                },
                { e: IOException -> Log.e("Aster", "Model failed to load", e) }
            )
        } else if (speech == null && !paused) {
            listen()
        }
        return START_STICKY
    }

    private fun listen() {
        val m = model ?: return
        try {
            val recognizer = Recognizer(m, 16000.0f)
            speech = SpeechService(recognizer, 16000.0f)
            speech?.startListening(this)
        } catch (e: IOException) {
            Log.e("Aster", "Could not start listening", e)
        }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun heardText(json: String?): String {
        if (json == null) return ""
        return try {
            val o = JSONObject(json)
            o.optString("text").ifEmpty { o.optString("partial") }
        } catch (e: Exception) {
            ""
        }
    }

    private fun check(json: String?, final: Boolean) {
        if (paused) return
        val text = heardText(json).lowercase()
        if (final && text.isNotBlank()) toast("Heard: $text")
        if (KEYWORDS.any { text.contains(it) }) trigger()
    }

    private fun trigger() {
        paused = true
        speech?.stop()
        speech?.shutdown()
        speech = null
        toast("Opening assistant...")
        try {
            startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Could not open: ${e.message}")
        }
        handler.postDelayed({
            paused = false
            listen()
        }, PAUSE_MS)
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, false)
    override fun onResult(hypothesis: String?) = check(hypothesis, true)
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Log.e("Aster", "Recognizer error", exception)
    }
    override fun onTimeout() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech?.shutdown()
        model?.close()
        super.onDestroy()
    }
}
