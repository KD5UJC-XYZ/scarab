// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** The class of the November 2025 mainnet partition: a delegation certificate whose hash is not the
  * length of its type. The reference reads a stake credential's key or script hash, a pool id and a
  * DRep's key or script hash as a `Hash Blake2b_224`, a byte string of exactly 28 bytes (`DecCBOR
  * (Hash h a)`, `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:492-498`,
  * cardano-ledger f649f975), and refuses the transaction otherwise; nodes that kept the first 28
  * bytes of a longer string took a delegation the others refused.
  *
  * `fixtures/reference/delegation-hashes/cases.jsonl` holds a whole Conway transaction for each
  * certificate that delegates (tags 2, 9, 10, 11, 12 and 13) with one of its hashes 27 or 29 bytes
  * long, each pool id also 56 bytes long (the incident's shape), and the certificates with every
  * hash of 28 bytes, with what the unchanged released decoders did with each. Scarab's transaction
  * decoder, the one a block's transactions and a submission go through, must give the same verdict:
  * the same certificate for an accepted one, and for a refused one the length of the hash it
  * refused, at its place.
  */
class DelegationHashesReferenceSuite extends FunSuite:
  import DelegationHashesReferenceSuite.*

  private val rows: Vector[Row] = load.fold(e => fail(e.toString), identity)

  test("the corpus covers every delegating certificate, each place of a hash and both kinds") {
    assertEquals(rows.map(_.tag).toSet, Set(2, 9, 10, 11, 12, 13))
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for tag <- rows.map(_.tag).distinct do
      val certificate = rows.filter(_.tag == tag)
      assertEquals(
        certificate.flatMap(_.hashes).toSet,
        HashKind.values.toSet,
        s"$tag: the controls"
      )
      for place <- Place.values if Place.of(tag).contains(place) do
        assertEquals(
          certificate.filter(_.place.contains(place)).map(_.length).toSet,
          if place == Place.PoolId then Set(27, 29, 56) else Set(27, 29),
          s"$tag $place"
        )
    for row <- rows do
      assertEquals(row.accepted, row.place.isEmpty, row.name)
      row.failure.foreach(f =>
        assert(
          f.contains(s"Expected 28 number of bytes, but got ${row.length}"),
          s"${row.name}: $f"
        )
      )
  }

  for row <- rows do
    test(row.name) {
      (row.place, Tx.fromEnvelope(row.raw, Era.Conway)) match
        case (None, Right(tx)) =>
          assertEquals(tx.body.certificates, Vector(expected(row)), "the certificate")
        case (None, Left(e)) => fail(s"the reference accepts this transaction, Scarab gave $e")
        case (Some(_), Right(tx)) =>
          fail(s"the reference refuses this transaction (${row.failure}); Scarab read $tx")
        case (Some(place), Left(e)) =>
          assertEquals(e, LedgerDecodeError.ByteLength(place.field, 28, row.length))
    }

object DelegationHashesReferenceSuite:

  /** Whether a certificate's hashes are key hashes or script hashes. */
  enum HashKind(val wire: String):
    case Key extends HashKind("key")
    case Script extends HashKind("script")

  /** A place of a hash in a delegating certificate, and the field Scarab names when it refuses it.
    */
  enum Place(val wire: String, val field: DecodeField):
    case CredentialKey extends Place("credential key hash", DecodeField.CertificateCredential)
    case CredentialScript extends Place("credential script hash", DecodeField.CertificateCredential)
    case PoolId extends Place("pool id", DecodeField.PoolId)
    case DRepKey extends Place("drep key hash", DecodeField.DrepHash)
    case DRepScript extends Place("drep script hash", DecodeField.DrepHash)

  object Place:
    /** The places of the certificate `tag` (`Conway/TxCert.hs:683-731`). */
    def of(tag: Int): Set[Place] =
      val pool = Set(2, 10, 11, 13).contains(tag)
      val drep = Set(9, 10, 12, 13).contains(tag)
      Set(CredentialKey, CredentialScript) ++ Option.when(pool)(PoolId) ++
        (if drep then Set(DRepKey, DRepScript) else Set.empty)

  /** A line of the corpus: the certificate `tag` in a whole transaction, with the hash at `place`
    * written at `length` bytes, or, for a control, every hash of 28 bytes and of kind `hashes`.
    */
  final case class Row(
      name: String,
      tag: Int,
      place: Option[Place],
      hashes: Option[HashKind],
      length: Int,
      accepted: Boolean,
      failure: Option[String],
      raw: Bytes
  )

  enum LoadError:
    case Unreadable(path: Path, cause: Throwable)
    case Line(path: Path, number: Int, cause: io.circe.Error)

  private val path = Path.of("fixtures/reference/delegation-hashes/cases.jsonl")

  /** The bytes the generator writes for each hash (`delegation_hash_cases.py`). */
  private def hash(byte: Int): Bytes = Bytes(Vector.fill(28)(byte.toByte))

  private def credential(kind: HashKind, key: Int, script: Int): Credential = kind match
    case HashKind.Key    => Credential.Key(hash(key))
    case HashKind.Script => Credential.Script(hash(script))

  /** The certificate a control holds, as Scarab must read it. */
  def expected(row: Row): Certificate =
    val kind = row.hashes.getOrElse(HashKind.Key)
    val pool = Option.when(Place.of(row.tag).contains(Place.PoolId))(hash(0x61))
    val drep = Option.when(Place.of(row.tag).contains(Place.DRepKey))(
      DRep.Registered(credential(kind, 0x68, 0x6c))
    )
    val deposit = Option.when(row.tag >= 11)(BigInt(2000000))
    Certificate.Delegate(credential(kind, 0x33, 0x3c), pool, drep, deposit)

  private given Decoder[Row] = Decoder.instance { c =>
    def unknown(what: String) = io.circe.DecodingFailure(s"unknown $what", c.history)
    for
      name <- c.get[String]("name")
      _ <- c.get[String]("era").ensure(unknown("era"))(_ == "conway")
      _ <- c.get[String]("kind").ensure(unknown("kind"))(_ == "transaction")
      tag <- c.get[Int]("tag")
      place <- c
        .get[Option[String]]("place")
        .flatMap(_.traverse(p => Place.values.find(_.wire == p).toRight(unknown(s"place $p"))))
      hashes <- c
        .get[Option[String]]("hashes")
        .flatMap(_.traverse(h => HashKind.values.find(_.wire == h).toRight(unknown(s"hashes $h"))))
      length <- c.get[Int]("length")
      accepted <- c.get[Boolean]("accepted")
      failure <- c.get[Option[String]]("failure")
      raw <- c.get[String]("bytes").flatMap(h => Bytes.fromHex(h).leftMap(e => unknown(e.toString)))
    yield Row(name, tag, place, hashes, length, accepted, failure, raw)
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
