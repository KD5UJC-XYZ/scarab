// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraTxBody, TxLevel}

/** The digests of a Dijkstra transaction's outputs, certificates, proposals and metadata, at the
  * top level and in each sub-transaction, in order: the first twelve hex digits of the SHA-256 of
  * each field's canonical form ([[DijkstraOnePassExpected.canonical]]: an output by its bytes and
  * fields, a certificate by its constructors and fields, a proposal as the governance reader read
  * it, metadata by label), `-` for a field empty at every level. The decoders' readings are held to
  * those the flat block decoder and its projection gave (`fixtures/dijkstra-flat-fields`, #85).
  */
object DijkstraFieldDigests:
  private val b = EraTxBody.dijkstra

  private def digest(xs: Vector[Any]): String =
    if xs.isEmpty then "-"
    else
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(
          DijkstraOnePassExpected
            .canonical(xs)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
        .take(6)
        .map(x => f"${x & 0xff}%02x")
        .mkString

  // each level's values, a level's own list and the sub-transactions' after it
  private def levels(tx: DijkstraTx[TxLevel.Top])(
      top: DijkstraTx[TxLevel.Top] => Vector[Any],
      sub: DijkstraTx[TxLevel.Sub] => Vector[Any]
  ): Vector[Any] =
    Vector(top(tx)) ++ b.subTransactions(tx.body).toVector.map(sub)

  private def metadata(aux: Option[DijkstraTxAuxData]): Vector[Any] =
    aux.toVector.flatMap(_.metadata.toVector.map((k, v) => (k.value, v)))

  /** The four digests of `tx`: outputs, certificates, proposals, metadata. */
  def of(tx: DijkstraTx[TxLevel.Top]): Vector[String] =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    val subLevel = BothLevels.Sub
    val outputs = levels(tx)(
      t => b.allSizedOutputs[TxLevel.Top](t.body).map(o => (o.size, o.value.rep)),
      s => b.outputs[TxLevel.Sub](s.body)(using subLevel).map(_.rep)
    )
    val certs = levels(tx)(
      t => b.certs[TxLevel.Top](t.body),
      s => b.certs[TxLevel.Sub](s.body)(using subLevel)
    )
    val proposals = levels(tx)(
      t => b.proposalProcedures[TxLevel.Top](t.body).toVector.map(_.read),
      s => b.proposalProcedures[TxLevel.Sub](s.body)(using subLevel).toVector.map(_.read)
    )
    val meta = levels(tx)(t => metadata(t.auxData), s => metadata(s.auxData))
    def empty(xs: Vector[Any]) = xs.forall {
      case v: Vector[?] => v.isEmpty
      case _            => false
    }
    Vector(outputs, certs, proposals, meta).map(xs => if empty(xs) then "-" else digest(xs))
