// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes

/** `Data.ByteString.Base64.decode` of base64-bytestring 1.2.1.0, the standard alphabet: the text's
  * length a multiple of four, at most two `=` and only at the end, and the bits the padding leaves
  * zero (a non-canonical encoding is refused). A Byron genesis reads a delegation key with it
  * (`parseFullVerificationKey`, `Cardano/Crypto/Signing/VerificationKey.hs:137-140`,
  * cardano-crypto-wrapper 1.7.0.0) and an AVVM key after `fromAvvmVK` swaps the URL alphabet.
  * Measured with `scripts/oracle/byron-genesis-balances` (#729) and
  * `scripts/oracle/byron-genesis-data` (#734).
  */
object StrictBase64:
  private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** The bytes of `text`, or `None` where `decode` refuses it. */
  def decode(text: String): Option[Bytes] =
    val body = text.reverse.dropWhile(_ == '=').reverse
    val pads = text.length - body.length
    val leftover = if pads == 1 then 0x03 else if pads == 2 then 0x0f else 0
    val canonical =
      text.length % 4 == 0 && pads <= 2 && body.forall(Alphabet.contains(_)) &&
        body.lastOption.forall(last => (Alphabet.indexOf(last.toInt) & leftover) == 0)
    Option
      .when(canonical)(text)
      .flatMap(t => Either.catchNonFatal(java.util.Base64.getDecoder.decode(t)).toOption)
      .map(Bytes.fromArray)
