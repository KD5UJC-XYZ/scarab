// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Availability,
  DeBruijn,
  DefaultFun,
  DefaultUni,
  PlutusLanguage,
  Program,
  ProgramVersion,
  ProtocolMajor,
  Term,
  Word64
}

/** The predicates `decodeProgram` takes (`UntypedPlutusCore/Core/Instance/Flat.hs:233-244`, plutus
  * 1.70.0.0 `507cab8c`): on the type of each constant before its value is read, on each builtin as
  * it is read, and on the number of fields of each `constr` once they are read.
  */
final case class Checks(
    constant: DefaultUni => Either[Availability.Refusal, Unit],
    builtin: DefaultFun => Either[Availability.Refusal, Unit],
    constrFields: Int => Either[Availability.Refusal, Unit]
)

object Checks:
  private val pass: Either[Availability.Refusal, Unit] = Right(())

  /** None, as for an `UnrestrictedProgram` (`Flat.hs:288-305`). */
  val unrestricted: Checks = Checks(_ => pass, _ => pass, _ => pass)

  /** Those of the ledger's decoder for a script of `language` at `protocol` (`scriptCBORDecoder`,
    * `PlutusLedgerApi/Common/SerialisedScript.hs:195-234`): the builtins available to the language
    * it is evaluated as (`builtinsAvailableIn`) and the bounds of `maxBoundsByPV` on a constant's
    * type and the fields of a `constr`, computed once for the whole program.
    */
  def ledger(language: PlutusLanguage, protocol: ProtocolMajor): Checks =
    val available = Availability.builtins(language, protocol)
    Checks(
      uni => Availability.checkConstantType(protocol, uni),
      fun =>
        Either.cond(
          available.contains(fun),
          (),
          Availability.Refusal.BuiltinNotAvailable(fun, language, protocol)
        ),
      fields => Availability.checkConstrFields(protocol, fields)
    )

/** The Flat decoder of programs (`decodeProgram` and `decodeTerm`,
  * `UntypedPlutusCore/Core/Instance/Flat.hs:132-186`, 233-244, plutus 1.70.0.0 `507cab8c`), run as
  * `unflatWith` runs it (`PlutusCore/Flat/Run.hs:34-46`): the program, a filler, and nothing after.
  *
  * A term is as deep as its script, so the decoder is a machine that keeps the terms still open on
  * a list on the heap (ADR 0011) and reads the input in its order. It is not an unfold of
  * [[Term]]'s functor: the fields of a `constr` and the branches of a `case` are a Flat list with a
  * continuation bit before each element (`decodeListWith`,
  * `PlutusCore/Flat/Decoder/Strict.hs:60-88`), so a layer's children are known only once the list
  * is read, and a fold of the cells of such a list yields a list where a term is wanted. Each open
  * term below waits for one thing, so the machine needs no case that cannot arise.
  */
object FlatDecoder:

  /** The program the bytes encode (`unflatWith (decodeProgram ...)`): the version, three naturals
    * (`PlutusCore/FlatInstances.hs:177-179`), the term, then the filler that ends the input
    * (`postAlignedDecoder`, `PlutusCore/Flat/Filler.hs:77-81`) on its last bit (`strictDecoder`,
    * `PlutusCore/Flat/Decoder/Run.hs:19-24`).
    */
  def program(bytes: Bytes, checks: Checks): Either[FlatError, Program] =
    val input = Input(bytes)
    for
      major <- input.natural(0)
      minor <- input.natural(major.at)
      patch <- input.natural(minor.at)
      version <- ProgramVersion
        .of(major.value, minor.value, patch.value)
        .toRight(FlatError.NegativeVersion(major.value, minor.value, patch.value))
      term <- term(input, patch.at, version, checks)
      end <- input.filler(term.at)
      _ <- Either.cond(end == input.bitLength, (), FlatError.TooMuchSpace(end))
    yield Program(version, term.value)

  // A term whose subterms are being read: each waits for the next term the machine completes.
  private enum Open:
    case Lam, Delay, Force
    case Function
    case Argument(function: Term)

    /** The fields read so far, the latest last; a continuation bit follows each. */
    case Fields(tag: Word64, read: Vector[Term])
    case Scrutinee
    case Branches(scrutinee: Term, read: Vector[Term])

  private enum Step:
    case Read(at: Long)
    case Give(term: Term, at: Long)

  /** `decodeTerm` (`Flat.hs:141-186`): the term at `at` of a program of `version`. */
  def term(
      input: Input,
      at: Long,
      version: ProgramVersion,
      checks: Checks
  ): Either[FlatError, Got[Term]] =
    def refused(check: Either[Availability.Refusal, Unit]): Either[FlatError, Unit] =
      check.left.map(FlatError.Refused(_))

    // The terms of no subterm, and `constr` up to its fields (the version, then the tag).
    def leaf(tag: Int, from: Long): Either[FlatError, Got[Term]] = tag match
      case 0 => input.word64(from).map(g => Got(Term.variable(DeBruijn(g.value)), g.at))
      case 4 =>
        ConstantDecoder
          .read(input, from, checks.constant)
          .map(g => Got(Term.constant(g.value), g.at))
      case 6 => Right(Got(Term.error, from))
      case 7 =>
        input.bits(from, 7).flatMap { case Got(t, next) =>
          DefaultFun
            .fromFlatTag(t)
            .toRight(FlatError.UnknownBuiltinTag(t))
            .flatMap(fun => refused(checks.builtin(fun)).map(_ => Got(Term.builtin(fun), next)))
        }
      case other => Left(FlatError.UnknownTermTag(other))

    // The bit after a field or branch: whether another follows.
    def more(from: Long): Either[FlatError, Got[Boolean]] = input.bit(from)

    @tailrec def go(step: Step, open: List[Open]): Either[FlatError, Got[Term]] = step match
      case Step.Read(from) =>
        input.bits(from, 4) match
          case Left(err) => Left(err)
          case Right(Got(tag, next)) =>
            tag match
              case 1 => go(Step.Read(next), Open.Delay :: open)
              case 2 => go(Step.Read(next), Open.Lam :: open)
              case 3 => go(Step.Read(next), Open.Function :: open)
              case 5 => go(Step.Read(next), Open.Force :: open)
              case 8 =>
                refused(Availability.checkConstr(version)).flatMap(_ => input.word64(next)) match
                  case Left(err) => Left(err)
                  case Right(Got(constrTag, after)) =>
                    more(after) match
                      case Left(err) => Left(err)
                      case Right(Got(true, first)) =>
                        go(Step.Read(first), Open.Fields(constrTag, Vector.empty) :: open)
                      case Right(Got(false, end)) =>
                        refused(checks.constrFields(0)) match
                          case Left(err) => Left(err)
                          case Right(_) =>
                            go(Step.Give(Term.constr(constrTag, Vector.empty), end), open)
              case 9 =>
                refused(Availability.checkCase(version)) match
                  case Left(err) => Left(err)
                  case Right(_)  => go(Step.Read(next), Open.Scrutinee :: open)
              case _ =>
                leaf(tag, next) match
                  case Left(err)               => Left(err)
                  case Right(Got(term, after)) => go(Step.Give(term, after), open)
      case Step.Give(term, from) =>
        open match
          case Nil                       => Right(Got(term, from))
          case Open.Lam :: below         => go(Step.Give(Term.lam(term), from), below)
          case Open.Delay :: below       => go(Step.Give(Term.delay(term), from), below)
          case Open.Force :: below       => go(Step.Give(Term.force(term), from), below)
          case Open.Function :: below    => go(Step.Read(from), Open.Argument(term) :: below)
          case Open.Argument(f) :: below => go(Step.Give(Term.app(f, term), from), below)
          case Open.Fields(constrTag, read) :: below =>
            more(from) match
              case Left(err) => Left(err)
              case Right(Got(true, next)) =>
                go(Step.Read(next), Open.Fields(constrTag, read :+ term) :: below)
              case Right(Got(false, end)) =>
                val fields = read :+ term
                refused(checks.constrFields(fields.size)) match
                  case Left(err) => Left(err)
                  case Right(_)  => go(Step.Give(Term.constr(constrTag, fields), end), below)
          case Open.Scrutinee :: below =>
            more(from) match
              case Left(err) => Left(err)
              case Right(Got(true, next)) =>
                go(Step.Read(next), Open.Branches(term, Vector.empty) :: below)
              case Right(Got(false, end)) =>
                go(Step.Give(Term.caseOf(term, Vector.empty), end), below)
          case Open.Branches(scrutinee, read) :: below =>
            more(from) match
              case Left(err) => Left(err)
              case Right(Got(true, next)) =>
                go(Step.Read(next), Open.Branches(scrutinee, read :+ term) :: below)
              case Right(Got(false, end)) =>
                go(Step.Give(Term.caseOf(scrutinee, read :+ term), end), below)
    go(Step.Read(at), Nil)
