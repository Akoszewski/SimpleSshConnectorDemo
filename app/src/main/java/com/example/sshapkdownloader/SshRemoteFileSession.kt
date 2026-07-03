package com.example.sshapkdownloader

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

class SshRemoteFileSession private constructor(
    private val session: Session,
    private val remoteDirectory: String
) : Closeable {
    fun listFiles(): List<String> {
        val command = "find ${remoteDirectory.toShellPathExpression()} -maxdepth 1 -type f -printf '%f\\n' | sort"
        return executeRemoteCommand(command)
            .lineSequence()
            .map { it.removeSuffix("\r") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun downloadFile(fileName: String, output: OutputStream) {
        RemoteFileNameValidator.requireValid(fileName)
        withSftpChannel { channel ->
            channel.get(fileName, output)
        }
    }

    fun deleteFile(fileName: String) {
        RemoteFileNameValidator.requireValid(fileName)
        withSftpChannel { channel ->
            channel.rm(fileName)
        }
    }

    fun uploadFile(input: InputStream, fileName: String, validateFileName: Boolean = true) {
        if (validateFileName) {
            RemoteFileNameValidator.requireValid(fileName)
        }
        withSftpChannel { channel ->
            channel.put(input, fileName)
        }
    }

    override fun close() {
        session.disconnect()
    }

    private fun executeRemoteCommand(command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()

        channel.setCommand(command)
        channel.outputStream = output
        channel.setErrStream(errorOutput)
        channel.connect(CONNECTION_TIMEOUT_MS)

        while (!channel.isClosed) {
            Thread.sleep(CHANNEL_POLL_DELAY_MS)
        }

        val exitStatus = channel.exitStatus
        channel.disconnect()

        if (exitStatus != 0) {
            val message = errorOutput.toString(Charsets.UTF_8.name()).ifBlank {
                "Remote command failed with exit status $exitStatus"
            }
            error(message)
        }

        return output.toString(Charsets.UTF_8.name())
    }

    private fun <T> withSftpChannel(action: (ChannelSftp) -> T): T {
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(CONNECTION_TIMEOUT_MS)
        try {
            channel.cd(remoteDirectory.toSftpDirectory())
            return action(channel)
        } finally {
            channel.disconnect()
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000
        private const val CHANNEL_POLL_DELAY_MS = 100L

        fun connect(config: SshServerConfig): SshRemoteFileSession {
            return connect(config, config.remoteApkPath)
        }

        fun connect(config: SshServerConfig, remoteDirectory: String): SshRemoteFileSession {
            val session = SshSessionFactory.create(config)
            try {
                session.connect(CONNECTION_TIMEOUT_MS)
            } catch (error: Throwable) {
                session.disconnect()
                throw error
            }
            return SshRemoteFileSession(session, remoteDirectory)
        }

        fun connect(address: String, privateKey: SshPrivateKey, remoteDirectory: String): SshRemoteFileSession {
            return connect(
                SshServerConfig(
                    address = address,
                    privateKey = privateKey,
                    remoteApkPath = remoteDirectory,
                    terminalStartPath = ""
                ),
                remoteDirectory
            )
        }
    }
}
