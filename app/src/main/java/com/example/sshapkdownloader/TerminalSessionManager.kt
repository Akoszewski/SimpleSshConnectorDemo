package com.example.sshapkdownloader

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object TerminalSessionManager {
    interface Listener {
        fun onTerminalOutputChanged(output: CharSequence)
        fun onTerminalEnabledChanged(enabled: Boolean)
        fun onTerminalConnectionLost()
        fun onTerminalDisconnected()
        fun onTerminalConnectionUnavailable()
    }

    private val terminalScreenBuffer = TerminalScreenBuffer { bytes ->
        sendBytes(bytes)
    }
    private val outputLock = Any()
    private val pendingOutput = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val flushPendingOutputRunnable = Runnable {
        flushPendingOutput()
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var connection: TerminalConnection? = null

    @Volatile
    private var terminalEnabled = false

    @Volatile
    private var outputRenderScheduled = false

    fun attachListener(listener: Listener) {
        this.listener = listener
        mainHandler.post {
            listener.onTerminalOutputChanged(terminalScreenBuffer.renderStyled())
            listener.onTerminalEnabledChanged(terminalEnabled)
        }
    }

    fun detachListener(listener: Listener) {
        if (this.listener === listener) {
            this.listener = null
        }
    }

    fun connect(context: Context, address: String, privateKey: String, initialDirectory: String) {
        val request = ConnectionRequest(context.applicationContext, address, privateKey, initialDirectory)
        val activeConnection = connection
        if (activeConnection?.isRunning() == true) {
            notifyOutputChanged()
            notifyTerminalEnabled()
            return
        }

        startConnection(request)
    }

    fun disconnectByUser() {
        val activeConnection = connection
        connection = null
        appendOutput(activeConnection?.contextString(R.string.terminal_disconnected).orEmpty())
        setTerminalEnabled(false)
        activeConnection?.close(
            stopForegroundService = true,
            releaseLocks = true,
            notifyDisconnected = true
        )
    }

    fun disconnectBecauseTaskRemoved() {
        val activeConnection = connection
        connection = null
        setTerminalEnabled(false)
        activeConnection?.close(
            stopForegroundService = false,
            releaseLocks = true,
            notifyDisconnected = false
        )
    }

    fun refresh(context: Context, address: String, privateKey: String, initialDirectory: String) {
        val request = ConnectionRequest(context.applicationContext, address, privateKey, initialDirectory)
        val previousConnection = connection
        connection = null
        setTerminalEnabled(false)

        Thread {
            previousConnection?.close(
                stopForegroundService = false,
                releaseLocks = true,
                notifyDisconnected = false
            )
            clearOutput()
            startConnection(request)
        }.apply {
            name = "ssh-terminal-refresh"
            isDaemon = true
            start()
        }
    }

    fun sendCommand(command: String) {
        val activeConnection = connection
        if (activeConnection == null) {
            notifyConnectionUnavailable()
            return
        }

        if (command.isBlank()) {
            activeConnection.sendBytes(ENTER_KEY_BYTES)
            return
        }

        activeConnection.sendCommand(command)
    }

    fun sendBytes(bytes: ByteArray) {
        val activeConnection = connection
        if (activeConnection == null) {
            notifyConnectionUnavailable()
            return
        }

        activeConnection.sendBytes(bytes)
    }

    fun sendInputEditingKey(currentInput: String, keyBytes: ByteArray) {
        val activeConnection = connection
        if (activeConnection == null) {
            notifyConnectionUnavailable()
            return
        }

        activeConnection.sendBytes(buildInputEditingBytes(currentInput, keyBytes))
    }

    fun sendPrimedInputCommand(command: String) {
        val activeConnection = connection
        if (activeConnection == null) {
            notifyConnectionUnavailable()
            return
        }

        activeConnection.sendBytes(buildInputEditingBytes(command, ENTER_KEY_BYTES))
    }

    fun currentInputPromptColumn(): Int {
        return terminalScreenBuffer.currentCursorColumn()
    }

    fun currentInputAfterPromptColumn(promptColumn: Int): String {
        return terminalScreenBuffer.currentLineTextAfterColumn(promptColumn)
    }

    fun clearOutput() {
        mainHandler.post {
            terminalScreenBuffer.clear()
            notifyOutputChanged()
        }
    }

    private fun startConnection(request: ConnectionRequest) {
        val terminalConnection = TerminalConnection(request, connectionCallbacks())
        connection = terminalConnection
        terminalConnection.start()
    }

    private fun connectionCallbacks(): TerminalConnection.Callbacks {
        return TerminalConnection.Callbacks(
            appendOutput = { text -> appendOutput(text) },
            appendOutputBytes = { bytes -> appendOutput(bytes) },
            setTerminalEnabled = { enabled -> setTerminalEnabled(enabled) },
            notifyConnectionLost = { notifyConnectionLost() },
            notifyConnectionUnavailable = { notifyConnectionUnavailable() },
            notifyDisconnected = { notifyDisconnected() },
            onClosed = { closedConnection ->
                if (connection === closedConnection) {
                    connection = null
                }
            }
        )
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) {
            return
        }

        mainHandler.post {
            terminalScreenBuffer.append(text)
            notifyOutputChanged()
        }
    }

    private fun appendOutput(bytes: ByteArray) {
        synchronized(outputLock) {
            pendingOutput.write(bytes)
            if (outputRenderScheduled) {
                return
            }
            outputRenderScheduled = true
        }
        mainHandler.postDelayed(flushPendingOutputRunnable, TERMINAL_RENDER_DELAY_MS)
    }

    private fun flushPendingOutput() {
        val bytes = synchronized(outputLock) {
            outputRenderScheduled = false
            if (pendingOutput.size() == 0) {
                return
            }
            pendingOutput.toByteArray().also {
                pendingOutput.reset()
            }
        }
        terminalScreenBuffer.append(bytes, bytes.size)
        notifyOutputChanged()
    }

    private fun setTerminalEnabled(enabled: Boolean) {
        terminalEnabled = enabled
        notifyTerminalEnabled()
    }

    private fun notifyOutputChanged() {
        val output = terminalScreenBuffer.renderStyled()
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalOutputChanged(output)
                }
            }
        }
    }

    private fun notifyTerminalEnabled() {
        val enabled = terminalEnabled
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalEnabledChanged(enabled)
                }
            }
        }
    }

    private fun notifyConnectionLost() {
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalConnectionLost()
                }
            }
        }
    }

    private fun notifyDisconnected() {
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalDisconnected()
                }
            }
        }
    }

    private fun notifyConnectionUnavailable() {
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalConnectionUnavailable()
                }
            }
        }
    }

    private fun buildInputEditingBytes(currentInput: String, keyBytes: ByteArray): ByteArray {
        return CONTROL_U_BYTES + currentInput.toByteArray(Charsets.UTF_8) + keyBytes
    }

    private data class ConnectionRequest(
        val context: Context,
        val address: String,
        val privateKey: String,
        val initialDirectory: String
    )

    private class TerminalConnection(
        private val request: ConnectionRequest,
        private val callbacks: Callbacks
    ) {
        data class Callbacks(
            val appendOutput: (String) -> Unit,
            val appendOutputBytes: (ByteArray) -> Unit,
            val setTerminalEnabled: (Boolean) -> Unit,
            val notifyConnectionLost: () -> Unit,
            val notifyConnectionUnavailable: () -> Unit,
            val notifyDisconnected: () -> Unit,
            val onClosed: (TerminalConnection) -> Unit
        )

        private val writerLock = Any()
        private val closeStarted = AtomicBoolean(false)
        private val reconnectScheduled = AtomicBoolean(false)
        private val generation = AtomicInteger(0)

        @Volatile
        private var session: Session? = null

        @Volatile
        private var channel: ChannelShell? = null

        @Volatile
        private var commandOutput: OutputStream? = null

        @Volatile
        private var stopRequested = false

        @Volatile
        private var connecting = false

        @Volatile
        private var hasConnected = false

        @Volatile
        private var keepAliveThread: Thread? = null

        private var terminalWakeLock: PowerManager.WakeLock? = null
        private var terminalWifiLock: WifiManager.WifiLock? = null

        fun start() {
            if (stopRequested) {
                return
            }

            callbacks.setTerminalEnabled(false)
            callbacks.appendOutput(request.context.getString(R.string.terminal_connecting, request.address))
            reconnectScheduled.set(false)
            connecting = true
            closeStarted.set(false)
            TerminalForegroundService.start(request.context)
            acquireTerminalLocks()
            val currentGeneration = generation.get()

            Thread {
                runCatching {
                    val sshSession = SshSessionFactory.create(
                        SshTargetParser.parse(request.address),
                        request.privateKey
                    )
                    session = sshSession
                    sshSession.connect(15_000)
                    if (!isCurrent(currentGeneration)) {
                        closeCurrentShell(releaseLocks = true)
                        return@Thread
                    }

                    val shell = sshSession.openChannel("shell") as ChannelShell
                    shell.setPty(true)
                    shell.setPtyType("xterm-256color")
                    shell.setPtySize(TERMINAL_COLUMNS, TERMINAL_ROWS, 0, 0)
                    shell.setEnv("TERM", "xterm-256color")
                    val remoteInput = shell.inputStream
                    val remoteOutput = shell.outputStream
                    channel = shell
                    commandOutput = remoteOutput

                    shell.connect(15_000)
                    if (!isCurrent(currentGeneration)) {
                        closeCurrentShell(releaseLocks = true)
                        return@Thread
                    }
                    changeInitialDirectory(remoteOutput)
                    connecting = false
                    startKeepAliveLoop(sshSession, currentGeneration)
                    callbacks.appendOutput(request.context.getString(R.string.terminal_connected))
                    hasConnected = true
                    callbacks.setTerminalEnabled(true)
                    readShellOutput(remoteInput, currentGeneration)
                }.onFailure { error ->
                    connecting = false
                    if (isCurrent(currentGeneration)) {
                        callbacks.appendOutput(
                            request.context.getString(
                                R.string.terminal_connection_error,
                                error.displayMessage()
                            )
                        )
                        callbacks.setTerminalEnabled(false)
                        scheduleReconnect()
                    }
                    if (stopRequested) {
                        close(
                            stopForegroundService = true,
                            releaseLocks = true,
                            notifyDisconnected = true
                        )
                    }
                }
            }.apply {
                name = "ssh-terminal-connect"
                isDaemon = true
                start()
            }
        }

        fun isRunning(): Boolean {
            return !stopRequested && (isSessionActive() || connecting || reconnectScheduled.get())
        }

        fun sendCommand(command: String) {
            val output = commandOutput
            if (output == null || channel?.isClosed == true) {
                callbacks.notifyConnectionUnavailable()
                return
            }

            Thread {
                runCatching {
                    synchronized(writerLock) {
                        output.write(command.toByteArray(Charsets.UTF_8))
                        output.flush()
                        Thread.sleep(ENTER_KEY_DELAY_MS)
                        output.write(ENTER_KEY_BYTES)
                        output.flush()
                    }
                }.onFailure { error ->
                    callbacks.appendOutput(contextString(R.string.terminal_write_error, error.displayMessage()))
                    callbacks.setTerminalEnabled(false)
                    scheduleReconnect()
                }
            }.apply {
                name = "ssh-terminal-write-command"
                isDaemon = true
                start()
            }
        }

        fun sendBytes(bytes: ByteArray) {
            val output = commandOutput
            if (output == null || channel?.isClosed == true) {
                callbacks.notifyConnectionUnavailable()
                return
            }

            Thread {
                runCatching {
                    synchronized(writerLock) {
                        output.write(bytes)
                        output.flush()
                    }
                }.onFailure { error ->
                    callbacks.appendOutput(contextString(R.string.terminal_write_error, error.displayMessage()))
                    callbacks.setTerminalEnabled(false)
                    scheduleReconnect()
                }
            }.apply {
                name = "ssh-terminal-write-bytes"
                isDaemon = true
                start()
            }
        }

        fun close(stopForegroundService: Boolean, releaseLocks: Boolean, notifyDisconnected: Boolean) {
            stopRequested = true
            generation.incrementAndGet()
            reconnectScheduled.set(false)
            callbacks.setTerminalEnabled(false)
            closeCurrentShell(releaseLocks)
            if (stopForegroundService) {
                TerminalForegroundService.stop(request.context)
            }
            if (notifyDisconnected) {
                callbacks.notifyDisconnected()
            }
            callbacks.onClosed(this)
        }

        fun contextString(resId: Int, vararg args: Any): String {
            return request.context.getString(resId, *args)
        }

        private fun readShellOutput(remoteInput: InputStream, currentGeneration: Int) {
            val buffer = ByteArray(4096)
            while (isCurrent(currentGeneration)) {
                val bytesRead = remoteInput.read(buffer)
                if (bytesRead < 0) {
                    break
                }
                callbacks.appendOutputBytes(buffer.copyOf(bytesRead))
            }

            if (isCurrent(currentGeneration)) {
                callbacks.appendOutput(contextString(R.string.terminal_remote_shell_closed))
                callbacks.setTerminalEnabled(false)
                scheduleReconnect()
            }
        }

        private fun startKeepAliveLoop(sshSession: Session, currentGeneration: Int) {
            keepAliveThread = Thread {
                while (isCurrent(currentGeneration) && sshSession.isConnected) {
                    try {
                        Thread.sleep(TERMINAL_KEEP_ALIVE_INTERVAL_MS)
                        if (isCurrent(currentGeneration) && sshSession.isConnected) {
                            sshSession.sendKeepAliveMsg()
                        }
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    } catch (error: Throwable) {
                        if (isCurrent(currentGeneration)) {
                            callbacks.appendOutput(
                                contextString(R.string.terminal_connection_error, error.displayMessage())
                            )
                            callbacks.setTerminalEnabled(false)
                            scheduleReconnect()
                        }
                        return@Thread
                    }
                }
            }.apply {
                name = "ssh-terminal-keepalive"
                isDaemon = true
                start()
            }
        }

        private fun changeInitialDirectory(output: OutputStream) {
            val directory = request.initialDirectory.trim()
            if (directory.isBlank()) {
                return
            }

            synchronized(writerLock) {
                output.write("cd ${directory.toShellPathExpression()}".toByteArray(Charsets.UTF_8))
                output.write(ENTER_KEY_BYTES)
                output.flush()
            }
        }

        private fun scheduleReconnect() {
            if (stopRequested || !reconnectScheduled.compareAndSet(false, true)) {
                return
            }

            val currentGeneration = generation.get()
            if (hasConnected) {
                callbacks.notifyConnectionLost()
            }
            callbacks.appendOutput(contextString(R.string.terminal_reconnecting, TERMINAL_RECONNECT_DELAY_MS / 1000))
            Thread {
                closeCurrentShell(releaseLocks = false)
                closeStarted.set(false)
                try {
                    Thread.sleep(TERMINAL_RECONNECT_DELAY_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    reconnectScheduled.set(false)
                    return@Thread
                }
                reconnectScheduled.set(false)
                if (isCurrent(currentGeneration)) {
                    start()
                }
            }.apply {
                name = "ssh-terminal-reconnect"
                isDaemon = true
                start()
            }
        }

        private fun isSessionActive(): Boolean {
            return session?.isConnected == true && channel?.isConnected == true
        }

        private fun isCurrent(currentGeneration: Int): Boolean {
            return !stopRequested && currentGeneration == generation.get()
        }

        private fun acquireTerminalLocks() {
            if (terminalWakeLock?.isHeld == true && terminalWifiLock?.isHeld == true) {
                return
            }
            val powerManager = request.context.getSystemService(Context.POWER_SERVICE) as PowerManager
            terminalWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${request.context.packageName}:TerminalSshSession"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            val wifiManager = request.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            terminalWifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "${request.context.packageName}:TerminalSshWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        private fun releaseTerminalLocks() {
            terminalWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
            terminalWakeLock = null

            terminalWifiLock?.let { wifiLock ->
                if (wifiLock.isHeld) {
                    wifiLock.release()
                }
            }
            terminalWifiLock = null
        }

        private fun closeCurrentShell(releaseLocks: Boolean) {
            if (!closeStarted.compareAndSet(false, true)) {
                if (releaseLocks) {
                    releaseTerminalLocks()
                }
                return
            }
            connecting = false
            val currentThread = Thread.currentThread()
            keepAliveThread
                ?.takeIf { it != currentThread }
                ?.interrupt()
            keepAliveThread = null
            runCatching {
                commandOutput?.close()
            }
            commandOutput = null
            runCatching {
                channel?.disconnect()
            }
            channel = null
            runCatching {
                session?.disconnect()
            }
            session = null
            if (releaseLocks) {
                releaseTerminalLocks()
            }
        }
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    private const val TERMINAL_COLUMNS = TerminalScreenBuffer.DEFAULT_COLUMNS
    private const val TERMINAL_ROWS = TerminalScreenBuffer.DEFAULT_ROWS
    private const val ENTER_KEY = "\r"
    private const val ENTER_KEY_DELAY_MS = 60L
    private const val TERMINAL_RENDER_DELAY_MS = 50L
    private const val TERMINAL_KEEP_ALIVE_INTERVAL_MS = 10_000L
    private const val TERMINAL_RECONNECT_DELAY_MS = 5_000L
    private val ENTER_KEY_BYTES = ENTER_KEY.toByteArray(Charsets.UTF_8)
    private val CONTROL_U_BYTES = byteArrayOf(0x15)
}
