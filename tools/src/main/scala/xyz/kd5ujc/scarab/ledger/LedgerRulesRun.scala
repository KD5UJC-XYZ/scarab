// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.era.{
  EraTx as EraTxOf,
  EraTxBody,
  EraTxError,
  GovActionPurpose,
  GovRelation,
  Proposals,
  ProposalsReadError,
  Tx as EraTx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayApplyTxError,
  ConwayCertPredFailure,
  ConwayCertsPredFailure,
  ConwayDelegPredFailure,
  ConwayMempoolRun,
  ConwayRuleDefect,
  CertsEnv,
  ProposalsRecord
}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayLedgerState,
  Credential as StateCredential,
  Staking
}
import xyz.kd5ujc.scarab.node.mempool.{ConwayRead, EraTx as Submitted, MempoolState, Rejection}
import xyz.kd5ujc.scarab.node.ntc.{ConwayFailureCbor, RefusalPresentationError}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.StateLayout

/** Why Scarab gave a fixture no verdict. */
enum NoVerdict extends DomainError:
  /** The fixture cannot be read or run as it states. */
  case Fixture(cause: FixtureError)

  /** The rules ended without a verdict ([[LedgerDefect]]). */
  case Defect(cause: LedgerDefect)

  /** Conway's era rules ended without a verdict, for a defect of their own ([[ConwayRuleDefect]]).
    */
  case Rules(cause: ConwayRuleDefect)

  /** Conway's decoders refuse the transaction the flat decoders read: the node's two readings
    * disagree, which the pool takes as no verdict ([[ConwayRead.of]]).
    */
  case Projection(cause: EraTxError)

  /** The mempool's decoding refused the bytes for a reason other than their form. */
  case Rejected(cause: Rejection)

  /** The decoder or the rules threw. */
  case Threw(cause: Throwable)

  def message: String = this match
    case Fixture(cause)    => cause.message
    case Defect(cause)     => cause.message
    case Rules(cause)      => s"no verdict of Conway's rules: $cause"
    case Projection(cause) => s"Conway's decoders refuse the transaction: $cause"
    case Rejected(cause)   => cause.message
    case Threw(cause)      => s"threw: $cause"

/** Scarab's verdict on a fixture's transaction. */
enum LedgerRulesVerdict:
  /** The rules accepted the transaction. */
  case Accepted

  /** The mempool's decoder refused the transaction's bytes. */
  case Malformed(cause: TransactionDecodeProblem)

  /** The transaction holds what the reference's decoder refuses, which the rules meet
    * ([[LedgerFailure.Undecodable]]).
    */
  case Inadmissible(defect: TxDefect)

  /** The rules refused the transaction: its failures in the reference's order, with what their
    * names depend on. When the MEMPOOL rule refused it for what only the mempool refuses
    * (`dropped`, [[LedgerRulesNames.mempoolOnly]]), the failures are those of LEDGER alone, as a
    * block judges the transaction, and none means LEDGER accepted it.
    */
  case Refused(
      failures: Vector[LedgerRulesNames.Failure],
      dropped: Vector[LedgerRulesNames.Failure],
      context: FailureContext
  )

  case Unjudged(cause: NoVerdict)

/** How a refusal's reported name is chosen among the names of the failures Scarab raised. The
  * report gives one name for a transaction that may fail several predicates at once.
  */
enum Selection(val flag: String) derives CanEqual:
  /** The first failure, in the reference's order, that the schema has a name for. */
  case First extends Selection("first")

  /** The fixture's expected name when Scarab raised it among its failures, and the first otherwise:
    * the criterion of the corpus's reference harness, which passes a refusal when the expected
    * predicate is among the reference's failures (Amaru
    * `crates/amaru/tests/conformance/validation-rules/src/Command/ValidatePhaseOne/Run.hs`,
    * `validateDecodedTransaction`, at 40ba9950), and so of cardano-node's report. A name Scarab did
    * not raise is never reported, and an accepted transaction is a pass whatever the fixture
    * expects.
    */
  case ExpectedInFailures extends Selection("expected-in-failures")

object Selection:
  val Default: Selection = ExpectedInFailures

  def parse(text: String): Option[Selection] = values.find(_.flag == text)

/** The outcome of one fixture: its id (the first five digits of its file name), its file, Scarab's
  * verdict, and what the name of a refusal is chosen by.
  *
  * @param expected
  *   the name of the predicate the fixture expects a refusal for, read only by `selection`
  */
final case class LedgerRulesCase(
    id: String,
    file: String,
    verdict: LedgerRulesVerdict,
    expected: Option[ReportName] = None,
    selection: Selection = Selection.First
):
  import LedgerRulesVerdict.*

  /** The name a refusal is reported by and the rule that chose it: [[Selection.First]] when it is
    * the first name raised, [[Selection.ExpectedInFailures]] when that rule chose another.
    */
  def chosen: Option[(ReportName, Selection)] = verdict match
    case Refused(_, _, _) =>
      val first = names.headOption.map(_ -> Selection.First)
      val wanted = expected.filter(e =>
        selection == Selection.ExpectedInFailures && names.contains(e) && !names.headOption
          .contains(e)
      )
      wanted.map(_ -> Selection.ExpectedInFailures).orElse(first)
    case _ => None

  /** The report's result: `pass`, `DecodingFailure`, or the name of a refusal ([[chosen]]); none
    * (the fixture left out of the report, so counted as failed) when Scarab has no verdict or the
    * schema no name for any of its failures.
    */
  def result: Option[String] = verdict match
    case Accepted                        => Some(LedgerRulesCase.Pass)
    case Malformed(_) | Inadmissible(_)  => Some(ReportName.DecodingFailure.toString)
    case Refused(fs, _, _) if fs.isEmpty => Some(LedgerRulesCase.Pass)
    case Refused(_, _, _)                => chosen.map(_._1.toString)
    case Unjudged(_)                     => None

  /** Every name of every failure, in the reference's order: what the sidecar keeps for the reader
    * of the report.
    */
  def names: Vector[ReportName] = verdict match
    case Refused(fs, _, context)        => fs.flatMap(LedgerRulesNames.of(_, context)).distinct
    case Malformed(_) | Inadmissible(_) => Vector(ReportName.DecodingFailure)
    case _                              => Vector.empty

object LedgerRulesCase:
  val Pass = "pass"

  private val Id = """^([0-9]{5})-.*\.json$""".r

  /** The id of a fixture file, or none for a file that is not one. */
  def id(file: String): Option[String] = file match
    case Id(n) => Some(n)
    case _     => None

  /** The report entry of a case that has a result. */
  def entry(c: LedgerRulesCase): Option[Json] =
    c.result.map(r => Json.obj("id" -> c.id.asJson, "result" -> r.asJson))

  /** What the sidecar records of a case: the verdict, the result and each failure with its names.
    */
  private def unencodable(e: ConwayFailureCbor.Unencodable): String = e match
    case r: RefusalPresentationError              => r.message
    case n: ConwayFailureCbor.NoReferenceEncoding => n.message

  def detail(c: LedgerRulesCase): Json =
    def failure(f: LedgerRulesNames.Failure, context: FailureContext) = Json.fromFields(
      Vector(
        "rule" -> LedgerRulesNames.constructor(f).asJson,
        "names" -> LedgerRulesNames.of(f, context).map(_.toString).asJson
      ) ++ ConwayFailureCbor
        .ledger(f)
        .fold(
          e => Vector("cbor" -> Json.Null, "unencodable" -> unencodable(e).asJson),
          b => Vector("cbor" -> b.hex.asJson)
        )
    )
    val (kind, extra) = c.verdict match
      case LedgerRulesVerdict.Accepted     => ("accepted", Vector.empty)
      case LedgerRulesVerdict.Malformed(d) => ("undecodable", Vector("cause" -> d.message.asJson))
      case LedgerRulesVerdict.Inadmissible(d) =>
        ("undecodable", Vector("cause" -> d.message.asJson))
      case LedgerRulesVerdict.Refused(fs, dropped, context) =>
        (
          "refused",
          Vector(
            "judged_by" -> (if dropped.isEmpty then "MEMPOOL" else "LEDGER").asJson,
            "failures" -> fs.map(failure(_, context)).asJson,
            "dropped" -> dropped.map(failure(_, context)).asJson
          )
        )
      case LedgerRulesVerdict.Unjudged(cause) =>
        ("unjudged", Vector("cause" -> cause.message.asJson))
    Json.fromFields(
      Vector(
        "id" -> c.id.asJson,
        "file" -> c.file.asJson,
        "verdict" -> kind.asJson,
        "result" -> c.result.asJson,
        "selected" -> c.chosen.map(_._2.flag).asJson,
        "names" -> c.names.map(_.toString).asJson
      ) ++ extra
    )

/** One fixture judged by Scarab's production paths: the transaction decoded as the mempool decodes
  * a submitted one ([[MempoolState.parseParts]]) and read into Conway's types as the pool reads it
  * ([[ConwayRead.of]], in the layout of the 11.1 build, whose state the fixture's is), then applied
  * by Conway's MEMPOOL rule on the era types with every failure recorded
  * ([[ConwayMempoolRun.applyTx]], the reference's `applyTx` that the corpus's reference harness
  * calls), the scripts evaluated by `backend`.
  *
  * Below protocol version 11 the MEMPOOL rule refuses a vote of an unelected committee member,
  * which a block admits, before it runs LEDGER, so LEDGER then runs with a failure recorded and
  * skips what runs only when nothing has failed (the scripts, `whenFailureFree`,
  * `Conway/Rules/Mempool.hs` and `Alonzo/Rules/Utxos.hs`, cardano-ledger f649f975). Such a
  * transaction is judged again by Conway's LEDGER alone ([[LedgerRulesEra.ledger]]), with every
  * failure recorded, as LEDGERS judges it in a block.
  */
object LedgerRulesRun:
  /** The layout the fixture's state is in: the 11.1 build's, the build the judgment follows. */
  private val Layout = StateLayout.Node11_1

  def judge(
      file: String,
      text: String,
      document: String => Option[String],
      backend: PlutusBackend
  ): LedgerRulesVerdict =
    Either
      .catchNonFatal(verdict(file, text, document, backend))
      .valueOr(t => LedgerRulesVerdict.Unjudged(NoVerdict.Threw(t)))

  private def verdict(
      file: String,
      text: String,
      document: String => Option[String],
      backend: PlutusBackend
  ): LedgerRulesVerdict =
    val judged = for
      fixture <- LedgerRulesFixture.read(file, text, document).leftMap(NoVerdict.Fixture(_))
      context <- LedgerRulesState.build(fixture, backend).leftMap(NoVerdict.Fixture(_))
    yield MempoolState.parseParts(fixture.transaction) match
      case Left(Rejection.Malformed(cause))   => LedgerRulesVerdict.Malformed(cause)
      case Left(Rejection.InputDefect(cause)) => LedgerRulesVerdict.Unjudged(NoVerdict.Threw(cause))
      case Left(other) => LedgerRulesVerdict.Unjudged(NoVerdict.Rejected(other))
      case Right(parsed) =>
        ConwayRead.of(Submitted(parsed.parts, parsed.tx), Layout) match
          case Left(cause) => LedgerRulesVerdict.Unjudged(NoVerdict.Projection(cause))
          case Right(read) => applied(read.tx, context)
    judged.valueOr(LedgerRulesVerdict.Unjudged(_))

  private def applied(tx: EraTx[TxLevel.Top, Conway], c: LedgerRulesContext): LedgerRulesVerdict =
    ConwayMempoolRun.applyTx(c.judging, c.env, c.ledger, tx) match
      case Right(Right(_)) => LedgerRulesVerdict.Accepted
      case Right(Left(ConwayApplyTxError(failures))) =>
        val (dropped, kept) = failures.toChain.toVector.partition(LedgerRulesNames.mempoolOnly)
        if dropped.isEmpty then refused(tx, c, kept, dropped)
        else asBlock(tx, c, dropped)
      case Left(defect) => undecided(defect)

  /** LEDGER alone on the state MEMPOOL judged the transaction on, the transaction annotated against
    * it as MEMPOOL annotates it ([[ConwayMempoolRun.annotate]]).
    */
  private def asBlock(
      tx: EraTx[TxLevel.Top, Conway],
      c: LedgerRulesContext,
      dropped: Vector[LedgerRulesNames.Failure]
  ): LedgerRulesVerdict =
    val judging = c.judging
    ConwayMempoolRun
      .utxoOf(c.ledger, tx)
      .leftMap(ConwayRuleDefect.UtxoState(_))
      .flatMap(utxo =>
        LedgerRulesEra.ledger(
          judging.globals,
          c.env,
          judging.annotation(judging.slotToPOSIXTime, c.pp, utxo, tx),
          judging.context(TxEnv.Validation.All),
          c.ledger,
          judging.evaluator,
          judging.verdicts,
          judging.decoder
        )
      ) match
      case Right(Right(_))       => refused(tx, c, Vector.empty, dropped)
      case Right(Left(failures)) => refused(tx, c, failures.toChain.toVector, dropped)
      case Left(defect)          => undecided(defect)

  /** A defect of Conway's rules: a transaction holding what the reference's decoder refuses
    * ([[LedgerFailure.Undecodable]]) is inadmissible, as a decoding failure; any other defect is no
    * verdict.
    */
  private def undecided(defect: ConwayRuleDefect): LedgerRulesVerdict = defect match
    case ConwayRuleDefect.Ledger(LedgerFailure.Undecodable(_, d)) =>
      LedgerRulesVerdict.Inadmissible(d)
    case ConwayRuleDefect.Ledger(other) => LedgerRulesVerdict.Unjudged(NoVerdict.Defect(other))
    case other                          => LedgerRulesVerdict.Unjudged(NoVerdict.Rules(other))

  private def body(tx: EraTx[TxLevel.Top, Conway]) =
    EraTxOf.conway.body[TxLevel.Top](tx)(using EraTxOf.conway.bodyOf.top)

  private def refused(
      tx: EraTx[TxLevel.Top, Conway],
      c: LedgerRulesContext,
      kept: Vector[LedgerRulesNames.Failure],
      dropped: Vector[LedgerRulesNames.Failure]
  ): LedgerRulesVerdict =
    val needed = kept.exists(unregisteredCredential(_).isDefined)
    val attributed =
      if needed then unregistered(tx, c)
      else Right(Map.empty[StateCredential[Staking], CertificateKind])
    attributed.fold(
      e => LedgerRulesVerdict.Unjudged(NoVerdict.Rules(ConwayRuleDefect.ProposalsRead(e))),
      a =>
        LedgerRulesVerdict.Refused(
          kept,
          dropped,
          FailureContext(
            EraTxBody.conway.inputs[TxLevel.Top](body(tx))(using EraTxBody.conway.top).nonEmpty,
            a
          )
        )
    )

  private def unregisteredCredential(
      f: LedgerRulesNames.Failure
  ): Option[StateCredential[Staking]] =
    f match
      case era.rules.ConwayLedgerPredFailure.ConwayCertsFailure(failure) =>
        certsUnregistered(failure)
      case _ => None

  private def certsUnregistered(
      f: ConwayCertsPredFailure[Conway]
  ): Option[StateCredential[Staking]] = f match
    case ConwayCertsPredFailure.CertFailure(
          ConwayCertPredFailure.DelegFailure(ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(c))
        ) =>
      Some(c)
    case _ => None

  /** The certificate that refused each credential as unregistered. CERTS recurses on the prefix of
    * the certificates (`Conway/Rules/Certs.hs:242-246`, `gamma :|> c`), so Conway's CERTS
    * ([[LedgerRulesEra.certs]]) is judged on each prefix, from the certificate state and in the
    * environment LEDGER gives it (`Conway/Rules/Ledger.hs:394-398`, cardano-ledger f649f975: the
    * committee and the actions of the committee's purpose, `proposalsWithPurpose grCommitteeL`),
    * and the certificate after which a credential's refusals grow is the one that refused it. From
    * protocol version 11 LEDGER drains the withdrawals before CERTS, which changes no registration.
    * A credential refused by certificates of more than one kind is left out: its failures do not
    * say which is which. The proposals are the node's record, which the era cannot always read
    * ([[ProposalsReadError]]).
    */
  private def unregistered(
      tx: EraTx[TxLevel.Top, Conway],
      c: LedgerRulesContext
  ): Either[ProposalsReadError, Map[StateCredential[Staking], CertificateKind]] =
    val ls = c.ledger
    val gov = ConwayLedgerState.gov.get(ls)
    val record = ProposalsRecord.conway
    record.read(gov.proposals).map { proposals =>
      val committeeProposals = Proposals
        .proposalsWithPurpose[Conway, GovActionPurpose.Committee](
          GovRelation.grCommitteeL,
          proposals
        )
        .valuesIterator
        .map(gas => gas.gasId -> record.storedAction(gas))
        .toMap
      val env = CertsEnv[Conway](tx, c.pp, c.epoch, gov.committee, committeeProposals)
      val certificates =
        EraTxBody.conway.certs[TxLevel.Top](body(tx))(using EraTxBody.conway.top)
      def refusals(prefix: Int): Map[StateCredential[Staking], Int] =
        LedgerRulesEra.certs(
          c.judging.globals,
          env,
          ConwayLedgerState.cert.get(ls),
          certificates.take(prefix)
        ) match
          case Right(Left(failures)) =>
            failures.toChain.toVector
              .flatMap(certsUnregistered)
              .groupMapReduce(identity)(_ => 1)(_ + _)
          case _ => Map.empty
      val counts = (0 to certificates.size).map(refusals)
      certificates.indices
        .flatMap { i =>
          val grown = counts(i + 1).collect { case (k, n) if n > counts(i).getOrElse(k, 0) => k }
          grown.flatMap(k => CertificateKind.of(certificates(i)).map(k -> _))
        }
        .groupMap(_._1)(_._2)
        .collect { case (k, kinds) if kinds.distinct.size == 1 => kinds.headOption.map(k -> _) }
        .flatten
        .toMap
    }
