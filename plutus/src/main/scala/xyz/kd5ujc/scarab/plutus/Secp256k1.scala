// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.crypto.{NativeSecp256k1, Secp256k1Context}

/** The meaning of the Plutus builtin `verifyEcdsaSecp256k1Signature` as the reference defines it
  * (`PlutusCore.Crypto.Secp256k1` over `Cardano.Crypto.DSIGN.EcdsaSecp256k1` and libsecp256k1):
  *
  *   - the key is 33 bytes and parses as a compressed point (`secp256k1_ec_pubkey_parse`), the
  *     message is 32 bytes, the signature is 64 bytes and parses as compact `r || s`
  *     (`secp256k1_ecdsa_signature_parse_compact`, which fails only when `r` or `s` is at least the
  *     group order `n`): otherwise the builtin fails;
  *   - everything else is a verdict, `False` or `True`, from `secp256k1_ecdsa_verify`: it is
  *     `False` for `r = 0` or `s = 0` (they parse), and for a high `s` (above `n / 2`), because
  *     libsecp256k1 verifies only normalised signatures and Plutus does not normalise.
  *
  * Scalus 1.2.0 and 1.3.0 fail on `r = 0` or `s = 0` where the reference answers `False`; the
  * preprod transaction 97b31e35 (block 3,498,869) passes an all-zero signature and the chain
  * accepts the answer `False`. Failures are `IllegalArgumentException`, like Scalus's.
  */
object Secp256k1:
  /** The order `n` of the secp256k1 group. */
  val Order: BigInt =
    BigInt("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

  private val HalfOrder: BigInt = Order >> 1

  /** The builtin; throws `IllegalArgumentException` where the reference fails. */
  def verifyEcdsa(pk: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
    require(Secp256k1Context.isEnabled, "secp256k1 native library not available")
    require(pk.length == 33 && NativeSecp256k1.isValidPubKey(pk), "Invalid verification key.")
    require(sig.length == 64, "Invalid signature.")
    val r = BigInt(1, sig.slice(0, 32))
    val s = BigInt(1, sig.slice(32, 64))
    require(r < Order && s < Order, "Invalid signature.")
    require(msg.length == 32, "Invalid message hash.")
    if r.signum == 0 || s.signum == 0 || s > HalfOrder then false
    else NativeSecp256k1.ecdsaVerify(msg, sig, pk)
