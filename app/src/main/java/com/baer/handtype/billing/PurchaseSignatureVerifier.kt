package com.baer.handtype.billing

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifies Play's SHA1withRSA purchase signature against the app's Play license public key. */
object PurchaseSignatureVerifier {
    /** Public key from Play Console > Monetarisierung einrichten > Lizenzierung. Public by design. */
    const val PLAY_LICENSE_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxmNbDihydVJcPnO1UnIWjirYGd5PMNvIIG0QQz+Wk8ShWr4Tr73/vlxYj17OTDr/cM/TIX2ZNQo02YiUn1Ldw5zkFypcxlD2hpzy00UtDsJSmZ0tWxET6qdFCSLeNnwVYCSIEYjIJbmDrVavGLsZWhrbz8PpxzE3VY1gSZ8WswJrB0ajUeKAsPhnv6ILsrbsjRPTYrY4zASUg1UEmHKHpsPHXzeH36Ry0u2cPQrA+V5WpVMFheGGsZJJh3sSqzDIElV9ZKqXn9rEdBBgApsD7AOLR3KSl38WUhP3Ai2eF2XPynsiwlkIkqe9WjGv33kBVMbIhHuNxkdUZGzwkdagdwIDAQAB"

    fun verify(base64PublicKey: String, signedData: String, base64Signature: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)))
        Signature.getInstance("SHA1withRSA").run {
            initVerify(key)
            update(signedData.toByteArray())
            verify(Base64.getDecoder().decode(base64Signature))
        }
    }.getOrDefault(false)
}
