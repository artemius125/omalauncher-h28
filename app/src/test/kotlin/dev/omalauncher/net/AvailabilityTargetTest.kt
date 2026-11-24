package dev.omalauncher.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvailabilityTargetTest {
    @Test fun acceptsIpAndDnsNames() {
        for (host in listOf("192.168.0.17", "2001:db8::1", "imac.local", "example.org")) {
            assertEquals(AvailabilityTarget.Host(host), AvailabilityTarget.parse("  $host  "))
        }
        assertEquals(AvailabilityTarget.Host("::1"), AvailabilityTarget.parse("[::1]"))
        assertEquals(AvailabilityTarget.Host("xn--e1afmkfd.xn--p1ai"), AvailabilityTarget.parse("пример.рф"))
    }

    @Test fun acceptsHttpsHealthUrls() {
        val url = "https://example.org:8443/health?probe=oma"
        assertEquals(AvailabilityTarget.Https(url), AvailabilityTarget.parse(url))
        assertEquals(AvailabilityTarget.Https("https://[::1]/health"), AvailabilityTarget.parse("HTTPS://[::1]/health"))
    }

    @Test fun rejectsMalformedOrUnsupportedAddresses() {
        for (text in listOf(
            "", "https://", "https://example.org:", "https://example.org:65536", "http://example.org",
            "https://user@example.org", "https://example.org#fragment", "example.org:443", "example.org/health",
            "999.1.2.3", "1.2.3", "invalid host", "[::1]@example.org",
        )) {
            assertNull(text, AvailabilityTarget.parse(text))
        }
    }
}
