package com.avaruz.printservice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.json.JSONArray
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.coroutines.CoroutineContext

class PrintService : Service(), CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job

    private val channelId = "MEGALABS_PRINT_SERVICE"
    private val printNotificationChannelId = "PRINT_NOTIFICATION_CHANNEL"
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var welcomeSocket: ServerSocket? = null

    companion object {
        const val TAG = "PrintService"
        const val MAX_RETRIES = 5
        const val SERVER_PORT = 6789
        const val MAX_ARRAY_SIZE = 25600
        const val SLEEP_TIME = 100L
        private val PRINTER_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // UUID standard for serial port
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()
        Log.i(TAG, "PrintService onCreate")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                startCoroutineService()
            }
            "STOP" -> {
                stopCoroutineService()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopCoroutineService() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Servicio de impresión")
            .setContentText("Servicio de impresión finalizado")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de tener este icono
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(System.currentTimeMillis().toInt(), notification)
        stopForeground(true) // Elimina la notificación de servicio foreground
        stopSelf()
        job.cancel() // Cancela todas las coroutines
        Log.i(TAG, "PrintService stopped")
    }

    private fun startCoroutineService() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Servicio de impresión")
            .setContentText("Servicio de impresión iniciado")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de tener este icono
            .build()
        startForeground(1, notification)
        listenForConnections()
        Log.i(TAG, "PrintService started with Coroutines")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            welcomeSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing welcomeSocket", e)
        }
        job.cancel() // Asegúrate de que todas las coroutines se cancelen
        Log.i(TAG, "PrintService onDestroy")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Servicio de Impresión Foreground",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)

            val printChannel = NotificationChannel(
                printNotificationChannelId,
                "Notificaciones de Impresión",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager?.createNotificationChannel(printChannel)
        }
    }

    private fun showPrintNotification(message: String) {
        val intent = Intent(this, PrintService::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, printNotificationChannelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de tener un icono de impresión
            .setContentTitle("Impresión Exitosa")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    suspend fun printTest(deviceAddress: String, testMessage: String): String {
        return print(
            deviceAddress,
            """
            BeginPage();
            SetMargin(0,0);
            SetPageSize(576,400);
            SetMargin(0,0);
            DrawText(0,0,1,0,"<b><f=3>***************************");
            DrawText(0,30,1,0,"<b><f=3>** $testMessage **");
            DrawText(0,70,1,0,"<b><f=3>** SOLUTICA     **");
            DrawText(0,100,1,0,"<b><f=3>***************************");
            EndPage();
            """.trimIndent()
        )
    }

    suspend fun print(deviceAddress: String, printLine: String?): String {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Bluetooth Connect permission not granted")
                return createSoapErrorResponse("Permiso de Bluetooth Connect no concedido")
            }

            val remoteDevice: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
            val socket: BluetoothSocket = remoteDevice.createRfcommSocketToServiceRecord(PRINTER_UUID)
            socket.connect()

            printLine?.let { line ->
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream

                val byteArray = if (line.startsWith("[")) {
                    try {
                        JSONArray(line).let { jsonArray ->
                            ByteArray(jsonArray.length()).apply {
                                for (i in 0 until jsonArray.length()) {
                                    this[i] = jsonArray.getInt(i).toByte()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing printLine as JSONArray", e)
                        return createSoapErrorResponse("Error al procesar los datos de impresión como JSON")
                    }

                } else {
                    line.toByteArray()
                }

                try {
                    outputStream.write(byteArray)
                    outputStream.flush()
                    delay(300L) // Dale tiempo para que se envíen los datos

                    val response = if (inputStream.available() > 0) {
                        val buffer = ByteArray(1000)
                        val bytesRead = inputStream.read(buffer)
                        val responseString = Charset.forName("UTF-8").decode(ByteBuffer.wrap(buffer,0,
                            bytesRead
                        )).toString()
                        val fullResponse = "<response error=\"false\">OK: $responseString </response>"
                        if (fullResponse.contains("OK")) {
                            showPrintNotification("Documento impreso exitosamente en $deviceAddress")
                        }
                        fullResponse
                    } else {
                        val fullResponse = "<response error=\"false\">OK</response>"
                        if (fullResponse.contains("OK")) {
                            showPrintNotification("Documento impreso exitosamente en $deviceAddress")
                        }
                        fullResponse
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "I/O error during printing", e)
                    return createSoapErrorResponse("Error de E/S durante la impresión: ${e.message}")
                } finally {
                    try{
                        outputStream.close()
                        inputStream.close()
                        socket.close()
                    }catch (e: IOException){
                        Log.e(TAG, "Error closing streams or socket", e)
                    }

                }
            } ?: return createSoapErrorResponse("No se proporcionó datos para imprimir")

        } catch (e: Exception) {
            Log.e(TAG, "Error en print()", e)
            return createSoapErrorResponse("Error inesperado: ${e.message}")
        }
        return createSoapErrorResponse("Error inesperado: no se pudo imprimir")
    }

    fun getBoundedDevices(): String {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Bluetooth Connect permission not granted for getBoundedDevices")
                return createSoapErrorResponse("Permiso de Bluetooth Connect no concedido")
            }
            if (!bluetoothAdapter.isEnabled) {
                return createSoapErrorResponse("Bluetooth no está habilitado")
            }
            val stringBuilder = StringBuilder()
            for (bluetoothDevice in bluetoothAdapter.bondedDevices) {
                stringBuilder.append(bluetoothDevice.name)
                stringBuilder.append("|")
                stringBuilder.append(bluetoothDevice.address)
                stringBuilder.append("||")
            }
            return createSoapResponse(stringBuilder.toString())
        } catch (e: SecurityException) {
            return createSoapErrorResponse("Error de seguridad: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado en getBoundedDevices()", e)
            return createSoapErrorResponse("Error inesperado: ${e.message}")
        }
    }

    private fun listenForConnections() = launch(IO) {
        try {
            welcomeSocket = ServerSocket(SERVER_PORT)
            Log.i(TAG, "Coroutine AcceptThread started on port $SERVER_PORT")
            while (isActive) {
                try {
                    val socket = welcomeSocket?.accept()
                    socket?.let {
                        Log.i(TAG, "Coroutine AcceptThread accepted a connection from ${it.inetAddress.hostAddress}")
                        launch(IO) { handleSocket(it) } // Lanza una nueva coroutine para manejar la conexión
                    }
                } catch (e: SocketException) {
                    if (isActive) {
                        Log.e(TAG, "Socket error in Coroutine AcceptThread", e)
                    }
                    break
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e(TAG, "I/O error in Coroutine AcceptThread", e)
                    }
                    break
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error creating server socket in Coroutine", e)
        } finally {
            try {
                welcomeSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing welcomeSocket in Coroutine", e)
            }
            Log.i(TAG, "Coroutine AcceptThread stopped")
        }
    }

    private suspend fun handleSocket(socket: Socket) = withContext(IO) {
        var inputStream = socket.getInputStream()
        var outputStream = socket.getOutputStream()
        var finalDocumento = false

        while (isActive && !finalDocumento) {
            var requestComplete = false
            var request = ""
            var retries = MAX_RETRIES

            while (isActive && !finalDocumento && !requestComplete && retries > 0) {
                retries--
                try {
                    if (inputStream.available() > 0) {
                        val buffer = ByteArray(MAX_ARRAY_SIZE)
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            request += String(buffer, 0, bytesRead)
                        }
                    } else {
                        delay(SLEEP_TIME) // Espera un poco antes de reintentar
                    }

                    if (request.startsWith("OPTIONS")) {
                        val response = "HTTP/1.1 200 OK\nContent-Type: application/soap+xml; charset=utf-8\nAccess-Control-Allow-Origin: *\nAccess-Control-Allow-Headers: Content-Type\nAccess-Control-Allow-Methods: PUT, GET, POST, DELETE, OPTIONS\nContent-Length: 0\n\n".toByteArray()
                        outputStream.write(response)
                        outputStream.flush()
                        requestComplete = true
                        finalDocumento = true
                        Log.d(TAG, "OPTIONS request handled by coroutine")
                    }

                    if (request.contains("<soap12:Envelope") || request.contains("<soap:Envelope")) {
                        requestComplete = true
                    }

                    if (retries <= 0 && !requestComplete) {
                        Log.w(TAG, "Maximum retries reached without a complete request in coroutine")
                        finalDocumento = true // Evita un bucle infinito
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from socket in coroutine", e)
                    finalDocumento = true // Detén el procesamiento de este socket en caso de error
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Coroutine interrupted during delay", e)
                    currentCoroutineContext().cancel()
                    finalDocumento = true
                }
            }

            if (requestComplete) {
                try {
                    val soapEnvelope = request.substringAfter("<soap12:Envelope>", "").substringAfter("<soap:Envelope>", "").substringBeforeLast("</soap12:Envelope>").substringBeforeLast("</soap:Envelope>")
                    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(InputSource(StringReader(soapEnvelope)))

                    val operationElements = document.getElementsByTagName("operation")
                    val addressElements = document.getElementsByTagName("address")
                    val argumentElements = document.getElementsByTagName("argument")

                    if (operationElements.length > 0 && addressElements.length > 0 && argumentElements.length > 0) {
                        val operation = operationElements.item(0).textContent
                        val address = addressElements.item(0).textContent
                        val argument = URLDecoder.decode(
                            argumentElements.item(0).textContent,
                            "UTF-8"
                        ).replace("\n", "\r\n")

                        val response = when (operation.uppercase()) {
                            "PRINT" -> this@PrintService.print(address, argument)
                            "GETDEVICES" -> this@PrintService.getBoundedDevices()
                            "PRINTTEST" -> this@PrintService.printTest(address, "PRUEBA DE IMPRESION")
                            else -> createSoapErrorResponse("Operación desconocida: $operation")
                        }

                        val soapResponse = createSoapResponse(response)

                        val httpResponse = """
                            HTTP/1.1 200 OK
                            Content-Type: application/soap+xml; charset=utf-8
                            Content-Length: ${soapResponse.length}
                            Access-Control-Allow-Origin: *
                            Access-Control-Allow-Headers: Content-Type
                            Access-Control-Allow-Methods: PUT, GET, POST, DELETE, OPTIONS

                            $soapResponse
                            """.trimIndent()

                        outputStream.write(httpResponse.toByteArray())
                        outputStream.flush()
                        Log.d(TAG, "Request handled successfully by coroutine. Operation: $operation, Address: $address")

                    } else {
                        val errorResponse = createSoapErrorResponse("Solicitud SOAP incompleta")
                        val httpErrorResponse = """
                            HTTP/1.1 400 Bad Request
                            Content-Type: application/soap+xml; charset=utf-8
                            Content-Length: ${errorResponse.length}
                            Access-Control-Allow-Origin: *
                            Access-Control-Allow-Headers: Content-Type
                            Access-Control-Allow-Methods: PUT, GET, POST, DELETE, OPTIONS

                            $errorResponse
                        """.trimIndent()
                        outputStream.write(httpErrorResponse.toByteArray())
                        outputStream.flush()
                        Log.w(TAG, "Incomplete SOAP request received by coroutine")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SOAP request in coroutine", e)
                    val errorResponse = createSoapErrorResponse("Error al procesar la solicitud SOAP: ${e.message}")
                    val httpErrorResponse = """
                        HTTP/1.1 500 Internal Server Error
                        Content-Type: application/soap+xml; charset=utf-8
                        Content-Length: ${errorResponse.length}
                        Access-Control-Allow-Origin: *
                        Access-Control-Allow-Headers: Content-Type
                        Access-Control-Allow-Methods: PUT, GET, POST, DELETE, OPTIONS

                        $errorResponse
                    """.trimIndent()
                    outputStream.write(httpErrorResponse.toByteArray())
                    outputStream.flush()
                } finally {
                    try {
                        outputStream.close()
                        inputStream.close()
                        socket.close()
                        finalDocumento = true
                        delay(SLEEP_TIME)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Coroutine interrupted while waiting to close socket", e)
                        currentCoroutineContext().cancel()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing socket in coroutine", e)
                    }
                }
            }
        }
        Log.d(TAG, "Socket handling finished by coroutine for ${socket.inetAddress.hostAddress}")
    }

    private fun createSoapResponse(result: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap12:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
                <soap12:Body>
                    <executeResponse xmlns="http://tempuri.org/">
                        <executeResult>${URLEncoder.encode(result, "UTF-8")}</executeResult>
                    </executeResponse>
                </soap12:Body>
            </soap12:Envelope>
            """.trimIndent()
    }

    private fun createSoapErrorResponse(errorMessage: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap12:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
                <soap12:Body>
                    <executeResponse xmlns="http://tempuri.org/">
                        <executeResult>${URLEncoder.encode("<response error=\"true\">$errorMessage</response>", "UTF-8")}</executeResult>
                    </executeResponse>
                </soap12:Body>
            </soap12:Envelope>
            """.trimIndent()
    }
}