package com.carplayer.btdiag

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.media.AudioManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.carplayer.btdiag.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val live = StringBuilder()

    /** Escucha los broadcasts legacy de AVRCP Controller (Android 6/7). */
    private val avrcpReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            live.append("\n[AVRCP] ").append(i.action).append('\n')
            i.extras?.keySet()?.forEach { k ->
                live.append("   ").append(k).append(" = ")
                    .append(i.extras?.get(k)).append('\n')
            }
            b.txtLive.text = live.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        pedirPermisos()

        b.btnRun.setOnClickListener { correr() }
        b.btnPerm.setOnClickListener {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Throwable) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
                toast("Busca manualmente Acceso a notificaciones")
            }
        }
        b.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("btdiag", b.txtOut.text))
            toast("Copiado al portapapeles")
        }
        b.btnSave.setOnClickListener { guardar() }

        // Prueba de control remoto: manda un NEXT por tecla multimedia.
        b.btnNext.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        b.btnPlay.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }

        registrarAvrcp()
        correr()
    }

    private fun pedirPermisos() {
        val faltan = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) faltan.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= 31 &&
            ActivityCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT")
            != PackageManager.PERMISSION_GRANTED
        ) faltan.add("android.permission.BLUETOOTH_CONNECT")

        if (faltan.isNotEmpty()) ActivityCompat.requestPermissions(this, faltan.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        correr()
    }

    private fun registrarAvrcp() {
        val f = IntentFilter().apply {
            addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT")
            addAction("android.bluetooth.avrcp-controller.profile.action.BROWSE_CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(avrcpReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(avrcpReceiver, f)
            }
            live.append("Receiver AVRCP registrado. Cambia de cancion en el celu.\n")
        } catch (e: Throwable) {
            live.append("No se pudo registrar receiver AVRCP: ").append(e.message).append('\n')
        }
        b.txtLive.text = live.toString()
    }

    private fun mediaKey(code: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            toast("Tecla enviada. Mira si el celu reacciona.")
        } catch (e: Throwable) {
            toast("Fallo: " + e.message)
        }
    }

    private fun correr() {
        b.txtOut.text = "Ejecutando pruebas..."
        Thread {
            val out = try { Probe.run(this) } catch (e: Throwable) {
                "ERROR GENERAL: " + e.javaClass.name + " / " + e.message
            }
            runOnUiThread { b.txtOut.text = out }
        }.start()
    }

    private fun guardar() {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "bt_diag.txt")
            f.writeText(b.txtOut.text.toString() + "\n\n--- LIVE ---\n" + live)
            toast("Guardado en " + f.absolutePath)
        } catch (e: Throwable) {
            try {
                val f = File(getExternalFilesDir(null), "bt_diag.txt")
                f.writeText(b.txtOut.text.toString() + "\n\n--- LIVE ---\n" + live)
                toast("Guardado en " + f.absolutePath)
            } catch (e2: Throwable) {
                toast("No se pudo guardar: " + e2.message)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        try { unregisterReceiver(avrcpReceiver) } catch (ignored: Throwable) {}
        super.onDestroy()
    }
}
