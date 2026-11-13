package com.example.ipcamapp

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAuthTest {
    @Test fun rfc2617Example() {
        // RFC 2617 3.5 példa, qop nélküli változat ellenőrzése a válaszformára
        val d = DigestAuth.parse("Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")!!
        val h = d.header("GET", "/dir/index.html", "Mufasa", "Circle Of Life")
        assertTrue(h, h.contains("response=\"670fd8c2df070c60b045671b8b24ff02\""))
        assertTrue(h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test fun basicIsNotDigest() = assertNull(DigestAuth.parse("Basic realm=\"x\""))

    @Test fun qopAuthAddsCounter() {
        val d = DigestAuth.parse("Digest realm=\"r\", nonce=\"n\", qop=\"auth,auth-int\"")!!
        assertTrue(d.header("GET", "/", "u", "p").contains("nc=00000001"))
        assertTrue(d.header("GET", "/", "u", "p").contains("nc=00000002"))
    }
}
