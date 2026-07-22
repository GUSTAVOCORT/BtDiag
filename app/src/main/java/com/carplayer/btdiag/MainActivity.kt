package com.carplayer.btdiag

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.carplayer.btdiag.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val live = StringBuilder()
    @Volatile private var corriendo = false

    private val avrcpReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            live.append("\n[AVRCP] ").append(i.action).append('\n')
            i.extras?.keySet()?.forEach { k ->
                live.append("   ").append(k).append(" = ").append(i.extras?.get(k)).append('\n')
            }
            runOnUiThread { b.txtLive.text = live.toString() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.txtOut.text = "Listo.\n\n" +
            "1. Da permiso de microfono.\n" +
            "2. Toca PERMISO y activa BT Diag en acceso a notificaciones.\n" +
            "3. Pone musica por Bluetooth y que este SONANDO.\n" +
            "4. Recien ahi toca REINTENTAR."

        pedirPermisos()

        b.btnRun.setOnClickListener { correr() }
        b.btnPerm.setOnClickListener {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Throwable) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (ig: Throwable) {}
                toast("Busca manualmente Acceso a notificaciones")
            }
        }
        b.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("btdiag", texto()))
            toast("Copiado")
        }
        b.btnSave.setOnClickListener { guardar() }
        b.btnPlay.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY/PAUSA") }
        b.btnNext.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "NEXT") }

        registrarAvrcp()
        // Ya NO se corre solo al arrancar: primero los permisos y la musica.
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
            live.append("Receiver AVRCP registrado.\n")
        } catch (e: Throwable) {
            live.append("No se registro receiver: ").append(e.message).append('\n')
        }
        b.txtLive.text = live.toString()
    }

    /** Manda la tecla y verifica si la musica cambio de estado. */
    private fun mediaKey(code: Int, nombre: String) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val antes = am.isMusicActive
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            b.txtLive.postDelayed({
                val despues = am.isMusicActive
                live.append("\n[TECLA ").append(nombre).append("] musicActive antes=")
                    .append(antes).append(" despues=").append(despues).append('\n')
                b.txtLive.text = live.toString()
            }, 1200)
            toast("Tecla enviada, mira si el celu reacciona")
        } catch (e: Throwable) {
            toast("Fallo: " + e.message)
        }
    }

    private fun correr() {
        if (corriendo) { toast("Ya se esta ejecutando, espera"); return }
        corriendo = true
        b.txtOut.text = "Ejecutando..."
        Thread {
            val p = Probe(applicationContext) { parcial ->
                runOnUiThread { b.txtOut.text = parcial }
            }
            try { p.run() } catch (e: Throwable) {
                runOnUiThread { b.txtOut.append("\nERROR GENERAL: " + e.javaClass.name + " / " + e.message) }
            } finally { corriendo = false }
        }.start()
    }

    private fun texto() = b.txtOut.text.toString() + "\n\n--- PANEL EN VIVO ---\n" + live

    private fun guardar() {
        val destinos = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "bt_diag.txt"),
            File(Environment.getExternalStorageDirectory(), "bt_diag.txt"),
            File(getExternalFilesDir(null), "bt_diag.txt")
        )
        for (f in destinos) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(texto())
                toast("Guardado en " + f.absolutePath)
                return
            } catch (e: Throwable) { /* siguiente destino */ }
        }
        toast("No se pudo guardar en ningun lado, usa COPIAR")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        try { unregisterReceiver(avrcpReceiver) } catch (ig: Throwable) {}
        super.onDestroy()
    }
}
