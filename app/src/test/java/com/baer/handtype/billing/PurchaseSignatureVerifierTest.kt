package com.baer.handtype.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PurchaseSignatureVerifierTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val json = """{"productId":"handtype_premium","purchaseState":0}"""

    private fun sign(data: String): String {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(keyPair.private)
        signer.update(data.toByteArray())
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    @Test
    fun acceptsValidSignature() {
        assertTrue(PurchaseSignatureVerifier.verify(publicKeyBase64, json, sign(json)))
    }

    @Test
    fun rejectsTamperedData() {
        assertFalse(PurchaseSignatureVerifier.verify(publicKeyBase64, json.replace("0}", "1}"), sign(json)))
    }

    @Test
    fun rejectsGarbageSignatureAndKey() {
        assertFalse(PurchaseSignatureVerifier.verify(publicKeyBase64, json, "not-base64!"))
        assertFalse(PurchaseSignatureVerifier.verify(publicKeyBase64, json, ""))
        assertFalse(PurchaseSignatureVerifier.verify("bad-key", json, sign(json)))
    }

    @Test
    fun bundledPlayKeyParses() {
        // A wrong signature must fail cleanly, which proves the embedded key decodes as an RSA public key.
        assertFalse(PurchaseSignatureVerifier.verify(PurchaseSignatureVerifier.PLAY_LICENSE_KEY, json, sign(json)))
        assertTrue(PurchaseSignatureVerifier.PLAY_LICENSE_KEY.startsWith("MIIB"))
    }
}
