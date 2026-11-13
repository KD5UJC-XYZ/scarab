// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraWitnesses
import xyz.kd5ujc.scarab.protocol.Era

/** A bootstrap witness's chain code of any length. The reference reads the chain code as a byte
  * array and checks its length only from decoder version 12 (`instance DecCBOR ChainCode`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:72-78`, cardano-ledger f649f975),
  * and the blocks and transactions of Shelley to Conway are read at their eras' `eraProtVerLow`, 2
  * to 9. `bootstrapWitKeyHash` (`Bootstrap.hs:112-146`) hashes the chain code as it is.
  *
  * `fixtures/reference/bootstrap-chain-codes/cases.jsonl` holds witness sets, whole transactions
  * and block bodies of each era from Shelley to Conway with a bootstrap witness whose chain code is
  * 0, 31, 32, 33 or 64 bytes, a witness set with two witnesses that differ only in their chain
  * codes, and a witness read alone at each decoder version from 2 to 12, with what the unchanged
  * released decoders did with each and the address roots they computed. Scarab's decoders, the ones
  * a block's transactions and a submission go through, must give the same verdict, keep the chain
  * code as written and compute the same address root.
  */
class BootstrapChainCodesReferenceSuite extends FunSuite:
  import BootstrapChainCodesReferenceSuite.*

  private val rows: Vector[Row] = load.fold(e => fail(e.toString), identity)

  test("the corpus covers each era, kind and length, and each decoder version from 2 to 12") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for
      era <- Shelley
      kind <- Vector(Kind.Witnesses, Kind.Transaction, Kind.BlockBody)
    do
      assertEquals(
        rows
          .filter(r => r.era.contains(era) && r.kind == kind && r.lengths.size == 1)
          .map(_.lengths)
          .toSet,
        Lengths.map(Vector(_)).toSet,
        s"$era $kind"
      )
    assertEquals(
      rows.filter(_.lengths.size == 2).map(_.lengths).toSet,
      Set(Vector(32, 33), Vector(31, 33)),
      "two witnesses that differ in their chain codes"
    )
    for version <- 2 to 12 do
      assertEquals(
        rows.filter(r => r.kind == Kind.Alone && r.version == version).map(_.lengths).toSet,
        Lengths.map(Vector(_)).toSet,
        s"version $version"
      )
  }

  test("the reference reads a chain code of any length below version 12, and of 32 bytes from 12") {
    for row <- rows do
      assertEquals(row.accepted, row.version < 12 || row.lengths.forall(_ == 32), row.name)
      if row.accepted && row.kind != Kind.BlockBody then
        assertEquals(row.keyHashes.size, row.lengths.size, s"${row.name}: the address roots")
      row.failure.foreach(f => assert(f.contains(Rule), s"${row.name}: $f"))
  }

  for row <- rows if readable(row) do
    test(row.name) {
      (row.accepted, scarab(row)) match
        case (true, Right(witnesses)) =>
          assertEquals(witnesses.map(_.chainCode), row.lengths.map(chainCode), "the chain codes")
          if row.kind != Kind.BlockBody then
            assertEquals(
              witnesses.map(_.addressRoot).toSet,
              row.keyHashes.toSet,
              "the address roots (bootstrapWitKeyHash)"
            )
        case (true, Left(refused)) =>
          fail(s"the reference accepts it, Scarab refused it: $refused")
        case (false, Right(witnesses)) =>
          fail(s"the reference refuses it (${row.failure}); Scarab read $witnesses")
        case (false, Left(refused)) =>
          assertEquals(
            refused,
            Refused.Decode(LedgerDecodeError.ByteLength(DecodeField.ChainCode, 32, row.lengths(0)))
          )
    }

object BootstrapChainCodesReferenceSuite:

  /** What a line holds: a witness set, a whole transaction, a block body (the parts of a block
    * after its header), or one bootstrap witness read alone at a decoder version.
    */
  enum Kind(val wire: String):
    case Witnesses extends Kind("witnesses")
    case Transaction extends Kind("transaction")
    case BlockBody extends Kind("blockbody")
    case Alone extends Kind("bootstrap")

  /** A line of the corpus: bootstrap witnesses whose chain codes have `lengths` bytes, read at
    * decoder `version` (the era's, or for a witness alone the one given), the reference's verdict
    * and the address root of each witness it read.
    */
  final case class Row(
      name: String,
      era: Option[Era],
      kind: Kind,
      version: Int,
      lengths: Vector[Int],
      accepted: Boolean,
      failure: Option[String],
      keyHashes: Vector[Bytes],
      raw: Bytes
  )

  /** Why Scarab refused a line. */
  enum Refused:
    case Decode(cause: LedgerDecodeError)
    case Chain(cause: ChainError)
    case Syntax(cause: Cbor.Error)
    case NotShelleyBased
    case NoEra

  enum LoadError:
    case Unreadable(path: Path, cause: Throwable)
    case Line(path: Path, number: Int, cause: io.circe.Error)

  val Lengths: Vector[Int] = Vector(0, 31, 32, 33, 64)
  val Shelley: Vector[Era] =
    Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

  /** `instance DecCBOR ChainCode`'s failure from version 12. */
  val Rule = "ChainCode is expected to be 32 bytes in size"

  private val path = Path.of("fixtures/reference/bootstrap-chain-codes/cases.jsonl")
  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  /** The chain code the generator writes for `length` (`bootstrap_chain_code_cases.py`). */
  def chainCode(length: Int): Bytes = Bytes(Vector.fill(length)(0x92.toByte))

  /** Whether an era of Scarab reads the line: a witness alone read at a version no era reads at (6,
    * 8, 10, 11) is the reference's alone.
    */
  def readable(row: Row): Boolean = row.era.isDefined

  /** `{2: [witness]}`: a witness set holding the one bootstrap witness of a line read alone. */
  private def holding(witness: Bytes): Bytes =
    Bytes(Vector(0xa1, 0x02, 0x81).map(_.toByte)) ++ witness

  /** The bootstrap witnesses Scarab reads from a line, with the decoders of its era: a witness
    * alone in a witness set of the era read at its version, Dijkstra's for version 12.
    */
  def scarab(row: Row): Either[Refused, Vector[BootstrapWitness]] =
    row.era.toRight(Refused.NoEra).flatMap { era =>
      (row.kind, era) match
        case (Kind.Alone, Era.Dijkstra) =>
          Cbor
            .decode(holding(row.raw), Cbor.Limits.Unbounded)
            .leftMap(Refused.Syntax(_))
            .flatMap(DijkstraWitnesses.decode(_).leftMap(Refused.Decode(_)))
            .map(
              _.bootstraps.map(b => BootstrapWitness(b.key, b.signature, b.chainCode, b.attributes))
            )
        case (Kind.Alone, _) =>
          Witnesses.decode(holding(row.raw), era).bimap(Refused.Decode(_), _.bootstrap)
        case (Kind.Witnesses, _) =>
          Witnesses.decode(row.raw, era).bimap(Refused.Decode(_), _.bootstrap)
        case (Kind.Transaction, _) =>
          Tx.fromEnvelope(row.raw, era).bimap(Refused.Decode(_), _.witnesses.bootstrap)
        case (Kind.BlockBody, _) =>
          Block
            .decodeShelley(DetachedRecordsReferenceSuite.block(era, row.raw))
            .leftMap(Refused.Chain(_))
            .flatMap {
              case b: Block.Shelley =>
                b.transactions
                  .flatTraverse(t =>
                    Tx.fromBlock(t, era).bimap(Refused.Decode(_), _.witnesses.bootstrap)
                  )
              case _ => Left(Refused.NotShelleyBased)
            }
    }

  private given Decoder[Row] = Decoder.instance { c =>
    def unknown(what: String) = io.circe.DecodingFailure(s"unknown $what", c.history)
    for
      name <- c.get[String]("name")
      era <- c
        .get[Option[String]]("era")
        .flatMap(_.traverse(e => eras.get(e).toRight(unknown(s"era $e"))))
      kind <- c
        .get[String]("kind")
        .flatMap(k => Kind.values.find(_.wire == k).toRight(unknown(s"kind $k")))
      version <- c.get[Int]("version")
      lengths <- c.get[Vector[Int]]("lengths")
      accepted <- c.get[Boolean]("accepted")
      failure <- c.get[Option[String]]("failure")
      keyHashes <- c
        .get[Option[Vector[String]]]("keyHashes")
        .flatMap(
          _.orEmpty.traverse(h => Bytes.fromHex(h).leftMap(e => unknown(e.toString)))
        )
      raw <- c.get[String]("bytes").flatMap(h => Bytes.fromHex(h).leftMap(e => unknown(e.toString)))
    yield Row(name, era, kind, version, lengths, accepted, failure, keyHashes, raw)
  }

  def load: Either[LoadError, Vector[Row]] =
    Either
      .catchNonFatal(Files.readAllLines(path).asScala.toVector)
      .leftMap(LoadError.Unreadable(path, _))
      .flatMap(
        _.zipWithIndex
          .filter(_._1.nonEmpty)
          .traverse((line, i) => parser.decode[Row](line).leftMap(LoadError.Line(path, i + 1, _)))
      )
