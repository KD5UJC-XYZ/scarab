// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** One profile commitment equality check; neither script execution nor ledger validation. */
object ProfileIntegrity:
  final case class Evidence(
      redeemers: Bytes,
      datums: Bytes,
      languageView: Bytes,
      preimage: Bytes,
      digest: Bytes
  )
  final case class Result(supplied: Option[Bytes], evidence: Evidence):
    def matches: Boolean = supplied.contains(evidence.digest)
  private def sha(bytes: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(bytes.toArray).map(b => f"${b & 255}%02x").mkString
  private def node(v: Value): Node = Node(v, Bytes.empty)

  // The profile authenticates the full parameter blob separately. This authenticates its
  // existing extracted model and re-encodes only the language-view domain, never redeemers.
  private[vm] def languageView(modelText: String): Either[String, Bytes] =
    if modelText.length > 16384 || sha(
        Bytes.fromArray(modelText.getBytes(UTF_8))
      ) != Pv9Profile.modelSha256
    then Left("unregistered integrity cost model")
    else
      for
        costs <- Pv9Profile.parseValues(modelText)
        encoded <- Cbor.encode(
          Value.Map(
            Vector(
              node(Value.UInt(2)) -> node(
                Value.Arr(
                  costs.map(n =>
                    node(if n < 0 then Value.NInt(BigInt(n)) else Value.UInt(BigInt(n)))
                  )
                )
              )
            )
          )
        )
        _ <- Either.cond(
          encoded.size == 690 && sha(
            encoded
          ) == "ffe1bc3154b74e81cf05b3482361a11b0e23bdc158ac38d64eb62e942969ca1d",
          (),
          "unexpected pinned language view"
        )
      yield encoded

  def check(
      tx: Bytes,
      parameters: Bytes,
      entries: Vector[(Bytes, Bytes)],
      modelText: String
  ): Either[String, Result] =
    for
      admitted <- ProfileTranslator.parseProfile(tx, parameters, entries, true)
      view <- languageView(modelText)
      preimage = Bytes(admitted.redeemerOriginal.value ++ view.value)
      _ <- Either.cond(preimage.size <= 69632, (), "integrity preimage bound")
      evidence = Evidence(
        admitted.redeemerOriginal,
        Bytes.empty,
        view,
        preimage,
        Blake2b.hash256.hash(preimage)
      )
    yield Result(admitted.suppliedCommitment, evidence)
