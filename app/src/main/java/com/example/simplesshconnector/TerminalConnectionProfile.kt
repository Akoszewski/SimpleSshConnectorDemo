package com.example.simplesshconnector

data class TerminalConnectionProfile(
    val serverConfig: SshServerConfig,
    val initialDirectory: String
)
