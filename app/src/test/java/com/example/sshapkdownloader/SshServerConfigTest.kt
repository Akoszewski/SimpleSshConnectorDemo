package com.example.sshapkdownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SshServerConfigTest {
    @Test
    fun targetParsesConfiguredAddress() {
        val config = serverConfig(address = "alice@example.com:2222")

        assertEquals(SshTarget("alice", "example.com", 2222), config.target)
    }

    @Test
    fun terminalConnectionInfoRequiresAddressAndPrivateKey() {
        assertTrue(serverConfig(address = "alice@example.com", privateKey = "key").hasTerminalConnectionInfo())
        assertFalse(serverConfig(address = "", privateKey = "key").hasTerminalConnectionInfo())
        assertFalse(serverConfig(address = "alice@example.com", privateKey = "").hasTerminalConnectionInfo())
    }

    @Test
    fun remoteFileConnectionInfoAlsoRequiresRemoteApkPath() {
        assertTrue(
            serverConfig(
                address = "alice@example.com",
                privateKey = "key",
                remoteApkPath = "~/Artifacts/android/"
            ).hasRemoteFileConnectionInfo()
        )
        assertFalse(
            serverConfig(
                address = "alice@example.com",
                privateKey = "key",
                remoteApkPath = ""
            ).hasRemoteFileConnectionInfo()
        )
    }

    @Test
    fun terminalProfileKeepsServerConfigAndStartDirectory() {
        val config = serverConfig(terminalStartPath = "~/project")

        val profile = config.terminalProfile()

        assertSame(config, profile.serverConfig)
        assertEquals("~/project", profile.initialDirectory)
    }

    private fun serverConfig(
        address: String = "alice@example.com",
        privateKey: String = "key",
        remoteApkPath: String = "~/Artifacts/android/",
        terminalStartPath: String = ""
    ): SshServerConfig {
        return SshServerConfig(
            address = address,
            privateKey = SshPrivateKey(privateKey),
            remoteApkPath = remoteApkPath,
            terminalStartPath = terminalStartPath
        )
    }
}
