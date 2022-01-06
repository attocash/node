package org.atto.commons

import com.rfksystems.blake2b.Blake2b
import com.rfksystems.blake2b.security.Blake2bProvider
import net.i2p.crypto.eddsa.Utils
import net.i2p.crypto.eddsa.math.Curve
import net.i2p.crypto.eddsa.math.Field
import net.i2p.crypto.eddsa.math.ed25519.Ed25519LittleEndianEncoding
import net.i2p.crypto.eddsa.math.ed25519.Ed25519ScalarOps
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import java.security.Security

internal object ED25519 {
    val ED25519_BLAKE2B_CURVES_PEC: EdDSANamedCurveSpec

    init {
        Security.addProvider(Blake2bProvider())
        val ED25519_FIELD = Field(
            256,  // b
            Utils.hexToBytes("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),  // q
            Ed25519LittleEndianEncoding()
        )
        val ED25519_CURVE = Curve(
            ED25519_FIELD,
            Utils.hexToBytes("a3785913ca4deb75abd841414d0a700098e879777940c78c73fe6f2bee6c0352"),  // d
            ED25519_FIELD.fromByteArray(Utils.hexToBytes("b0a00e4a271beec478e42fad0618432fa7d7fb3d99004d2b0bdfc14f8024832b"))
        ) // I
        ED25519_BLAKE2B_CURVES_PEC = EdDSANamedCurveSpec(
            EdDSANamedCurveTable.ED_25519,
            ED25519_CURVE,
            Blake2b.BLAKE2_B_512,  // H
            Ed25519ScalarOps(),  // l
            ED25519_CURVE.createPoint( // B
                Utils.hexToBytes("5866666666666666666666666666666666666666666666666666666666666666"),
                true
            )
        ) // Precompute tables for B
        EdDSANamedCurveTable.defineCurve(ED25519_BLAKE2B_CURVES_PEC)
    }

}