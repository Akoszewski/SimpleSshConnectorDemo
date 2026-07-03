package com.example.simplesshconnector

fun String.toShellPathExpression(): String {
    val path = trim().trimTrailingSlashes()
    val homeRelativePath = path.homeRelativePath()
    return when {
        homeRelativePath == "" -> "\$HOME"
        homeRelativePath != null -> "\$HOME/${homeRelativePath.shellQuote()}"
        else -> path.shellQuote()
    }
}

fun String.toSftpDirectory(): String {
    val path = trim().trimTrailingSlashes()
    val homeRelativePath = path.homeRelativePath()
    return when {
        homeRelativePath == "" -> "."
        homeRelativePath != null -> homeRelativePath
        else -> path
    }
}

fun String.toResolvedDirectoryCommand(): String {
    return "cd ${toShellPathExpression()} && pwd -P"
}

fun String.toOsInterpretedPathDisplay(): String {
    val path = trim().trimTrailingSlashes()
    val homeRelativePath = path.homeRelativePath()
    return when {
        homeRelativePath == "" -> "\$HOME"
        homeRelativePath != null -> "\$HOME/$homeRelativePath"
        else -> path
    }
}

private fun String.homeRelativePath(): String? {
    return when {
        this == "~" || this == "\$HOME" || this == "\${HOME}" -> ""
        startsWith("~/") -> removePrefix("~/")
        startsWith("\$HOME/") -> removePrefix("\$HOME/")
        startsWith("\${HOME}/") -> removePrefix("\${HOME}/")
        else -> null
    }
}

private fun String.shellQuote(): String {
    return "'${replace("'", "'\"'\"'")}'"
}

private fun String.trimTrailingSlashes(): String {
    return if (length > 1) trimEnd('/').ifEmpty { "/" } else this
}
