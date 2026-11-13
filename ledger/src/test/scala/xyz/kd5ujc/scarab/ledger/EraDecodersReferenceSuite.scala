// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.Era

/** The parts of a block that neighbouring eras' decoders read differently, each run through the
  * production decoders and held to the verdict of the unchanged released ones
  * (`fixtures/reference/era-decoders/cases.jsonl`):
  *   - a certificate of each tag in a body: Shelley to Babbage read `ShelleyTxCert`, tags 0 to 6
  *     (`eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:475-487`, cardano-ledger f649f975);
  *     Conway's `ConwayTxCert` fails 5 and 6 (`Conway/TxCert.hs:674-681`);
  *   - a time-lock native script in a witness set: Shelley's `MultiSig` reads tags 0 to 3
  *     (`Shelley/Scripts.hs:264-278`), Allegra's `Timelock` 0 to 5;
  *   - a repeated index in a block body's auxiliary data map, refused from decoder version 9
  *     (`decodeIntMap`, `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:
  *     858-878`);
  *   - an invalid-transaction list that is not increasing, Alonzo to Conway, for which the
  *     reference throws while it decodes the block body (`alignedValidFlags`,
  *     `Alonzo/BlockBody/Internal.hs:278-286`).
  *
  * A refusal must be for the Scarab cause of the reference's rule
  * ([[EraDecodersReferenceSuite.Rule]]).
  */
class EraDecodersReferenceSuite extends FunSuite:
  import EraDecodersReferenceSuite.*

  private val rows: Vector[Row] = load.fold(e => fail(e.toString), identity)

  test("the corpus is the harness's input, every refusal for one rule") {
    val inputs = loadInputs.fold(e => fail(e.toString), identity)
    assertEquals(rows.size, 106)
    assertEquals(rows.count(_.accepted), 30)
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    assertEquals(rows.map(r => r.name -> r.raw).toMap, inputs.cases, "the bytes the harness read")
    for (name, accepted) <- inputs.controls do
      assertEquals(rows.find(_.name == name).map(_.accepted), Some(accepted), name)
    for row <- rows do assertEquals(row.rule.isEmpty, row.accepted, s"${row.name}: ${row.failure}")
    assertEquals(
      rows.flatMap(_.rule).groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        Rule.UnknownKey -> (60 + 3),
        Rule.NoLongerSupported -> 2,
        Rule.DuplicateCount -> 1,
        Rule.NegativeReplicate -> 9,
        Rule.EndOfInput -> 1
      )
    )
  }

  for row <- rows do
    test(row.name) {
      (row.rule, scarab(row)) match
        case (None, Right(())) => ()
        case (None, Left(e))   => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(())) =>
          fail(s"the reference refuses this encoding: ${row.failure.getOrElse("")}")
        case (Some(rule), Left(e)) =>
          assert(cause(row, rule, e), s"Scarab refused it for another cause than $rule: $e")
    }

object EraDecodersReferenceSuite:
  enum Kind(val wire: String):
    case Body extends Kind("body")
    case Witnesses extends Kind("witnesses")

    /** The parts of a block after its header. */
    case BlockBody extends Kind("blockbody")

  /** The rule of the reference a refusal is for, named by the fragment of its failure that
    * identifies it.
    */
  enum Rule(val fragment: String):
    /** `invalidKey`: a tag the era's sum type does not name. */
    case UnknownKey extends Rule("not a valid key")

    /** `ConwayTxCert`'s failure for the certificates it retired. */
    case NoLongerSupported extends Rule("certificates are no longer supported")

    /** `decodeListLikeEnforceNoDuplicates`: a map that names a key twice. */
    case DuplicateCount extends Rule("does not match the total count that was decoded")

    /** The exception `Seq.replicate` throws for a negative count, not a decoder failure. */
    case NegativeReplicate extends Rule("replicate takes a nonnegative integer argument")

    /** The input ends inside an item. */
    case EndOfInput extends Rule("end of input")

  /** Why Scarab refused a row. */
  enum Refused:
    case Decode(cause: LedgerDecodeError)
    case Chain(cause: ChainError)
    case NotShelleyBased

  /** A line of the corpus; `rule` is that of the reference's failure, for a refused one. */
  final case class Row(
      name: String,
      era: Era,
      kind: Kind,
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String],
      rule: Option[Rule]
  )

  /** The harness's input files: each case's bytes by name, and the controls' committed verdicts. */
  final case class Inputs(cases: Map[String, Bytes], controls: Map[String, Boolean])

  enum LoadError:
    case Unreadable(file: String, message: String)
    case Line(file: String, message: String)
    case NoRule(name: String)
    case TwoRules(name: String, rules: Vector[Rule])

  private val directory = Path.of("fixtures/reference/era-decoders")
  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  /** What Scarab reads a row as: the decoder of its kind, and for a block body each transaction. */
  def scarab(row: Row): Either[Refused, Unit] = row.kind match
    case Kind.Body      => TxBody.decode(row.raw, row.era).bimap(Refused.Decode(_), _ => ())
    case Kind.Witnesses => Witnesses.decode(row.raw, row.era).bimap(Refused.Decode(_), _ => ())
    case Kind.BlockBody =>
      Block
        .decodeShelley(DetachedRecordsReferenceSuite.block(row.era, row.raw))
        .leftMap(Refused.Chain(_))
        .flatMap {
          case b: Block.Shelley =>
            b.transactions.traverse_(t => Tx.fromBlock(t, row.era).leftMap(Refused.Decode(_)))
          case _ => Left(Refused.NotShelleyBased)
        }

  /** Whether `refused` is the Scarab cause of `rule` for `row`. */
  def cause(row: Row, rule: Rule, refused: Refused): Boolean = (rule, row.kind, refused) match
    case (
          Rule.UnknownKey,
          Kind.Body,
          Refused.Decode(LedgerDecodeError.TaggedArity(DecodeField.Certificate, tag, size))
        ) =>
      certificate(row.raw).contains((tag, size))
    case (
          Rule.UnknownKey,
          Kind.Witnesses,
          Refused.Decode(
            LedgerDecodeError.NativeScript(
              NativeScript.DecodeError.Malformed(
                LedgerDecodeError.TaggedArity(DecodeField.NativeScript, tag, 2)
              )
            )
          )
        ) =>
      tag == 4 || tag == 5
    case (
          Rule.NoLongerSupported,
          Kind.Body,
          Refused.Decode(LedgerDecodeError.RetiredCertificate(retired))
        ) =>
      certificate(row.raw).map(_._1).contains(BigInt(retired.tag))
    case (
          Rule.DuplicateCount,
          Kind.BlockBody,
          Refused.Chain(
            ChainError.Decode(
              DecodeField.Block,
              LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData)
            )
          )
        ) =>
      true
    case (
          Rule.NegativeReplicate,
          Kind.BlockBody,
          Refused.Chain(
            ChainError.Decode(
              DecodeField.Block,
              LedgerDecodeError.InvalidTransactionOrder(previous, index)
            )
          )
        ) =>
      index <= previous
    case (Rule.EndOfInput, Kind.Body, Refused.Decode(LedgerDecodeError.Syntax(_))) => true
    case _                                                                         => false

  /** The tag and the number of items of the first certificate of a body (key 4), as written. */
  private def certificate(body: Bytes): Option[(BigInt, Int)] =
    def items(n: Node): Option[Vector[Node]] = n.value match
      case Value.Arr(xs) => Some(xs)
      case _             => None
    for
      root <- Cbor.decode(body, Cbor.Limits.Unbounded).toOption
      certs <- root.value match
        case Value.Map(fields) =>
          fields.collectFirst { case (k, v) if k.value == Value.UInt(BigInt(4)) => v }
        case _ => None
      first <- items(certs).flatMap(_.headOption)
      xs <- items(first)
      tag <- xs.headOption.map(_.value).collect { case Value.UInt(t) => t }
    yield (tag, xs.size)

  private def read(file: String): Either[LoadError, String] =
    Either
      .catchNonFatal(Files.readString(directory.resolve(file)))
      .leftMap(e => LoadError.Unreadable(file, e.getMessage))

  private def hex(file: String, s: String): Either[LoadError, Bytes] =
    Bytes.fromHex(s).leftMap(e => LoadError.Line(file, e.toString))

  /** The rule of a recorded failure: exactly one must explain it. */
  private def rule(name: String, failure: String): Either[LoadError, Rule] =
    Rule.values.filter(r => failure.contains(r.fragment)).toVector match
      case Vector(r) => Right(r)
      case Vector()  => Left(LoadError.NoRule(name))
      case rs        => Left(LoadError.TwoRules(name, rs))

  /** The lines of `cases.jsonl`. */
  def load: Either[LoadError, Vector[Row]] =
    val file = "cases.jsonl"
    given Decoder[Row] = Decoder.instance { c =>
      def unknown(what: String) = io.circe.DecodingFailure(s"unknown $what", c.history)
      for
        name <- c.get[String]("name")
        era <- c.get[String]("era").flatMap(e => eras.get(e).toRight(unknown(s"era $e")))
        kind <- c
          .get[String]("kind")
          .flatMap(k => Kind.values.find(_.wire == k).toRight(unknown(s"kind $k")))
        hexBytes <- c.get[String]("bytes")
        raw <- Bytes.fromHex(hexBytes).leftMap(e => unknown(e.toString))
        accepted <- c.get[Boolean]("accepted")
        failure <- c.get[Option[String]]("failure")
      yield Row(name, era, kind, raw, accepted, failure, None)
    }
    read(file).flatMap(
      _.linesIterator.toVector
        .filter(_.nonEmpty)
        .traverse(line =>
          parser
            .decode[Row](line)
            .leftMap(e => LoadError.Line(file, e.getMessage))
            .flatMap(row =>
              row.failure
                .filter(_ => !row.accepted)
                .traverse(rule(row.name, _))
                .map(r => row.copy(rule = r))
            )
        )
    )

  /** The input files: `batch.json`, `single-NN.json` and `controls-expected.json`. */
  def loadInputs: Either[LoadError, Inputs] =
    def cases(file: String): Either[LoadError, Vector[(String, Bytes)]] =
      read(s"inputs/$file").flatMap(text =>
        parser
          .decode[Vector[Json]](text)
          .leftMap(e => LoadError.Line(file, e.getMessage))
          .flatMap(_.traverse { json =>
            val c = json.hcursor
            (c.get[String]("name"), c.get[String]("bytes")).tupled
              .leftMap(e => LoadError.Line(file, e.getMessage))
              .flatMap((name, b) => hex(file, b).map(name -> _))
          })
      )
    for
      singles <- Either
        .catchNonFatal(
          Using.resource(Files.list(directory.resolve("inputs")))(
            _.iterator().asScala.toVector.map(_.getFileName.toString)
          )
        )
        .leftMap(e => LoadError.Unreadable("inputs", e.getMessage))
        .map(_.filter(_.startsWith("single-")).sorted)
      all <- ("batch.json" +: singles).flatTraverse(cases)
      controls <- read("inputs/controls-expected.json").flatMap(text =>
        parser
          .decode[Map[String, Boolean]](text)
          .leftMap(e => LoadError.Line("controls-expected.json", e.getMessage))
      )
    yield Inputs(all.toMap, controls)
