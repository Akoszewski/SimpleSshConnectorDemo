package com.example.simplesshconnector

data class SshPrivateKey(val pem: String) {
    fun isBlank(): Boolean = pem.isBlank()

    fun isNotBlank(): Boolean = pem.isNotBlank()

    fun toByteArray(): ByteArray = pem.toByteArray(Charsets.UTF_8)

    companion object {
        val Empty = SshPrivateKey("")
    }
}
