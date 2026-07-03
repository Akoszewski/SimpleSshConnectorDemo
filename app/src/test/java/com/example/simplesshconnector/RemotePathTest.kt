package com.example.simplesshconnector

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathTest {
    @Test
    fun shellPathExpressionLeavesHomeVariableExpandable() {
        assertEquals("\$HOME/'Artifacts/android'", "\$HOME/Artifacts/android/".toShellPathExpression())
    }

    @Test
    fun shellPathExpressionLeavesBracedHomeVariableExpandable() {
        assertEquals("\$HOME/'Artifacts/android'", "\${HOME}/Artifacts/android/".toShellPathExpression())
    }

    @Test
    fun shellPathExpressionQuotesHomeRelativePathAfterExpandablePrefix() {
        assertEquals("\$HOME/'My Folder/android'", "\$HOME/My Folder/android/".toShellPathExpression())
    }

    @Test
    fun sftpDirectoryTreatsHomeVariableAsRemoteHome() {
        assertEquals("Artifacts/android", "\$HOME/Artifacts/android/".toSftpDirectory())
    }

    @Test
    fun sftpDirectoryTreatsBracedHomeVariableAsRemoteHome() {
        assertEquals("Artifacts/android", "\${HOME}/Artifacts/android/".toSftpDirectory())
    }

    @Test
    fun resolvedDirectoryCommandAsksServerForCanonicalPath() {
        assertEquals("cd \$HOME/'Artifacts/android' && pwd -P", "\$HOME/Artifacts/android/".toResolvedDirectoryCommand())
    }

    @Test
    fun osInterpretedPathDisplayExpandsHomeDirectory() {
        assertEquals("\$HOME/Artifacts/android", "~/Artifacts/android/".toOsInterpretedPathDisplay())
    }

    @Test
    fun osInterpretedPathDisplayNormalizesBracedHomeDirectory() {
        assertEquals("\$HOME/Artifacts/android", "\${HOME}/Artifacts/android/".toOsInterpretedPathDisplay())
    }

    @Test
    fun osInterpretedPathDisplayKeepsAbsolutePath() {
        assertEquals("/srv/shared", "/srv/shared/".toOsInterpretedPathDisplay())
    }
}
