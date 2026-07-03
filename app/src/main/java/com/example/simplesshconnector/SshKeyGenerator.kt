package com.example.simplesshconnector

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

object SshKeyGenerator {
    private const val KEY_COMMENT = "ssh-apk-downloader"

    fun generate(): GeneratedSshKeyPair {
        JschEd25519Support.configure()

        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519).apply {
            publicKeyComment = KEY_COMMENT
        }

        return try {
            GeneratedSshKeyPair(
                privateKeyPem = keyPair.toOpenSshPrivateKey(),
                publicKeyOpenSsh = keyPair.toOpenSshPublicKey()
            )
        } finally {
            keyPair.dispose()
        }
    }

    private fun KeyPair.toOpenSshPrivateKey(): String {
        val output = ByteArrayOutputStream()
        writeOpenSSHv1PrivateKey(output, null as ByteArray?)
        return output.toString(Charsets.UTF_8.name()).trimEnd()
    }

    private fun KeyPair.toOpenSshPublicKey(): String {
        val output = ByteArrayOutputStream()
        writePublicKey(output, KEY_COMMENT)
        return output.toString(Charsets.UTF_8.name()).trimEnd()
    }
}
