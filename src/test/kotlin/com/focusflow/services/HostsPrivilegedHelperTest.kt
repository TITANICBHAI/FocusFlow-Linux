package com.focusflow.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostsPrivilegedHelperTest {

    @Test
    fun `safe domain validation rejects shell metacharacters`() {
        assertTrue(HostsBlocker.isSafeDomain("example.com"))
        assertTrue(HostsBlocker.isSafeDomain("www.example.com"))
        assertFalse(HostsBlocker.isSafeDomain("example.com; touch /tmp/pwned"))
        assertFalse(HostsBlocker.isSafeDomain("../etc/hosts"))
        assertFalse(HostsBlocker.isSafeDomain(""))
    }
}