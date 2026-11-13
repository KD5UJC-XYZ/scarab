// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.protocol.Era

/** Bootstrap witnesses that share an address root. The Shelley and Alonzo witness sets read their
  * bootstrap witnesses into a `Set` (`Set.fromList <$> decodeList decCBOR` below decoder version 9,
  * `Set.fromList . NE.toList <$> decodeNonEmptyList decCBOR` from 9 below 12;
  * `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxWits.hs:234-249`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:613-668`, cardano-ledger f649f975), and
  * `Ord BootstrapWitness` compares `bootstrapWitKeyHash` alone
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:108-109`): of the witnesses with
  * one root the set keeps one, and `validateVerifiedWits`
  * (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxow.hs:391-407`) checks only its
  * signature. A vkey witness's `Ord` also compares its signature's hash (`Keys/WitVKey.hs:58-68`),
  * so two vkey witnesses of one key with different signatures are both kept and both checked.
  *
  * `fixtures/reference/bootstrap-duplicates/cases.jsonl` holds whole transactions of each era from
  * Shelley to Conway with bootstrap witnesses of one root and different signatures, valid and
  * invalid, in each order and among witnesses of other roots, and the controls (two roots, the same
  * witness twice, two vkey witnesses of one key), and witness sets read at each decoder version
  * from the era's to 12, with the witnesses the unchanged released decoders kept and the verdict of
  * `validateVerifiedWits`. Scarab's decoders must keep the same witnesses, in the order of the
  * reference's set for the bootstrap witnesses, and its signature check give the same verdict.
  */
class BootstrapDuplicatesReferenceSuite extends FunSuite:
  import BootstrapDuplicatesReferenceSuite.*

  private val rows: Vector[Row] = load.fold(e => fail(e.toString), identity)

  test("the corpus covers each era's transactions and each decoder version from the era's to 12") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    val shapes =
      rows.filter(_.kind == Kind.Transaction).groupMap(_.era)(r => (r.bootstraps, r.vkeys))
    assertEquals(shapes.keySet, Shelley.toSet)
    assertEquals(shapes.values.map(_.toSet).toSet.size, 1, "every era holds the same transactions")
    assertEquals(shapes.values.map(_.size).toSet, Set(28))
    for era <- Shelley do
      assertEquals(
        rows.filter(r => r.era == era && r.kind == Kind.Witnesses).map(_.version).toSet,
        (DecoderVersion.of(era).value to 12).toSet,
        s"$era"
      )
  }

  test("the reference keeps the last bootstrap witness of each root below version 12") {
    for row <- rows do
      assertEquals(row.accepted, row.rule.isEmpty, row.name)
      row.failure.foreach(f => assert(row.rule.exists(f.contains), s"${row.name}: $f"))
      if row.accepted && row.bootstraps.nonEmpty then
        // `Asplit` is `A`'s key with the chain code and attributes splitting the same bytes: one root
        val roots = row.bootstraps.map(_.takeWhile(_ != ':').stripSuffix("split")).distinct
        assertEquals(row.keptBootstraps.size, roots.size, row.name)
        assertEquals(row.keyHashes.distinct.size, row.keyHashes.size, row.name)
    // the cases that decide the direction: an invalid witness first is replaced by the valid one
    // after it, and the valid one first by the invalid one
    val transactions = rows.filter(_.kind == Kind.Transaction)
    for era <- Shelley do
      def verdict(shape: Vector[String]) =
        transactions.find(r => r.era == era && r.bootstraps == shape).flatMap(_.verified)
      assertEquals(verdict(Vector("A:invalid", "A:valid")), Some(true), s"$era")
      assertEquals(verdict(Vector("A:valid", "A:invalid")), Some(false), s"$era")
      assertEquals(
        transactions
          .find(r => r.era == era && r.vkeys == Vector("K:invalid", "K:valid"))
          .flatMap(_.verified),
        Some(false),
        s"$era: the vkey witnesses of one key are two"
      )
  }

  for row <- rows if DecoderVersion.of(row.era).value == row.version do
    test(row.name) {
      scarab(row) match
        case Left(refused) =>
          fail(s"the reference accepts it (${row.failure}), Scarab refused it: $refused")
        case Right(read) =>
          assert(row.accepted, s"the reference refuses it (${row.failure}); Scarab read it")
          read.tx.foreach { tx =>
            val invalid = TxChecks
              .verifiedWits(tx, Verdicts.Direct)
              .map(Test.failures(_).toVector.flatMap(_.toChain.toVector))
              .fold(e => fail(s"no verdict: $e"), identity)
              .flatMap {
                case PredicateFailure.Utxow(UtxowFailure.InvalidWitnesses(keys)) => keys
                case other => fail(s"not a signature failure: $other")
              }
            assertEquals(invalid.isEmpty, row.verified.contains(true), "the verdict")
            assertEquals(
              invalid.sorted,
              row.invalidKeys.sorted,
              "the keys of the invalid witnesses"
            )
          }
          assertEquals(
            read.witnesses.bootstrap.map(encoded),
            row.keptBootstraps,
            "the bootstrap witnesses kept, in the order of the reference's set"
          )
          assertEquals(
            read.witnesses.bootstrap.map(_.addressRoot),
            row.keyHashes,
            "the address roots"
          )
          assertEquals(
            read.witnesses.vkeys.map(encoded).toSet,
            row.keptVkeys.toSet,
            "the vkey witnesses kept"
          )
          assertEquals(read.witnesses.vkeys.size, row.keptVkeys.size, "the vkey witnesses kept")
    }

object BootstrapDuplicatesReferenceSuite:

  enum Kind(val wire: String):
    case Witnesses extends Kind("witnesses")
    case Transaction extends Kind("transaction")

  /** A line of the corpus: the bootstrap and vkey witnesses written (`signer:signature`, as
    * `bootstrap_duplicate_cases.py` names them), the decoder version, the reference's verdict, the
    * encodings of the witnesses its sets kept, in their order, and for a transaction the verdict of
    * `validateVerifiedWits` and the keys it found invalid.
    */
  final case class Row(
      name: String,
      era: Era,
      kind: Kind,
      version: Int,
      bootstraps: Vector[String],
      vkeys: Vector[String],
      rule: Option[String],
      accepted: Boolean,
      failure: Option[String],
      keyHashes: Vector[Bytes],
      keptBootstraps: Vector[Bytes],
      keptVkeys: Vector[Bytes],
      verified: Option[Boolean],
      invalidKeys: Vector[Bytes],
      raw: Bytes
  )

  /** What Scarab read from a line: the witness set, and the transaction for a whole one. */
  final case class Read(witnesses: Witnesses, tx: Option[Tx])

  enum LoadError:
    case Unreadable(path: Path, cause: Throwable)
    case Line(path: Path, number: Int, cause: io.circe.Error)

  val Shelley: Vector[Era] =
    Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)

  private val path = Path.of("fixtures/reference/bootstrap-duplicates/cases.jsonl")
  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  /** The head of a definite byte string of `n` bytes, `n` below 65536. */
  private def bytesHead(n: Int): Vector[Byte] =
    if n < 24 then Vector((0x40 + n).toByte)
    else if n < 256 then Vector(0x58.toByte, n.toByte)
    else Vector(0x59.toByte, (n >> 8).toByte, n.toByte)

  private def bstr(b: Bytes): Bytes = Bytes(bytesHead(b.size)) ++ b

  /** `encCBOR` of a bootstrap witness (`Bootstrap.hs:93-100`): the list of its four byte strings.
    */
  def encoded(w: BootstrapWitness): Bytes =
    Bytes(Vector(0x84.toByte)) ++ bstr(w.vkey) ++ bstr(w.signature) ++ bstr(w.chainCode) ++
      bstr(w.attributes)

  /** `encCBOR` of a vkey witness: the list of its key and signature. */
  def encoded(w: (Bytes, Bytes)): Bytes = Bytes(Vector(0x82.toByte)) ++ bstr(w._1) ++ bstr(w._2)

  def scarab(row: Row): Either[LedgerDecodeError, Read] =
    row.kind match
      case Kind.Witnesses => Witnesses.decode(row.raw, row.era).map(Read(_, None))
      case Kind.Transaction =>
        Tx.fromEnvelope(row.raw, row.era).map(t => Read(t.witnesses, Some(t)))

  private given Decoder[Row] = Decoder.instance { c =>
    def unknown(what: String) = io.circe.DecodingFailure(s"unknown $what", c.history)
    def hexes(field: String) =
      c.get[Option[Vector[String]]](field)
        .flatMap(_.orEmpty.traverse(h => Bytes.fromHex(h).leftMap(e => unknown(e.toString))))
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").flatMap(e => eras.get(e).toRight(unknown(s"era $e")))
      kind <- c
        .get[String]("kind")
        .flatMap(k => Kind.values.find(_.wire == k).toRight(unknown(s"kind $k")))
      version <- c.get[Int]("version")
      bootstraps <- c.get[Vector[String]]("bootstraps")
      vkeys <- c.get[Vector[String]]("vkeys")
      rule <- c.get[Option[String]]("rule")
      accepted <- c.get[Boolean]("accepted")
      failure <- c.get[Option[String]]("failure")
      keyHashes <- hexes("keyHashes")
      keptBootstraps <- hexes("keptBootstraps")
      keptVkeys <- hexes("keptVkeys")
      verified <- c.get[Option[Boolean]]("verified")
      invalidKeys <- hexes("invalidKeys")
      raw <- c.get[String]("bytes").flatMap(h => Bytes.fromHex(h).leftMap(e => unknown(e.toString)))
    yield Row(
      name,
      era,
      kind,
      version,
      bootstraps,
      vkeys,
      rule,
      accepted,
      failure,
      keyHashes,
      keptBootstraps,
      keptVkeys,
      verified,
      invalidKeys,
      raw
    )
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
