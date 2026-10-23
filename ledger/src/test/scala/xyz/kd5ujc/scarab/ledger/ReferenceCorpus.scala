// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** A recorded reference corpus (`fixtures/reference/<name>`): the original encodings of
  * `cases.json` joined with what the unchanged released decoders did with them (`reference.json`),
  * read once into typed values. The JSON vocabulary (era, kind, the Scarab error case a refusal
  * must be, the rule of the reference it must be for) is parsed here, at the boundary; a name the
  * corpus does not know fails the load with a [[ReferenceCorpus.Error]], so the suites that replay
  * a corpus branch on enums and never on text.
  */
private[ledger] object ReferenceCorpus:

  /** A recorded corpus: the directory of `fixtures/reference` that holds it. */
  enum Corpus(val directory: String):
    case ConwayDecoding extends Corpus("conway-decoding")
    case PreConwaySets extends Corpus("pre-conway-sets")

  /** What a case is decoded as. */
  enum Kind(val wire: String):
    case Body extends Kind("body")
    case Witnesses extends Kind("witnesses")
    case Transaction extends Kind("transaction")

  /** The case of [[LedgerDecodeError]] a refusal must be. */
  enum Refusal(val wire: String):
    case DuplicateElement extends Refusal("DuplicateElement")
    case Empty extends Refusal("Empty")
    case Expected extends Refusal("Expected")
    case RationalOutOfBounds extends Refusal("RationalOutOfBounds")
    case UnknownFields extends Refusal("UnknownFields")
    case ZeroDenominator extends Refusal("ZeroDenominator")
    case Zero extends Refusal("Zero")

  object Refusal:
    /** The tag of the case of an error, if the corpus names it; a voting-procedure structure error
      * is the error of the structure.
      */
    def of(e: LedgerDecodeError): Option[Refusal] = e match
      case LedgerDecodeError.DuplicateElement(_)          => Some(DuplicateElement)
      case LedgerDecodeError.Empty(_)                     => Some(Empty)
      case LedgerDecodeError.Expected(_, _, _)            => Some(Expected)
      case LedgerDecodeError.RationalOutOfBounds(_, _, _) => Some(RationalOutOfBounds)
      case LedgerDecodeError.UnknownFields(_, _)          => Some(UnknownFields)
      case LedgerDecodeError.ZeroDenominator(_)           => Some(ZeroDenominator)
      case LedgerDecodeError.Zero(_)                      => Some(Zero)
      case LedgerDecodeError.Voting(VotingProcedures.DecodeError.InvalidStructure(cause)) =>
        of(cause)
      case _ => None

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

  private def array(corpus: Corpus, file: String): Either[Error, Vector[Json]] =
    for
      text <- Either
        .catchNonFatal(Files.readString(Path.of("fixtures", "reference", corpus.directory, file)))
        .leftMap(e => Error.Unreadable(file, e.getMessage))
      json <- parse(text).leftMap(e => Error.Unreadable(file, e.message))
      items <- json.asArray.toRight(Error.NotAnArray(file))
    yield items

  private def field[A: Decoder](json: Json, id: String, name: String): Either[Error, A] =
    json.hcursor.get[A](name).leftMap(e => Error.Field(id, e.message))

  /** The cases of a corpus, in the order of its `cases.json`. */
  def load(corpus: Corpus): Either[Error, Vector[Case]] =
    for
      verdicts <- array(corpus, "reference.json")
      cases <- array(corpus, "cases.json")
      byName <- verdicts
        .traverse(v => field[String](v, "reference", "name").map(_ -> v))
        .map(_.toMap)
      rows <- cases.traverse(row(byName, _))
      _ <- rows
        .groupBy(_.id)
        .collectFirst { case (id, rs) if rs.size > 1 => Error.DuplicateId(id) }
        .toLeft(())
    yield rows

  private def row(verdicts: Map[String, Json], c: Json): Either[Error, Case] =
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
      verdict <- verdicts.get(name).toRight(Error.NoVerdict(id, name))
      result = verdict.hcursor.downField("result")
      accepted <- result.get[Boolean]("accepted").leftMap(e => Error.Field(id, e.message))
      failure <- result.get[Option[String]]("failure").leftMap(e => Error.Field(id, e.message))
    yield Case(id, name, era, kind, expect, rule, size, raw, accepted, failure.map(Failure(_)))
