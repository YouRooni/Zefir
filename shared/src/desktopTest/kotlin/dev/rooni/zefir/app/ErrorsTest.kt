package dev.rooni.zefir.app

import dev.rooni.zefir.res.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorsTest {
    @Test
    fun recognizesCommonCoreErrors() {
        assertEquals(Res.string.err_dns, friendlyError("dial tcp: lookup vpn.example: no such host"))
        assertEquals(Res.string.err_tls, friendlyError("x509: certificate has expired"))
        assertEquals(Res.string.err_vpn, friendlyError("establish rejected"))
        assertEquals(Res.string.err_config, friendlyError("yaml: line 3: mapping values are not allowed"))
        assertEquals(Res.string.err_timeout, friendlyError("i/o timeout"))
        assertEquals(Res.string.err_network, friendlyError("connect: connection refused"))
        assertEquals(Res.string.err_generic, friendlyError("something odd"))
        assertEquals(Res.string.err_generic, friendlyError(null))
    }
}
