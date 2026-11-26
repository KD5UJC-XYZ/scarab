// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem}

/** The writer of a KES key file's text as it was before the key files were written as bytes (the
  * branch point of #1418's key-file work, 3a1cdcbfd), kept in test scope with its own type literal
  * and taking the payload as bytes: the reference of `KeyFilesBytesSuite`, which checks that the
  * node writes the same bytes from a key that the replaced key implementation evolved alongside,
  * and the maker of the envelopes the suites install.
  */
object LegacyKeyFiles:
  /** The envelope type the previous writer wrote, its own literal: a change of the node's constant
    * cannot move both sides of the comparison.
    */
  private val KesSigningKeyType = "KesSigningKey_ed25519_kes_2^6"

  /** The text of a KES key file: the cardano-cli envelope around the 608-byte payload `payload`. */
  def kesEnvelope(file: String, payload: Bytes): Either[ForgeError, String] =
    Cbor
      .encode(Value.ByteString(payload))
      .leftMap(e => ForgeError.KeyFile(file, ForgeKeyProblem.Cbor(e)))
      .map(cbor =>
        Json
          .obj(
            "type" -> KesSigningKeyType.asJson,
            "description" -> "KES Signing Key".asJson,
            "cborHex" -> cbor.hex.asJson
          )
          .spaces2
      )

  /** [[kesEnvelope]] of the key's payload at its current period: the maker of the envelopes the
    * suites install.
    */
  def kesEnvelope(file: String, key: Sum6KesSigning.SigningKey): Either[ForgeError, String] =
    kesEnvelope(file, key.toBytes)
