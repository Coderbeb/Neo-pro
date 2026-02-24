package com.autoapk.automation.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

/**
 * Manages Bluetooth SPP (Serial Port Profile) connection with a Raspberry Pi
 * or other microcontroller.
 *
 * The receiver acts as a Bluetooth SERVER — the Pi connects TO the phone.
 * Commands are received as newline-terminated strings.
 */
class BluetoothCommandReceiver(private val context: Context) {

    companion object {
        private const val TAG = "Neo_BT"
        // Standard SPP UUID for serial communication
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "Neo_BT_Service"
    }

    interface BluetoothListener {
        fun onCommandReceived(command: String)
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onError(message: String)
    }

    private var listener: BluetoothListener? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var isRunning: Boolean = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setListener(listener: BluetoothListener) {
        this.listener = listener
    }

    fun isConnected(): Boolean = connectedSocket?.isConnected == true

    /**
     * Start the Bluetooth server and wait for a connection from the Pi.
     */
    @SuppressLint("MissingPermission")
    fun startServer() {
        if (isRunning) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            listener?.onError("Bluetooth not available on this device")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            listener?.onError("Please enable Bluetooth first")
            return
        }

        isRunning = true

        connectionJob = scope.launch {
            try {
                // Create server socket
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    SPP_UUID
                )
                Log.i(TAG, "Bluetooth server started, waiting for connection...")

                while (isRunning) {
                    try {
                        // This blocks until a device connects
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            connectedSocket = socket
                            val deviceName = socket.remoteDevice.name ?: "Unknown Device"
                            Log.i(TAG, "Device connected: $deviceName")

                            withContext(Dispatchers.Main) {
                                listener?.onDeviceConnected(deviceName)
                            }

                            // Start reading commands from the connected device
                            readFromDevice(socket)

                            // If we reach here, the device disconnected
                            withContext(Dispatchers.Main) {
                                listener?.onDeviceDisconnected()
                            }
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Connection accept error: ${e.message}")
                            delay(2000) // Wait before retrying
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Server socket creation failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    listener?.onError("Failed to start Bluetooth server: ${e.message}")
                }
            }
        }
    }

    /**
     * Connect to a specific paired device (alternative to server mode).
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        scope.launch {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                connectedSocket = socket

                val deviceName = device.name ?: "Unknown"
                Log.i(TAG, "Connected to: $deviceName")

                withContext(Dispatchers.Main) {
                    listener?.onDeviceConnected(deviceName)
                }

                readFromDevice(socket)

                withContext(Dispatchers.Main) {
                    listener?.onDeviceDisconnected()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    listener?.onError("Connection failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Reads newline-terminated commands from the Bluetooth socket.
     */
    private suspend fun readFromDevice(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            var line: String?

            while (isRunning && socket.isConnected) {
                line = reader.readLine()
                if (line != null) {
                    val command = line.trim()
                    if (command.isNotEmpty()) {
                        Log.i(TAG, "BT command received: '$command'")
                        withContext(Dispatchers.Main) {
                            listener?.onCommandReceived(command)
                        }
                    }
                } else {
                    // null means end of stream (device disconnected)
                    break
                }
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.w(TAG, "Read error (device likely disconnected): ${e.message}")
            }
        }
    }

    /**
     * Send a response back to the connected device.
     */
    fun sendResponse(message: String) {
        scope.launch {
            try {
                connectedSocket?.outputStream?.let { out ->
                    out.write("$message\n".toByteArray())
                    out.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send response: ${e.message}")
            }
        }
    }

    /**
     * Get the list of paired Bluetooth devices.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return emptyList()
        return adapter.bondedDevices.toList()
    }

    /**
     * Stop the Bluetooth server and close all connections.
     */
    fun stop() {
        isRunning = false
        try {
            connectedSocket?.close()
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing sockets: ${e.message}")
        }
        connectionJob?.cancel()
        readJob?.cancel()
        connectedSocket = null
        serverSocket = null
        Log.i(TAG, "Bluetooth receiver stopped")
    }
}
