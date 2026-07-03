package com.example.simplesshconnector

import org.junit.Assert.assertThrows
import org.junit.Test

class ApkNameValidatorTest {
    @Test
    fun requireValidAllowsApkFilename() {
        ApkNameValidator.requireValid("SimpleSshConnector-debug.apk")
    }

    @Test
    fun requireValidRejectsNonApkFilename() {
        assertThrows(IllegalArgumentException::class.java) {
            ApkNameValidator.requireValid("notes.txt")
        }
    }

    @Test
    fun requireValidRejectsPathTraversalWithSlash() {
        assertThrows(IllegalArgumentException::class.java) {
            ApkNameValidator.requireValid("../other.apk")
        }
    }

    @Test
    fun requireValidRejectsPathTraversalWithBackslash() {
        assertThrows(IllegalArgumentException::class.java) {
            ApkNameValidator.requireValid("folder\\other.apk")
        }
    }
}
