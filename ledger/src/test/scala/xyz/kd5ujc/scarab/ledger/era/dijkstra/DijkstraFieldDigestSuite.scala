// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CborRead, LeiosBlock}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12

import DijkstraCorpus.{hex, str}

/** The fields the era decoder reads of every Dijkstra transaction the repository holds, against
  * those the flat block decoder and its projection read before they were deleted
  * (`fixtures/dijkstra-flat-fields`, #85): the outputs, the certificates, the proposals and the
  * metadata of each transaction, at its top level and in each sub-transaction, by their digests
  * ([[DijkstraFieldDigests]]). The transactions are those [[DijkstraOnePassSuite]] reads, the block
  * forms by [[DijkstraTx.inBlock]] and the mempool forms by [[DijkstraTx.fromSubmission]].
  */
class DijkstraFieldDigestSuite extends FunSuite:
  private def gunzipped(path: String): Vector[Vector[String]] =
    new String(
      new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(Path.of(path))))
        .readAllBytes(),
      StandardCharsets.UTF_8
    ).linesIterator.map(_.split('\t').toVector).toVector

  private def blockTxs(raw: Bytes): Vector[Bytes] =
    if LeiosBlock.decode(raw).isRight then DijkstraCorpus.blockItems(raw) else Vector.empty

  private lazy val read: Vector[DijkstraTx[xyz.kd5ujc.scarab.ledger.era.TxLevel.Top]] =
    val corpus = DijkstraCorpus.leiosCorpus
    val rules = DijkstraCorpus.jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
    val blockForms: Vector[Bytes] =
      DijkstraCorpus.testnetBlocks.flatMap((_, r) => blockTxs(r)) ++
        DijkstraCorpus.devnetBlocks.flatMap((_, r) => blockTxs(r)) ++
        gunzipped("fixtures/leios/testnet/eb-1455869.tsv.gz").collect { case Vector("tx", _, t) =>
          hex(t)
        } ++
        corpus.filter(j => str(j, "kind") == "tx").map(j => hex(str(j, "bytes"))) ++
        corpus
          .filter(j => str(j, "kind") == "block")
          .flatMap(j => blockTxs(hex(str(j, "bytes")))) ++
        DijkstraCorpus.contextCases.map(c => hex(str(c, "bytes"))) ++
        DijkstraCorpus.chainContextCases.map(c => hex(str(c, "bytes"))) ++
        DijkstraCorpus
          .json("fixtures/reference/leios-testnet-context-refusals/cases.json")
          .hcursor
          .get[Vector[io.circe.Json]]("cases")
          .fold(e => fail(e.message), identity)
          .map(c => hex(str(c, "bytes"))) ++
        DijkstraCorpus.utxowCases.map(j => hex(str(j, "tx"))) ++
        rules.filter(j => str(j, "kind") == "entities").map(j => hex(str(j, "tx"))) ++
        rules.filter(j => str(j, "kind") == "bbody").flatMap(j => blockTxs(hex(str(j, "block"))))
    blockForms.flatMap(raw =>
      CborRead.decode(raw, V12.nestedLimits).toOption.flatMap(DijkstraTx.inBlock(_).toOption)
    ) ++ (DijkstraCorpus.devnetSubmitted +: DijkstraCorpus.crossingForms).flatMap(raw =>
      DijkstraTx.fromSubmission(raw).toOption
    )

  /** The recorded digests, by the first 16 hex digits of the transaction's id. */
  private lazy val recorded: Map[String, Vector[String]] =
    DijkstraCorpus
      .jsonLines("fixtures/dijkstra-flat-fields/fields.jsonl")
      .map(j => str(j, "id") -> Vector("o", "c", "p", "m").map(str(j, _)))
      .toMap

  test("every transaction's outputs, certificates, proposals and metadata are the flat decoder's") {
    val ours = read.distinctBy(_.id).map(t => t.id.hex.take(16) -> DijkstraFieldDigests.of(t)).toMap
    // the same transactions, 911, and each the same fields
    assertEquals(ours.keySet, recorded.keySet)
    assertEquals(ours.size, 911)
    ours.foreach((id, fields) => assertEquals(fields, recorded(id), s"transaction $id"))
  }

  test("the recorded fields cover every kind, at both levels") {
    val held = recorded.values.toVector
    assertEquals(
      Vector(1, 2, 3).map(i => held.count(_(i) != "-")),
      Vector(80, 83, 206)
    )
    assert(
      read.exists(t => EraTxBodyLevels.subTransactions(t).nonEmpty),
      "no transaction with sub-transactions"
    )
  }

  private object EraTxBodyLevels:
    def subTransactions(t: DijkstraTx[xyz.kd5ujc.scarab.ledger.era.TxLevel.Top]) =
      xyz.kd5ujc.scarab.ledger.era.EraTxBody.dijkstra.subTransactions(t.body).toVector
