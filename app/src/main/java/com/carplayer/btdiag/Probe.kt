package com.carplayer.btdiag

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.media.session.MediaSessionManager
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Bateria de pruebas para determinar como esta implementado el Bluetooth
 * en esta head unit. Todo va envuelto en try/catch porque en firmwares
 * chinos cualquier API puede no existir o lanzar SecurityException.
 */
object Probe {

    // Constantes ocultas de BluetoothProfile (no estan en el SDK publico)
    private const val A2DP_SINK = 11
    private const val AVRCP_CONTROLLER = 12
    private const val HEADSET_CLIENT = 16

    private val sb = StringBuilder()

    private fun t(title: String) {
        sb.append("\n=== ").append(title).append(" ===\n")
    }

    private fun l(key: String, value: Any?) {
        sb.append(key).append(": ").append(value).append('\n')
    }

    private fun safe(label: String, block: () -> String) {
        try {
            sb.append(label).append(": ").append(block()).append('\n')
        } catch (e: Throwable) {
            sb.append(label).append(": ERROR -> ")
                .append(e.javaClass.simpleName).append(" / ").append(e.message).append('\n')
        }
    }

    fun run(ctx: Context): String {
        sb.setLength(0)
        sb.append("BT DIAG v1 - ").append(System.currentTimeMillis()).append('\n')

        system()
        props()
        bluetooth()
        profiles(ctx)
        audioState(ctx)
        mediaSessions(ctx)
        visualizer()
        equalizer()
        packages(ctx)
        verdict()

        return sb.toString()
    }

    // ------------------------------------------------------------------ 1
    private fun system() {
        t("SISTEMA")
        l("Build.VERSION.RELEASE (lo que dice)", Build.VERSION.RELEASE)
        l("Build.VERSION.SDK_INT (lo que es)", Build.VERSION.SDK_INT)
        l("MANUFACTURER", Build.MANUFACTURER)
        l("MODEL", Build.MODEL)
        l("BOARD", Build.BOARD)
        l("HARDWARE", Build.HARDWARE)
        l("DEVICE", Build.DEVICE)
        l("FINGERPRINT", Build.FINGERPRINT)
        l("ABIs", Build.SUPPORTED_ABIS.joinToString())
    }

    // ------------------------------------------------------------------ 2
    private fun props() {
        t("PROPIEDADES getprop")
        val keys = listOf(
            "ro.build.version.sdk", "ro.product.board", "ro.board.platform",
            "ro.bluetooth.hfp.ver", "ro.bt.bdaddr_path",
            "persist.service.bdroid.bdaddr", "bluetooth.enable_timeout_ms",
            "ro.hardware.bluetooth", "ro.boot.btmacaddr", "persist.sys.bt.name"
        )
        for (k in keys) safe(k) { getProp(k).ifEmpty { "(vacio)" } }
    }

    private fun getProp(key: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        val r = BufferedReader(InputStreamReader(p.inputStream))
        val out = r.readLine() ?: ""
        r.close()
        p.destroy()
        return out.trim()
    }

    // ------------------------------------------------------------------ 3
    @Suppress("MissingPermission", "DEPRECATION")
    private fun bluetooth() {
        t("ADAPTADOR BLUETOOTH")
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) {
            sb.append("NO HAY BluetoothAdapter -> Android no maneja el BT de este equipo.\n")
            sb.append("SENAL FUERTE de modulo externo por UART.\n")
            return
        }
        safe("enabled") { ad.isEnabled.toString() }
        safe("name") { ad.name ?: "null" }
        safe("address") { ad.address ?: "null" }
        safe("state") { ad.state.toString() }
        safe("dispositivos vinculados") {
            val b = ad.bondedDevices
            if (b.isNullOrEmpty()) "(ninguno)"
            else b.joinToString(" | ") { d: BluetoothDevice -> d.name + "/" + d.address + "/tipo=" + d.type }
        }
    }

    // ------------------------------------------------------------------ 4
    private fun profiles(ctx: Context) {
        t("PERFILES BLUETOOTH DISPONIBLES")
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { sb.append("(sin adaptador, se omite)\n"); return }
        val toTest = listOf(
            BluetoothProfile.A2DP to "A2DP (source)",
            A2DP_SINK to "A2DP_SINK  <-- clave: el equipo recibe audio",
            AVRCP_CONTROLLER to "AVRCP_CONTROLLER  <-- clave: metadata y controles",
            HEADSET_CLIENT to "HEADSET_CLIENT (manos libres)"
        )
        for ((id, label) in toTest) {
            try {
                val ok = ad.getProfileProxy(ctx, ProxyCollector, id)
                sb.append(label).append(" (id=").append(id).append("): getProfileProxy=")
                    .append(ok).append('\n')
            } catch (e: Throwable) {
                sb.append(label).append(" (id=").append(id).append("): ERROR ")
                    .append(e.javaClass.simpleName).append(' ').append(e.message).append('\n')
            }
        }
        sb.append("(los estados llegan asincronos, aparecen al final del reporte)\n")
    }

    /** Recoge los proxies que devuelvan los perfiles ocultos. */
    object ProxyCollector : BluetoothProfile.ServiceListener {
        val log = StringBuilder()
        @Suppress("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            try {
                val devs = proxy?.connectedDevices
                log.append("perfil ").append(profile).append(" CONECTADO, clase=")
                    .append(proxy?.javaClass?.name).append(", dispositivos=")
                    .append(devs?.joinToString { it.address } ?: "null").append('\n')
            } catch (e: Throwable) {
                log.append("perfil ").append(profile).append(" conectado pero fallo listar: ")
                    .append(e.message).append('\n')
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            log.append("perfil ").append(profile).append(" desconectado\n")
        }
    }

    // ------------------------------------------------------------------ 5
    private fun audioState(ctx: Context) {
        t("ESTADO DE AUDIO")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        safe("isMusicActive  <-- true con musica BT sonando = pasa por Android") {
            am.isMusicActive.toString()
        }
        @Suppress("DEPRECATION")
        safe("isBluetoothA2dpOn") { am.isBluetoothA2dpOn.toString() }
        safe("volumen STREAM_MUSIC") {
            am.getStreamVolume(AudioManager.STREAM_MUSIC).toString() + "/" +
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
        safe("mode") { am.mode.toString() }
    }

    // ------------------------------------------------------------------ 6
    private fun mediaSessions(ctx: Context) {
        t("MEDIA SESSIONS ACTIVAS")
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: ""
        val mine = enabled.contains(ctx.packageName)
        l("permiso NotificationListener concedido", mine)
        l("listeners del sistema", if (enabled.isEmpty()) "(ninguno)" else enabled)

        if (!mine) {
            sb.append("-> Toca el boton PERMISO, activa 'BT Diag' y reintenta.\n")
            return
        }
        safe("sesiones") {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(ctx, NotifListener::class.java)
            val list = msm.getActiveSessions(comp)
            if (list.isEmpty()) "(cero sesiones - pone musica desde el celu y reintenta)"
            else list.joinToString(" || ") { c ->
                val md = c.metadata
                val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                "pkg=" + c.packageName + " estado=" + c.playbackState?.state +
                    " acciones=" + c.playbackState?.actions +
                    " titulo=" + title + " artista=" + artist
            }
        }
    }

    // ------------------------------------------------------------------ 7
    private fun visualizer() {
        t("VISUALIZER SOBRE SESION GLOBAL (id=0)")
        var v: Visualizer? = null
        try {
            v = Visualizer(0)
            l("construido", "OK")
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            l("captureSize", v.captureSize)
            v.enabled = true
            l("enabled", v.enabled)

            val wave = ByteArray(v.captureSize)
            var maxDelta = 0
            repeat(12) {
                v.getWaveForm(wave)
                var mn = 127
                var mx = -128
                for (b in wave) {
                    val x = b.toInt()
                    if (x < mn) mn = x
                    if (x > mx) mx = x
                }
                val d = mx - mn
                if (d > maxDelta) maxDelta = d
                Thread.sleep(60)
            }
            l("amplitud maxima observada", maxDelta)
            sb.append(
                if (maxDelta > 4)
                    ">>> HAY SENAL. Visualizador y ecualizador VAN A FUNCIONAR con audio BT.\n"
                else
                    ">>> Silencio. O no habia musica sonando, o el audio BT NO pasa por el mixer de Android.\n"
            )
            v.enabled = false
        } catch (e: Throwable) {
            sb.append("FALLO: ").append(e.javaClass.simpleName).append(" / ").append(e.message).append('\n')
            sb.append(">>> Sin visualizador. Verifica el permiso de microfono.\n")
        } finally {
            try { v?.release() } catch (ignored: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ 8
    private fun equalizer() {
        t("EQUALIZER SOBRE SESION GLOBAL (id=0)")
        var eq: Equalizer? = null
        try {
            eq = Equalizer(0, 0)
            eq.enabled = true
            l("bandas", eq.numberOfBands)
            l("rango milibelios", eq.bandLevelRange.joinToString())
            l("presets", eq.numberOfPresets)
            sb.append(">>> Ecualizador global disponible.\n")
            eq.enabled = false
        } catch (e: Throwable) {
            sb.append("FALLO: ").append(e.javaClass.simpleName).append(" / ").append(e.message).append('\n')
        } finally {
            try { eq?.release() } catch (ignored: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ 9
    private fun packages(ctx: Context) {
        t("APPS DE BLUETOOTH INSTALADAS")
        safe("paquetes") {
            val pm = ctx.packageManager
            val found = pm.getInstalledPackages(0)
                .map { it.packageName }
                .filter {
                    it.contains("bluetooth", true) || it.contains(".bt.", true) ||
                        it.endsWith(".bt") || it.contains("btmusic", true) ||
                        it.contains("a2dp", true) || it.contains("avrcp", true)
                }
                .sorted()
            if (found.isEmpty()) "(ninguno - muy mala senal)"
            else found.joinToString("\n  ", "\n  ")
        }
        sb.append("-> Si aparece 'com.android.bluetooth' el stack de Android esta presente.\n")
        sb.append("-> Si solo hay apps del fabricante, probablemente sea un modulo externo.\n")
    }

    // ------------------------------------------------------------------ 10
    private fun verdict() {
        t("PROXIMOS PASOS")
        sb.append("1. Empareja el celular y pone musica ANTES de tocar Reintentar.\n")
        sb.append("2. Con la musica sonando, baja el volumen multimedia de Android:\n")
        sb.append("   si el volumen NO cambia -> el audio no pasa por Android.\n")
        sb.append("3. Guarda este reporte y pasamelo completo.\n")
        sb.append(ProxyCollector.log)
    }
}
