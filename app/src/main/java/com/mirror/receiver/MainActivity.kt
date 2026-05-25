package com.mirror.receiver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private var serverJob: Job? = null
    private var broadcastJob: Job? = null
    private var serverSocket: ServerSocket? = null

    companion object {
        const val TCP_PORT = 5555
        const val UDP_PORT = 5556
        const val BROADCAST_MSG = "MIRRORRECEIVE_HERE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Tela cheia
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val imageView = findViewById<ImageView>(R.id.ivScreen)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvIp = findViewById<TextView>(R.id.tvIp)

        val localIp = getLocalIp()
        tvIp.text = "IP: $localIp | Porta: $TCP_PORT"
        tvStatus.text = "Aguardando celular..."

        startUdpBroadcast(localIp)
        startTcpServer(imageView, tvStatus, tvIp)
    }

    private fun getLocalIp(): String {
        return try {
            val s = java.net.Socket("8.8.8.8", 80)
            val ip = s.localAddress.hostAddress ?: "?"
            s.close()
            ip
        } catch (e: Exception) {
            try {
                // fallback: pegar IP das interfaces
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (iface in interfaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is java.net.Inet4Address) return addr.hostAddress ?: "?"
                    }
                }
                "Sem rede"
            } catch (e2: Exception) {
                "Sem rede"
            }
        }
    }

    private fun startUdpBroadcast(localIp: String) {
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val msg = "$BROADCAST_MSG:$localIp".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")

                while (isActive) {
                    try {
                        val packet = DatagramPacket(msg, msg.size, broadcastAddr, UDP_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        // ignorar erro pontual
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                socket?.close()
            }
        }
    }

    private fun startTcpServer(imageView: ImageView, tvStatus: TextView, tvIp: TextView) {
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)

                while (isActive) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "⏳ Aguardando celular..."
                    }

                    val client = serverSocket!!.accept()
                    val clientIp = client.inetAddress.hostAddress

                    withContext(Dispatchers.Main) {
                        tvStatus.text = "✅ Conectado: $clientIp"
                    }

                    val input = DataInputStream(client.getInputStream())
                    var lastBitmap: Bitmap? = null

                    try {
                        while (isActive) {
                            val size = input.readInt()
                            if (size <= 0 || size > 20_000_000) {
                                continue
                            }

                            val bytes = ByteArray(size)
                            var read = 0
                            while (read < size) {
                                val n = input.read(bytes, read, size - read)
                                if (n < 0) throw Exception("Conexão encerrada")
                                read += n
                            }

                            val options = BitmapFactory.Options().apply {
                                inMutable = true
                                inBitmap = lastBitmap
                            }

                            val bitmap = try {
                                BitmapFactory.decodeByteArray(bytes, 0, size, options)
                            } catch (e: Exception) {
                                BitmapFactory.decodeByteArray(bytes, 0, size)
                            }

                            if (bitmap != null) {
                                lastBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    imageView.setImageBitmap(bitmap)
                                    imageView.invalidate()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastBitmap = null
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "❌ Desconectado. Aguardando..."
                        }
                    } finally {
                        client.close()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Erro: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        broadcastJob?.cancel()
        serverJob?.cancel()
        serverSocket?.close()
        super.onDestroy()
    }
}
