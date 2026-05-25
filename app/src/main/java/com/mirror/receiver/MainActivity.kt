package com.mirror.receiver

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.ServerSocket

class MainActivity : AppCompatActivity() {

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val imageView = findViewById<ImageView>(R.id.ivScreen)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnStart.setOnClickListener {
            tvStatus.text = "Aguardando conexão na porta 5555..."
            startServer(imageView, tvStatus)
        }

        btnStop.setOnClickListener {
            serverJob?.cancel()
            serverSocket?.close()
            tvStatus.text = "Servidor parado."
            imageView.setImageBitmap(null)
        }
    }

    private fun startServer(imageView: ImageView, tvStatus: TextView) {
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(5555)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Aguardando conexão..."
                }

                val client = serverSocket!!.accept()
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Conectado: ${client.inetAddress.hostAddress}"
                }

                val input = DataInputStream(client.getInputStream())

                while (isActive) {
                    val size = input.readInt()
                    if (size <= 0) continue

                    val bytes = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        read += input.read(bytes, read, size - read)
                    }

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, size)
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Desconectado."
                }
            }
        }
    }

    override fun onDestroy() {
        serverJob?.cancel()
        serverSocket?.close()
        super.onDestroy()
    }
}
