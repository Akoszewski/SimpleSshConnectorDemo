package com.example.simplesshconnector

import com.jcraft.jsch.JSch
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyGeneratorTest {
    @Test
    fun generateCreatesEd25519OpenSshKeyPair() {
        val keyPair = SshKeyGenerator.generate()

        assertTrue(keyPair.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(keyPair.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(keyPair.publicKeyOpenSsh.endsWith(" ssh-apk-downloader"))

        JschEd25519Support.configure()
        JSch().addIdentity(
            "generated-test-key",
            keyPair.privateKeyPem.toByteArray(Charsets.UTF_8),
            null,
            null
        )
    }
}
