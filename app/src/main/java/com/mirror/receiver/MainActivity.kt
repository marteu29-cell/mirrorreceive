package com.mirror.receiver

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class MainActivity : AppCompatActivity() {

    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var broadcastJob: Job? = null
    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null

    companion object {
        const val TCP_VIDEO_PORT = 5555
        const val TCP_AUDIO_PORT = 5557
        const val UDP_PORT = 5556
        const val BROADCAST_MSG = "MIRRORRECEIVE_HERE"
        const val TYPE_VIDEO: Byte = 0x01
        const val TYPE_AUDIO: Byte = 0x02
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Tela cheia imersiva
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val imageView = findViewById<ImageView>(R.id.ivScreen)
        val tvStatus  = findViewById<TextView>(R.id.tvStatus)
        val tvIp      = findViewById<TextView>(R.id.tvIp)

        val localIp = getLocalIp()
        tvIp.text    = "📺 IP: $localIp"
        tvStatus.text = "⏳ Aguardando celular..."

        startUdpBroadcast(localIp)
        startVideoServer(imageView, tvStatus)
        startAudioServer()
    }

    private fun getLocalIp(): String {
        return try {
            java.net.Socket("8.8.8.8", 80).use { it.localAddress.hostAddress ?: "?" }
        } catch (e: Exception) {
            try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (iface in ifaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is java.net.Inet4Address) return addr.hostAddress ?: "?"
                    }
                }
                "Sem rede"
            } catch (e2: Exception) { "Sem rede" }
        }
    }

    private fun startUdpBroadcast(localIp: String) {
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            val socket = DatagramSocket()
            socket.broadcast = true
            val msg = "$BROADCAST_MSG:$localIp".toByteArray()
            val addr = InetAddress.getByName("255.255.255.255")
            while (isActive) {
                try {
                    socket.send(DatagramPacket(msg, msg.size, addr, UDP_PORT))
                } catch (_: Exception) {}
                delay(2000)
            }
            socket.close()
        }
    }

    private fun startVideoServer(imageView: ImageView, tvStatus: TextView) {
        videoJob = CoroutineScope(Dispatchers.IO).launch {
            videoServer = ServerSocket(TCP_VIDEO_PORT)
            while (isActive) {
                withContext(Dispatchers.Main) { tvStatus.text = "⏳ Aguardando celular..." }
                val client = videoServer!!.accept()
                withContext(Dispatchers.Main) {
                    tvStatus.text = "✅ Conectado: ${client.inetAddress.hostAddress}"
                }
                val din = DataInputStream(client.getInputStream())
                try {
                    while (isActive) {
                        val type = din.readByte()
                        val size = din.readInt()
                        if (size <= 0 || size > 20_000_000) continue
                        val bytes = ByteArray(size)
                        var read = 0
                        while (read < size) {
                            val n = din.read(bytes, read, size - read)
                            if (n < 0) throw Exception("EOF")
                            read += n
                        }
                        if (type == TYPE_VIDEO) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, size)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    imageView.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { tvStatus.text = "❌ Desconectado. Aguardando..." }
                } finally {
                    client.close()
                }
            }
        }
    }

    private fun startAudioServer() {
        audioJob = CoroutineScope(Dispatchers.IO).launch {
            audioServer = ServerSocket(TCP_AUDIO_PORT)
            while (isActive) {
                val client = audioServer!!.accept()
                val din = DataInputStream(client.getInputStream())

                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

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
                try {
                    while (isActive) {
                        val type = din.readByte()
                        val size = din.readInt()
                        if (size <= 0 || size > 65536) continue
                        val bytes = ByteArray(size)
                        var read = 0
                        while (read < size) {
                            val n = din.read(bytes, read, size - read)
                            if (n < 0) throw Exception("EOF")
                            read += n
                        }
                        if (type == TYPE_AUDIO) {
                            track.write(bytes, 0, size)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    track.stop()
                    track.release()
                    client.close()
                }
            }
        }
    }

    override fun onDestroy() {
        broadcastJob?.cancel()
        videoJob?.cancel()
        audioJob?.cancel()
        videoServer?.close()
        audioServer?.close()
        super.onDestroy()
    }
}
