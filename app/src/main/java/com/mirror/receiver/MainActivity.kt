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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class MainActivity : AppCompatActivity() {

    companion object {
        const val TCP_VIDEO_PORT = 5555
        const val TCP_AUDIO_PORT = 5557
        const val UDP_PORT       = 5556
        const val BROADCAST_MSG  = "MIRRORRECEIVE_HERE"
        const val TYPE_VIDEO     = 0x01.toByte()
        const val TYPE_AUDIO     = 0x02.toByte()
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = true

    private var videoServerSocket: ServerSocket? = null
    private var audioServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        imageView = findViewById(R.id.ivScreen)
        tvStatus  = findViewById(R.id.tvStatus)
        tvIp      = findViewById(R.id.tvIp)

        val ip = getLocalIp()
        tvIp.text     = "📺 IP: $ip  |  Porta: $TCP_VIDEO_PORT"
        tvStatus.text = "⏳ Iniciando servidores..."

        startUdpBroadcast(ip)
        startVideoServer()
        startAudioServer()
    }

    // ── IP local ─────────────────────────────────────────────────────────────
    private fun getLocalIp(): String {
        return try {
            java.net.Socket("8.8.8.8", 80).use { it.localAddress.hostAddress ?: "?" }
        } catch (_: Exception) {
            try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (iface in ifaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses)
                        if (addr is java.net.Inet4Address) return addr.hostAddress ?: "?"
                }
                "Sem Wi-Fi"
            } catch (_: Exception) { "Sem Wi-Fi" }
        }
    }

    // ── UDP broadcast ─────────────────────────────────────────────────────────
    private fun startUdpBroadcast(localIp: String) {
        Thread {
            try {
                udpSocket = DatagramSocket()
                udpSocket!!.broadcast = true
                val msg  = "$BROADCAST_MSG:$localIp".toByteArray()
                val addr = InetAddress.getByName("255.255.255.255")
                ui.post { tvStatus.text = "📡 Anunciando... IP: $localIp" }
                while (running) {
                    try {
                        udpSocket!!.send(DatagramPacket(msg, msg.size, addr, UDP_PORT))
                    } catch (_: Exception) {}
                    Thread.sleep(2000)
                }
            } catch (e: Exception) {
                ui.post { tvStatus.text = "UDP erro: ${e.message}" }
            }
        }.apply { isDaemon = true; name = "UDP-Broadcast"; start() }
    }

    // ── Servidor de vídeo TCP ─────────────────────────────────────────────────
    private fun startVideoServer() {
        Thread {
            try {
                videoServerSocket = ServerSocket(TCP_VIDEO_PORT)
                videoServerSocket!!.soTimeout = 0  // espera infinita
                ui.post { tvStatus.text = "✅ Pronto — aguardando celular na porta $TCP_VIDEO_PORT" }

                while (running) {
                    val client = try {
                        videoServerSocket!!.accept()
                    } catch (e: Exception) {
                        if (running) ui.post { tvStatus.text = "Erro accept: ${e.message}" }
                        break
                    }

                    val clientIp = client.inetAddress.hostAddress
                    ui.post { tvStatus.text = "🔗 Celular conectado: $clientIp" }

                    val din = DataInputStream(client.getInputStream())
                    try {
                        while (running) {
                            val type = din.readByte()
                            val size = din.readInt()

                            if (size <= 0 || size > 20_000_000) continue

                            val bytes = ByteArray(size)
                            var total = 0
                            while (total < size) {
                                val n = din.read(bytes, total, size - total)
                                if (n < 0) throw Exception("Stream encerrado")
                                total += n
                            }

                            if (type == TYPE_VIDEO) {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, size)
                                if (bmp != null) {
                                    ui.post { imageView.setImageBitmap(bmp) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ui.post { tvStatus.text = "❌ Celular desconectou — aguardando..." }
                    } finally {
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                ui.post { tvStatus.text = "Erro servidor vídeo: ${e.message}" }
            }
        }.apply { isDaemon = true; name = "Video-Server"; start() }
    }

    // ── Servidor de áudio TCP ─────────────────────────────────────────────────
    private fun startAudioServer() {
        Thread {
            try {
                audioServerSocket = ServerSocket(TCP_AUDIO_PORT)
                while (running) {
                    val client = try { audioServerSocket!!.accept() } catch (_: Exception) { break }

                    val sampleRate    = 44100
                    val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                    val encoding      = AudioFormat.ENCODING_PCM_16BIT
                    val minBuf        = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 4)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    track.play()
                    val din = DataInputStream(client.getInputStream())
                    try {
                        while (running) {
                            val type = din.readByte()
                            val size = din.readInt()
                            if (size <= 0 || size > 65536) continue
                            val bytes = ByteArray(size)
                            var total = 0
                            while (total < size) {
                                val n = din.read(bytes, total, size - total)
                                if (n < 0) throw Exception("EOF")
                                total += n
                            }
                            if (type == TYPE_AUDIO) {
                                track.write(bytes, 0, size)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { track.stop(); track.release() } catch (_: Exception) {}
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "Audio-Server"; start() }
    }

    override fun onDestroy() {
        running = false
        try { videoServerSocket?.close() } catch (_: Exception) {}
        try { audioServerSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() }         catch (_: Exception) {}
        super.onDestroy()
    }
}
