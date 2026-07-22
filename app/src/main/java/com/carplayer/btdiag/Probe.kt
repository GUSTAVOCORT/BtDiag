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
 * v2: instancia nueva por corrida (sin estado compartido) y publicacion
 * progresiva, asi si una seccion revienta se ve exactamente donde.
 */
class Probe(private val ctx: Context, private val onProgress: (String) -> Unit) {

    private val A2DP_SINK = 11
    private val AVRCP_CONTROLLER = 12
    private val HEADSET_CLIENT = 16

    private val sb = StringBuilder()
    private val proxyLog = StringBuilder()
    private var avrcpProxy: BluetoothProfile? = null
    private var sinkProxy: BluetoothProfile? = null

    private fun t(title: String) { sb.append("\n=== ").append(title).append(" ===\n") }
    private fun l(k: String, v: Any?) { sb.append(k).append(": ").append(v).append('\n') }

    private fun safe(label: String, block: () -> String) {
        try {
            sb.append(label).append(": ").append(block()).append('\n')
        } catch (e: Throwable) {
            sb.append(label).append(": ERROR -> ").append(e.javaClass.simpleName)
                .append(" / ").append(e.message).append('\n')
        }
    }

    /** Cada seccion aislada: si explota, se anota y se sigue con la siguiente. */
    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            sb.append("\n!!! SECCION '").append(name).append("' FALLO: ")
                .append(e.javaClass.name).append(" / ").append(e.message).append('\n')
        }
        onProgress(sb.toString())
    }

    fun run(): String {
        sb.append("BT DIAG v3\n")
        step("sistema") { system() }
        step("props") { props() }
        step("adaptador") { bluetooth() }
        step("perfiles") { profiles() }
        step("espera") { Thread.sleep(1500) }   // deja llegar los callbacks asincronos
        step("audio") { audioState() }
        step("sessions") { mediaSessions() }
        step("visualizer") { visualizer() }
        step("equalizer") { equalizer() }
        step("paquetes") { packages() }
        step("api_proxy") { apiProxy() }
        step("receptores") { receptores() }
        step("appFabricante") { appFabricante() }
        step("cierre") { verdict() }
        return sb.toString()
    }

    private fun system() {
        t("SISTEMA")
        l("RELEASE (lo que dice)", Build.VERSION.RELEASE)
        l("SDK_INT (lo que es)", Build.VERSION.SDK_INT)
        l("MODEL", Build.MODEL)
        l("HARDWARE", Build.HARDWARE)
    }

    private fun props() {
        t("PROPIEDADES getprop")
        val keys = listOf(
            "ro.bt.bdaddr_path", "persist.service.bdroid.bdaddr",
            "ro.hardware.bluetooth", "ro.board.platform"
        )
        for (k in keys) safe(k) { getProp(k).ifEmpty { "(vacio)" } }
    }

    private fun getProp(key: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        val r = BufferedReader(InputStreamReader(p.inputStream))
        val out = r.readLine() ?: ""
        r.close(); p.destroy()
        return out.trim()
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun bluetooth() {
        t("ADAPTADOR BLUETOOTH")
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { sb.append("SIN ADAPTADOR\n"); return }
        safe("enabled") { ad.isEnabled.toString() }
        safe("name") { ad.name ?: "null" }
        safe("address") { ad.address ?: "null" }
        safe("state") { ad.state.toString() }
        safe("vinculados") {
            val b = ad.bondedDevices
            if (b.isNullOrEmpty()) "(ninguno)"
            else b.joinToString(" | ") { d: BluetoothDevice -> d.name + "/" + d.address }
        }
    }

    private fun profiles() {
        t("PERFILES BLUETOOTH")
        val ad = BluetoothAdapter.getDefaultAdapter()
        if (ad == null) { sb.append("(sin adaptador)\n"); return }
        val listener = object : BluetoothProfile.ServiceListener {
            @Suppress("MissingPermission")
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == AVRCP_CONTROLLER) avrcpProxy = proxy
                if (profile == A2DP_SINK) sinkProxy = proxy
                try {
                    proxyLog.append("perfil ").append(profile).append(" OK clase=")
                        .append(proxy?.javaClass?.name).append(" conectados=")
                        .append(proxy?.connectedDevices?.joinToString { it.address } ?: "null")
                        .append('\n')
                } catch (e: Throwable) {
                    proxyLog.append("perfil ").append(profile).append(" listo, fallo listar: ")
                        .append(e.message).append('\n')
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val toTest = listOf(
            BluetoothProfile.A2DP to "A2DP source",
            A2DP_SINK to "A2DP_SINK (clave)",
            AVRCP_CONTROLLER to "AVRCP_CONTROLLER (clave)",
            HEADSET_CLIENT to "HEADSET_CLIENT"
        )
        for ((id, label) in toTest) {
            try {
                sb.append(label).append(" id=").append(id).append(" -> proxy=")
                    .append(ad.getProfileProxy(ctx, listener, id)).append('\n')
            } catch (e: Throwable) {
                sb.append(label).append(" id=").append(id).append(" -> ERROR ")
                    .append(e.javaClass.simpleName).append('\n')
            }
        }
    }

    private fun audioState() {
        t("ESTADO DE AUDIO")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        safe("isMusicActive (clave)") { am.isMusicActive.toString() }
        @Suppress("DEPRECATION")
        safe("isBluetoothA2dpOn") { am.isBluetoothA2dpOn.toString() }
        safe("volumen musica") {
            am.getStreamVolume(AudioManager.STREAM_MUSIC).toString() + "/" +
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun mediaSessions() {
        t("MEDIA SESSIONS")
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: ""
        val mine = enabled.contains(ctx.packageName)
        l("permiso concedido", mine)
        if (!mine) { sb.append("-> Toca PERMISO, activa BT Diag y reintenta.\n"); return }
        safe("sesiones") {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(ctx, NotifListener::class.java))
            if (list.isEmpty()) "(cero)"
            else list.joinToString("\n  ", "\n  ") { c ->
                val md = c.metadata
                "pkg=" + c.packageName +
                    " estado=" + c.playbackState?.state +
                    " acciones=" + c.playbackState?.actions +
                    " titulo=" + md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) +
                    " artista=" + md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            }
        }
    }

    private fun visualizer() {
        t("VISUALIZER SESION GLOBAL (0)")
        var v: Visualizer? = null
        try {
            v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.enabled = true
            l("captureSize", v.captureSize)
            val wave = ByteArray(v.captureSize)
            var maxDelta = 0
            repeat(15) {
                v.getWaveForm(wave)
                var mn = 127; var mx = -128
                for (b in wave) { val x = b.toInt(); if (x < mn) mn = x; if (x > mx) mx = x }
                if (mx - mn > maxDelta) maxDelta = mx - mn
                Thread.sleep(60)
            }
            l("amplitud maxima", maxDelta)
            sb.append(if (maxDelta > 4) ">>> HAY SENAL, visualizador viable.\n"
                      else ">>> Silencio: o no sonaba musica, o el audio no pasa por el mixer.\n")
            v.enabled = false
        } catch (e: Throwable) {
            sb.append("FALLO: ").append(e.javaClass.simpleName).append(" / ").append(e.message).append('\n')
        } finally { try { v?.release() } catch (ig: Throwable) {} }
    }

    private fun equalizer() {
        t("EQUALIZER SESION GLOBAL (0)")
        var eq: Equalizer? = null
        try {
            eq = Equalizer(0, 0)
            eq.enabled = true
            l("bandas", eq.numberOfBands)
            l("presets", eq.numberOfPresets)
            sb.append(">>> Ecualizador global disponible.\n")
            eq.enabled = false
        } catch (e: Throwable) {
            sb.append("FALLO: ").append(e.javaClass.simpleName).append(" / ").append(e.message).append('\n')
        } finally { try { eq?.release() } catch (ig: Throwable) {} }
    }

    private fun packages() {
        t("APPS DE BLUETOOTH")
        safe("paquetes") {
            val found = ctx.packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .filter {
                    it.contains("bluetooth", true) || it.contains(".bt.", true) ||
                        it.endsWith(".bt") || it.contains("a2dp", true) ||
                        it.contains("avrcp", true) || it.contains("btmusic", true)
                }.sorted()
            if (found.isEmpty()) "(ninguno)" else found.joinToString("\n  ", "\n  ")
        }
    }


    /** Que metodos expone realmente el proxy de AVRCP en este firmware. */
    private fun apiProxy() {
        t("API REAL DEL PROXY AVRCP / A2DP_SINK")
        for ((nombre, px) in listOf("AVRCP_CONTROLLER" to avrcpProxy, "A2DP_SINK" to sinkProxy)) {
            sb.append("-- ").append(nombre).append(": ")
            if (px == null) { sb.append("NO respondio\n"); continue }
            sb.append(px.javaClass.name).append('\n')
            try {
                px.javaClass.declaredMethods
                    .map { m -> m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")" }
                    .distinct().sorted()
                    .forEach { sb.append("   ").append(it).append('\n') }
            } catch (e: Throwable) {
                sb.append("   no se pudo listar: ").append(e.message).append('\n')
            }
        }
    }

    /** Quien escucha los broadcasts de AVRCP en este equipo. */
    private fun receptores() {
        t("QUIEN ESCUCHA LOS BROADCASTS AVRCP")
        val acciones = listOf(
            "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.avrcp-controller.profile.action.BROWSE_CONNECTION_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.AUDIO_CONFIG_CHANGED"
        )
        for (a in acciones) {
            safe(a.substringAfterLast('.')) {
                val r = ctx.packageManager.queryBroadcastReceivers(android.content.Intent(a), 0)
                if (r.isEmpty()) "(nadie)"
                else r.joinToString(", ") { it.activityInfo.packageName + "/" + it.activityInfo.name }
            }
        }
    }

    /** Componentes de las apps de Bluetooth del fabricante. */
    private fun appFabricante() {
        t("COMPONENTES DE LAS APPS DEL FABRICANTE")
        val flags = android.content.pm.PackageManager.GET_RECEIVERS or
            android.content.pm.PackageManager.GET_SERVICES or
            android.content.pm.PackageManager.GET_ACTIVITIES
        for (pkg in listOf("com.nwd.bt.music", "com.bt.bc03", "com.android.bluetooth")) {
            sb.append("-- ").append(pkg).append('\n')
            try {
                val pi = ctx.packageManager.getPackageInfo(pkg, flags)
                pi.receivers?.forEach { sb.append("   RECEIVER ").append(it.name).append('\n') }
                pi.services?.forEach { sb.append("   SERVICE  ").append(it.name).append('\n') }
                pi.activities?.take(12)?.forEach { sb.append("   ACTIVITY ").append(it.name).append('\n') }
            } catch (e: Throwable) {
                sb.append("   no instalado o sin acceso: ").append(e.message).append('\n')
            }
        }
    }

    private fun verdict() {
        t("PERFILES ASINCRONOS")
        sb.append(if (proxyLog.isEmpty()) "(ninguno respondio)\n" else proxyLog)
        sb.append("\n--- FIN DEL REPORTE ---\n")
    }
}
