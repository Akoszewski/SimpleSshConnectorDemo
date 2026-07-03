package com.example.simplesshconnector

data class SshServerConfig(
    val address: String,
    val privateKey: SshPrivateKey,
    val remoteApkPath: String,
    val terminalStartPath: String
) {
    val target: SshTarget
        get() = SshTargetParser.parse(address)

    fun hasTerminalConnectionInfo(): Boolean {
        return address.isNotBlank() && privateKey.isNotBlank()
    }

    fun hasRemoteFileConnectionInfo(): Boolean {
        return hasTerminalConnectionInfo() && remoteApkPath.isNotBlank()
    }

    fun terminalProfile(): TerminalConnectionProfile {
        return TerminalConnectionProfile(serverConfig = this, initialDirectory = terminalStartPath)
    }
}
