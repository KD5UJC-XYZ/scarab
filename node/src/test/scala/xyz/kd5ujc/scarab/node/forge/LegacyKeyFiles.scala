// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem}

/** The writer of a KES key file's text as it was before the key files were written as bytes (the
  * branch point of #1418's key-file work, 3a1cdcbfd), kept unchanged in test scope but for its
  * object: the reference of `KeyFilesBytesSuite`, which checks that the node writes the same bytes,
  * and the maker of the envelopes the suites install.
  */
object LegacyKeyFiles:
  /** The text of a KES key file: the cardano-cli envelope around the key at its current period. */
  def kesEnvelope(file: String, key: Sum6KesSigning.SigningKey): Either[ForgeError, String] =
    Cbor
      .encode(Value.ByteString(key.toBytes))
      .leftMap(e => ForgeError.KeyFile(file, ForgeKeyProblem.Cbor(e)))
      .map(cbor =>
        Json
          .obj(
            "type" -> KeyFiles.KesSigningKeyType.asJson,
            "description" -> "KES Signing Key".asJson,
            "cborHex" -> cbor.hex.asJson
          )
          .spaces2
      )
