package com.mirror.receiver

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.net.NetworkInterface
import java.net.ServerSocket

class MainActivity : AppCompatActivity() {

    companion object {
        const val VIDEO_PORT = 5555
        const val AUDIO_PORT = 5557
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private lateinit var ivScreen: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        ivScreen = findViewById(R.id.ivScreen)
        tvStatus = findViewById(R.id.tvStatus)
        tvIp     = findViewById(R.id.tvIp)

        val ip = getWifiIp()
        tvIp.text     = "IP da TV Box: $ip"
        tvStatus.text = "Porta $VIDEO_PORT  |  Aguardando celular..."

        startVideoServer()
        startAudioServer()
    }

    private fun getWifiIp(): String {
        return try {
            java.net.Socket("8.8.8.8", 80).use { it.localAddress.hostAddress ?: "?" }
        } catch (_: Exception) {
            try {
                for (iface in NetworkInterface.getNetworkInterfaces()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address)
                            return addr.hostAddress ?: continue
                    }
                }
                "Sem Wi-Fi"
            } catch (_: Exception) { "Sem Wi-Fi" }
        }
    }

    private fun startVideoServer() {
        Thread {
            try {
                videoServer = ServerSocket(VIDEO_PORT)
            } catch (e: Exception) {
                ui.post { tvStatus.text = "ERRO porta $VIDEO_PORT: ${e.message}" }
                return@Thread
            }
            while (alive) {
                ui.post { tvStatus.text = "Aguardando celular na porta $VIDEO_PORT..." }
                val client = try { videoServer!!.accept() } catch (e: Exception) {
                    if (alive) ui.post { tvStatus.text = "Erro: ${e.message}" }
                    break
                }
                ui.post { tvStatus.text = "Conectado: ${client.inetAddress.hostAddress}" }
                val din = DataInputStream(client.getInputStream().buffered(512 * 1024))
                try {
                    while (alive) {
                        val type = din.readByte()
                        val len  = din.readInt()
                        if (len <= 0 || len > 10_000_000) continue
                        val buf = ByteArray(len)
                        din.readFully(buf)
                        if (type == 0x01.toByte()) {
                            val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                            if (bmp != null) ui.post { ivScreen.setImageBitmap(bmp) }
                        }
                    }
                } catch (_: Exception) {
                    ui.post { tvStatus.text = "Desconectou — aguardando..." }
                } finally { runCatching { client.close() } }
            }
        }.also { it.isDaemon = true; it.name = "VideoServer" }.start()
    }

    private fun startAudioServer() {
        Thread {
            try { audioServer = ServerSocket(AUDIO_PORT) } catch (_: Exception) { return@Thread }
            while (alive) {
                val client = try { audioServer!!.accept() } catch (_: Exception) { break }
                val sr   = 44100
                val mbuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(mbuf * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
                track.play()
                val din = DataInputStream(client.getInputStream())
                try {
                    while (alive) {
                        val type = din.readByte()
                        val len  = din.readInt()
                        if (len <= 0 || len > 65536) continue
                        val buf = ByteArray(len)
                        din.readFully(buf)
                        if (type == 0x02.toByte()) track.write(buf, 0, len)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { track.stop(); track.release() }
                    runCatching { client.close() }
                }
            }
        }.also { it.isDaemon = true; it.name = "AudioServer" }.start()
    }

    override fun onDestroy() {
        alive = false
        runCatching { videoServer?.close() }
        runCatching { audioServer?.close() }
        super.onDestroy()
    }
}
