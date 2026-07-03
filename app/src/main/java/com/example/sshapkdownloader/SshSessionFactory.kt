package com.example.sshapkdownloader

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

object SshSessionFactory {
    fun create(config: SshServerConfig): Session {
        return create(config.target, config.privateKey)
    }

    fun create(target: SshTarget, privateKey: SshPrivateKey): Session {
        JschEd25519Support.configure()

        val jsch = JSch()
        jsch.addIdentity(
            "ssh-apk-downloader-key",
            privateKey.toByteArray(),
            null,
            null
        )

        return jsch.getSession(target.username, target.host, target.port).apply {
            setConfig("StrictHostKeyChecking", "no")
            setConfig("TCPKeepAlive", "yes")
            setServerAliveInterval(10_000)
            setServerAliveCountMax(6)
        }
    }
}
