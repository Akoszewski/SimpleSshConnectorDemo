package com.example.sshapkdownloader

data class TerminalConnectionProfile(
    val serverConfig: SshServerConfig,
    val initialDirectory: String
)
