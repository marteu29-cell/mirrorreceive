package com.mirror.receiver

import android.graphics.BitmapFactory
import android.os.Bundle
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

        val imageView = findViewById<ImageView>(R.id.ivScreen)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvIp = findViewById<TextView>(R.id.tvIp)

        // Mostrar IP local
        val localIp = getLocalIp()
        tvIp.text = "IP desta TV Box: $localIp"
        tvStatus.text = "Aguardando celular..."

        // Iniciar broadcast UDP para anunciar presença
        startUdpBroadcast(localIp)

        // Iniciar servidor TCP para receber frames
        startTcpServer(imageView, tvStatus)
    }

    private fun getLocalIp(): String {
        return try {
            val s = java.net.Socket("8.8.8.8", 80)
            val ip = s.localAddress.hostAddress ?: "?"
            s.close()
            ip
        } catch (e: Exception) {
            "Sem rede"
        }
    }

    private fun startUdpBroadcast(localIp: String) {
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            val socket = DatagramSocket()
            socket.broadcast = true
            val msg = "$BROADCAST_MSG:$localIp".toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            while (isActive) {
                try {
                    val packet = DatagramPacket(msg, msg.size, broadcastAddr, UDP_PORT)
                    socket.send(packet)
                    delay(2000) // anunciar a cada 2s
                } catch (e: Exception) {
                    break
                }
            }
            socket.close()
        }
    }

    private fun startTcpServer(imageView: ImageView, tvStatus: TextView) {
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)

                while (isActive) {
                    val client = serverSocket!!.accept()
                    val clientIp = client.inetAddress.hostAddress

                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Conectado: $clientIp"
                    }

                    val input = DataInputStream(client.getInputStream())

                    try {
                        while (isActive) {
                            val size = input.readInt()
                            if (size <= 0 || size > 10_000_000) continue

                            val bytes = ByteArray(size)
                            var read = 0
                            while (read < size) {
                                val n = input.read(bytes, read, size - read)
                                if (n < 0) break
                                read += n
                            }

                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, size)
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Desconectado. Aguardando..."
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
