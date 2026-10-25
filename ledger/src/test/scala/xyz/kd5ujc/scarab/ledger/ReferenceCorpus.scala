// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** A recorded reference corpus (`fixtures/reference/<name>`): the original encodings of
  * `cases.json` joined with what the unchanged released decoders did with them (`reference.json`),
  * or, for a [[Layout.Lines]] corpus, one `cases.jsonl` whose lines hold both, read once into typed
  * values. The JSON vocabulary (era, kind, the Scarab error case a refusal must be, the rule of the
  * reference it must be for) is parsed here, at the boundary; a name the corpus does not know fails
  * the load with a [[ReferenceCorpus.Error]], so the suites that replay a corpus branch on enums
  * and never on text.
  */
private[ledger] object ReferenceCorpus:

  /** A recorded corpus: the directory of `fixtures/reference` that holds it. */
  enum Corpus(val directory: String, val layout: Layout):
    case ConwayDecoding extends Corpus("conway-decoding", Layout.Pair)
    case PreConwaySets extends Corpus("pre-conway-sets", Layout.Pair)
    case DefiniteBytes extends Corpus("definite-bytes", Layout.Lines)

  /** How a corpus is stored: `cases.json` beside `reference.json`, or one `cases.jsonl` with a case
    * and its verdict (`accepted`, `failure`) on each line.
    */
  enum Layout:
    case Pair
    case Lines

  /** What a case is decoded as. */
  enum Kind(val wire: String):
    case Body extends Kind("body")
    case Witnesses extends Kind("witnesses")
    case Transaction extends Kind("transaction")

  /** The case of [[LedgerDecodeError]] a refusal must be. */
  enum Refusal(val wire: String):
    case ArrayLength extends Refusal("ArrayLength")
    case DuplicateElement extends Refusal("DuplicateElement")
    case AddressHeader extends Refusal("AddressHeader")
    case ByteLength extends Refusal("ByteLength")
    case CrcMismatch extends Refusal("CrcMismatch")
    case Empty extends Refusal("Empty")
    case Expected extends Refusal("Expected")
    case IndefiniteLength extends Refusal("IndefiniteLength")
    case DuplicateKey extends Refusal("DuplicateKey")
    case ExpectedTag extends Refusal("ExpectedTag")
    case NumberOutOfRange extends Refusal("NumberOutOfRange")
    case RationalOutOfBounds extends Refusal("RationalOutOfBounds")
    case UnknownFields extends Refusal("UnknownFields")
    case UnknownKey extends Refusal("UnknownKey")
    case ZeroDenominator extends Refusal("ZeroDenominator")
    case Syntax extends Refusal("Syntax")
    case UnknownTag extends Refusal("UnknownTag")
    case Zero extends Refusal("Zero")

  object Refusal:
    /** The tag of the case of an error, if the corpus names it; a voting-procedure structure error
      * and a malformed native script are the error of the structure.
      */
    def of(e: LedgerDecodeError): Option[Refusal] = e match
      case LedgerDecodeError.ArrayLength(_, _, _)         => Some(ArrayLength)
      case LedgerDecodeError.DuplicateElement(_)          => Some(DuplicateElement)
      case LedgerDecodeError.AddressHeader(_)             => Some(AddressHeader)
      case LedgerDecodeError.ByteLength(_, _, _)          => Some(ByteLength)
      case LedgerDecodeError.CrcMismatch(_, _)            => Some(CrcMismatch)
      case LedgerDecodeError.Empty(_)                     => Some(Empty)
      case LedgerDecodeError.Expected(_, _, _)            => Some(Expected)
      case LedgerDecodeError.IndefiniteLength(_)          => Some(IndefiniteLength)
      case LedgerDecodeError.DuplicateKey(_)              => Some(DuplicateKey)
      case LedgerDecodeError.ExpectedTag(_, _, _)         => Some(ExpectedTag)
      case LedgerDecodeError.NumberOutOfRange(_, _, _, _) => Some(NumberOutOfRange)
      case LedgerDecodeError.RationalOutOfBounds(_, _, _) => Some(RationalOutOfBounds)
      case LedgerDecodeError.UnknownFields(_, _)          => Some(UnknownFields)
      case LedgerDecodeError.UnknownKey(_, _)             => Some(UnknownKey)
      case LedgerDecodeError.ZeroDenominator(_)           => Some(ZeroDenominator)
      case LedgerDecodeError.Syntax(_)                    => Some(Syntax)
      case LedgerDecodeError.UnknownTag(_, _)             => Some(UnknownTag)
      case LedgerDecodeError.Zero(_)                      => Some(Zero)
      case LedgerDecodeError.Voting(VotingProcedures.DecodeError.InvalidStructure(cause)) =>
        of(cause)
      case LedgerDecodeError.NativeScript(NativeScript.DecodeError.Malformed(cause)) => of(cause)
      case _                                                                         => None

  /** The rule of the reference a refusal is recorded for, named by the fragment of its failure text
    * that identifies it.
    */
  enum Rule(val fragment: String):
    case DuplicateCount extends Rule("does not match the total count that was decoded")
    case NonEmptyWhenSupplied extends Rule("must be non-empty when supplied")
    case EmptyList extends Rule("Empty list found")
    case ExpectedInteger extends Rule("expected integer")
    case EmptyScriptList extends Rule("Empty list of scripts is not allowed")
    case BoundedRatio extends Rule("BoundedRatio")
    case UnknownKey extends Rule("not a valid key")
    case ZeroDenominator extends Rule("Denominator cannot be zero")
    case ExpectedList extends Rule("expected list len or indef")
    case TagMismatch extends Rule("Expecteg tag 258 but got tag 259")
    case NoElements extends Rule("didn't have any")
    case NonZero extends Rule("non-zero")
    case EmptyRedeemers extends Rule("Expected redeemers map to be non-empty")
    case AddressCrc extends Rule("was not the computed one")
    case AddressLeftover extends Rule("DecoderErrorLeftover")
    case AddressHeaderBits extends Rule("Unused bits are not suppose to be set")
    case AddressEnvelope extends Rule("expected tag")
    case ExpectedBytes extends Rule("expected bytes")
    case AddressExpectedList extends Rule("expected list len")
    case ExpectedString extends Rule("expected string")
    case AddressExpectedMap extends Rule("expected map len")
    case AddressMapCanonicity extends Rule("Canonicity violation while decoding Map")
    case AddressWord8 extends Rule("expected word8")
    case AddressWord8Canonical extends Rule("non-canonical word8")
    case AddressTypeOne extends Rule("Found unknown tag 1 while decoding AddrType")
    case AddressTypeThree extends Rule("Found unknown tag 3 while decoding AddrType")
    case AddressPathLength extends Rule("non-canonical length prefix")
    case AddressPathLeftover
        extends Rule("Found unexpected leftover bytes while decoding HDAddressPayload")
    case AddressWord32Canonical extends Rule("non-canonical word32")
    case AddressWord32 extends Rule("expected word32")
    case AddressRootLength extends Rule("Bytes not expected length")
    case DuplicateKey extends Rule("Duplicate key:")
    case ExpectedWord extends Rule("expected word")
    case ExUnitsBound extends Rule("values must not exceed maxBound :: Int64")
    case ExpectedTag extends Rule("Expected tag")
    case RecordSize extends Rule("Size mismatch when decoding Record")
    case ExpectedMap extends Rule("expected map len or indef")
    case MetadataBytesBound extends Rule("bytestring exceeds 64 bytes")
    case MetadataTextBound extends Rule("text exceeds 64 bytes")

    /** The reference's failure is for this rule. */
    def explains(failure: Failure): Boolean = failure.text.contains(fragment)

  /** The complete failure of the reference, as recorded. */
  final case class Failure(text: String)

  /** One case with the reference's verdict. `size` is the number of elements Scarab must keep where
    * the corpus records it.
    */
  final case class Case(
      id: String,
      name: String,
      era: Era,
      kind: Kind,
      expect: Option[Refusal],
      rule: Option[Rule],
      size: Option[Int],
      raw: Bytes,
      accepted: Boolean,
      failure: Option[Failure]
  )

  enum Error:
    case Unreadable(file: String, message: String)
    case NotAnArray(file: String)
    case Field(id: String, message: String)
    case UnknownEra(id: String, era: String)
    case UnknownKind(id: String, kind: String)
    case UnknownRefusal(id: String, refusal: String)
    case UnknownRule(id: String, rule: String)
    case NoVerdict(id: String, name: String)
    case BadHex(id: String)
    case DuplicateId(id: String)

  private def named[A](all: Array[A], wire: A => String, s: String): Option[A] =
    all.find(a => wire(a) == s)

  private def text(corpus: Corpus, file: String): Either[Error, String] =
    Either
      .catchNonFatal(Files.readString(Path.of("fixtures", "reference", corpus.directory, file)))
      .leftMap(e => Error.Unreadable(file, e.getMessage))

  private def array(corpus: Corpus, file: String): Either[Error, Vector[Json]] =
    for
      content <- text(corpus, file)
      json <- parse(content).leftMap(e => Error.Unreadable(file, e.message))
      items <- json.asArray.toRight(Error.NotAnArray(file))
    yield items

  private def lines(corpus: Corpus, file: String): Either[Error, Vector[Json]] =
    text(corpus, file).flatMap(
      _.linesIterator.toVector
        .filter(_.nonEmpty)
        .traverse(l => parse(l).leftMap(e => Error.Unreadable(file, e.message)))
    )

  private def field[A: Decoder](json: Json, id: String, name: String): Either[Error, A] =
    json.hcursor.get[A](name).leftMap(e => Error.Field(id, e.message))

  /** The cases of a corpus, in the order of its `cases.json` or `cases.jsonl`. */
  def load(corpus: Corpus): Either[Error, Vector[Case]] =
    for
      rows <- corpus.layout match
        case Layout.Pair =>
          for
            verdicts <- array(corpus, "reference.json")
            cases <- array(corpus, "cases.json")
            byName <- verdicts
              .traverse(v => field[String](v, "reference", "name").map(_ -> v))
              .map(_.toMap)
            rows <- cases.traverse(c =>
              row(c, name => byName.get(name).map(_.hcursor.downField("result")))
            )
          yield rows
        case Layout.Lines =>
          lines(corpus, "cases.jsonl").flatMap(_.traverse(c => row(c, _ => Some(c.hcursor))))
      _ <- rows
        .groupBy(_.id)
        .collectFirst { case (id, rs) if rs.size > 1 => Error.DuplicateId(id) }
        .toLeft(())
    yield rows

  /** The case `c`, with the verdict its name has in `result`: the `accepted` and `failure` fields.
    */
  private def row(c: Json, result: String => Option[ACursor]): Either[Error, Case] =
    for
      id <- field[String](c, "case", "id")
      name <- field[String](c, id, "name")
      eraWire <- field[String](c, id, "era")
      era <- named(Era.values, _.toString.toLowerCase, eraWire)
        .toRight(Error.UnknownEra(id, eraWire))
      kindWire <- field[String](c, id, "kind")
      kind <- named(Kind.values, _.wire, kindWire).toRight(Error.UnknownKind(id, kindWire))
      expectWire <- field[Option[String]](c, id, "expect")
      expect <- expectWire.traverse(w =>
        named(Refusal.values, _.wire, w).toRight(Error.UnknownRefusal(id, w))
      )
      ruleWire <- field[Option[String]](c, id, "rule")
      rule <- ruleWire.traverse(w =>
        named(Rule.values, _.fragment, w).toRight(Error.UnknownRule(id, w))
      )
      size <- c.hcursor
        .get[Option[Int]]("size")
        .orElse(Right(None))
        .leftMap(e => Error.Field(id, e.message))
      hex <- field[String](c, id, "bytes")
      raw <- Bytes.fromHex(hex).leftMap(_ => Error.BadHex(id))
      verdict <- result(name).toRight(Error.NoVerdict(id, name))
      accepted <- verdict.get[Boolean]("accepted").leftMap(e => Error.Field(id, e.message))
      failure <- verdict.get[Option[String]]("failure").leftMap(e => Error.Field(id, e.message))
    yield Case(id, name, era, kind, expect, rule, size, raw, accepted, failure.map(Failure(_)))
