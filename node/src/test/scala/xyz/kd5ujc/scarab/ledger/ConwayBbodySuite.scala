// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyChain
import java.nio.file.{Files, Path}
import cats.effect.IO
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.Block as FlatBlock
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraPParams,
  EraBlockBody,
  EraBlockHeader,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  IsPhase2Valid,
  PParams,
  PraosEraBlockHeader,
  ProtVer,
  ScriptIntegrity,
  StoredTxOut,
  TxLevel,
  UTxO,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.Mismatch as EraMismatch
import xyz.kd5ujc.scarab.ledger.era.Relation.{RelEQ, RelLTEQ}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayBlock, ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraImpBatch as B
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoBbody,
  AlonzoBbodyPredFailure,
  BbodyEnv,
  BbodyPredFailure,
  ConwayBbody,
  ConwayBbodyPredFailure,
  ConwayBlockRun,
  ConwayPhase2,
  ConwayRuleDefect,
  HeaderProtVerGate,
  InjectBbodyFailure,
  ShelleyBbody,
  ShelleyBbodyPredFailure,
  ShelleyBbodyState,
  ShelleyLedgersPredFailure,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Bbody as FlatBbody,
  EraBlock,
  EraNes,
  Globals,
  TxDependencies
}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Coin,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayNewEpochState,
  LedgerState,
  Network,
  NewEpochState,
  ShelleyBasedEra,
  Staking,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusLanguage,
  ScarabEvaluator,
  ScriptDecodeFailure,
  ScriptDecoder,
  ScriptEvaluator,
  ScriptFailure
}
import xyz.kd5ujc.scarab.uplc.cek.{CekError, CekFailure}
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup, ParallelPlutus}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}

/** Conway's BBODY on the era types (`ConwayBbody.conwayBbody`, run by `ConwayBlockRun`;
  * `Conway/Rules/Bbody.hs` and the Alonzo and Shelley BBODY functions it runs, cardano-ledger
  * f649f975) against the flat BBODY the node runs for Conway (`rules.Bbody` through
  * `EraBlock.body`), from the same ticked ledger state, as `MirrorRules` ticks it, on the same
  * block's bytes, each side reading them with its own decoder:
  *   - every recorded Conway block of the era-walking captures (`fixtures/eras` and
  *     `fixtures/chain-dep`, each replayed from genesis by the node's rules), which the reference
  *     node accepted: both admit it with the same ledger state and blocks made on its testnet, and
  *     as if on mainnet, where the header's version is checked, both refuse it for that version
  *     (the clusters' headers declare major 11 over parameters at 9);
  *   - refusals crafted from a recorded block's bytes: another declared body size, another declared
  *     hash, a header major version past the one after the parameters' on mainnet (which the flat
  *     side checks in the envelope, `consensus.HardForkConfig.check`, before its BBODY, and its
  *     BBODY alone admits), each refused by both sides with equal failures in the node's flat form,
  *     and each boundary admitted by both;
  *   - blocks of built transactions (real CBOR and signatures, a reference script and a Plutus V3
  *     script of the reference's examples) on the state before the third Conway block of
  *     `fixtures/eras` with the outputs they spend: reference scripts past the block's limit, a
  *     block's units past `maxBlockExUnits` in either component, and a transaction whose input is
  *     missing, refused by both with equal failures, and the same blocks within the limits admitted
  *     by both with the same state, a script run among them;
  *   - the era block reads every recorded Conway block (the captures' and the Preview blocks of
  *     `fixtures/preview`) as the flat decoder reads it, and the units of a block's transactions
  *     are the flat rules' sum.
  *
  * The flat form of an era failure (`flat`): the body's size or hash `BodyCommitment`, which holds
  * neither; the units `BlockExUnitsTooBig`; the reference scripts `RefScriptsTooLarge`; the
  * header's version the envelope's `HeaderProtVerTooHigh`; LEDGERS's failures the transaction's
  * `Rejected`, through `EraLedgerRun.flat`. The flat BBODY stops at its first failure, the era's
  * records every one (`ValidateAll`, the accumulation the flat rules take on in #412), so each
  * refusal here fails one check, and the two lists are compared whole; a named difference pins the
  * accumulation on a block failing both the size and the hash.
  *
  * Incomplete (CNF-2): no recording holds a Conway block's refusal or a Conway block with
  * transactions and the state before it, so the refusals are crafted, the transactions built, and
  * the reference's verdict on a whole block with transactions is not compared (the Preview blocks
  * with the state #1324 builds are the next evidence); the second clause of the 11.1 build's gate
  * of the header's version (`HeaderProtVerGate`) is never met with a failure, since from major 12
  * the version after it is the last (13) and no header's major decodes past it.
  */
class ConwayBbodySuite extends FailsOnUncaught:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def ok[A](what: String)(e: Either[?, A]): A = e.fold(x => fail(s"$what: $x"), identity)

  private val layout: StateLayout = ok("layout")(StateLayout.of(ReferenceBuild.Release11_1))

  // ---- the recorded chains --------------------------------------------------------------------------

  /** A Conway block of a recorded chain with the node's state before it. */
  private final case class Before(
      name: String,
      setup: NodeSetup,
      mirror: MirrorState,
      raw: Bytes
  )

  private def lines(path: Path): Vector[Bytes] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(l => ok(s"$path")(Bytes.fromHex(l)))

  /** The Conway blocks of the capture in `dir`, each with the state the node's rules leave before
    * it, the chain replayed from genesis.
    */
  private def conwayOf(dir: String): Vector[Before] =
    val setup =
      ok(dir)(NodeSetup.fromDirectory(ReferenceBuild.Release11_1)(Path.of(dir, "genesis")))
    val raws = lines(Path.of(dir, "blocks.hex"))
    raws.zipWithIndex
      .foldLeft((setup.mirrorStart, Vector.empty[Before])) { case ((state, acc), (raw, i)) =>
        val block = ok(s"$dir block $i")(FlatBlock.decode(raw, setup.geometry.byron.epochSlots))
        val next = ok(s"$dir block $i")(MirrorSteps.block(setup, state, block.header, raw))
        val seen =
          if block.era == Era.Conway then acc :+ Before(s"$dir block $i", setup, state, raw)
          else acc
        (next, seen)
      }
      ._2

  private lazy val eras: Vector[Before] = conwayOf("fixtures/eras")
  private lazy val chainDep: Vector[Before] = conwayOf("fixtures/chain-dep")

  // ---- the two BBODYs on one ticked state ----------------------------------------------------------

  /** What a BBODY made of a block: its failures in the node's flat form, or the ledger state and
    * the blocks made after it.
    */
  private type Verdict = Either[Vector[BlockFailure], (LedgerState[Conway], BlocksMade)]

  private def same(name: String, era: Verdict, flat: Verdict): Unit =
    (era, flat) match
      case (Right((ls, made)), Right((flatLs, flatMade))) =>
        assert(cats.Eq[LedgerState[Conway]].eqv(ls, flatLs), s"$name: the ledger states differ")
        assertEquals(made, flatMade, s"$name: blocks made")
      case _ => assertEquals(era, flat, name)

  /** The ticked Conway state of a block and what each side reads of the run: the era BBODY's own
    * failures as well as their flat form, and the scripts each side's evaluator ran.
    */
  private final case class Judged(
      eraRun: Either[ConwayRuleDefect, Verdict],
      flat: Verdict,
      ticked: NewEpochState[Conway],
      flatPlutus: Option[PlutusStats],
      eraPlutus: Option[PlutusStats],
      flatBbodyAdmits: Boolean,
      eraRefusal: Option[ConwayBlockRun.Refusal],
      eraFailures: Vector[BbodyPredFailure[Conway]],
      eraEvaluated: Int,
      flatEvaluated: Int,
      eraObserved: Vector[Phase2.Observation],
      flatObserved: Vector[Phase2.Observation],
      deferred: Vector[Vector[Int]],
      replayed: Option[ConwayBlockRun.Observing] => ConwayBlockRun.Body,
      program: (
          ScriptEvaluator[cats.Id],
          Option[ConwayBlockRun.Observing],
          Verdicts
      ) => ConwayBlockRun.Body,
      replaying: () => ScriptEvaluator[cats.Id]
  ):
    /** The era run's verdict, where it reached one. */
    def era: Verdict = eraRun.fold(defect => fail(s"era defect $defect"), identity)

  /** A script's failure in a form that compares by value: a machine's failure by its error and
    * whether the reference attaches a term to it (`ErrorWithCause`), since the term (`Cause`, read
    * only when asked for) compares by identity; every other failure as it is, whose fields compare
    * by value.
    */
  private enum FailureForm derives CanEqual:
    case Machine(error: CekError, caused: Boolean, logs: Vector[String])
    case Other(failure: EvalFailure)

  private def formOf(failure: EvalFailure): FailureForm = failure match
    case EvalFailure.Script(
          ScriptFailure.Uplc(EvaluationError.Cek(CekFailure(error, cause)), logs)
        ) =>
      FailureForm.Machine(error, cause.isDefined, logs)
    case EvalFailure.Script(_) | EvalFailure.Decode(_) | EvalFailure.Unsupported(_) |
        EvalFailure.OutOfBudget | EvalFailure.OpenTerm(_) =>
      FailureForm.Other(failure)

  /** A script's result with its failure in its comparable form ([[FailureForm]]). */
  private enum ResultForm derives CanEqual:
    case Passed(used: ExUnits, logs: Vector[String])
    case Failed(failure: FailureForm, used: ExUnits, logs: Vector[String])

  private type EvaluationForm = (Redeemer, EvalRequest, ResultForm)

  /** What an observation says of its scripts, each failure in its comparable form. */
  private enum OutcomeForm derives CanEqual:
    case Passed(evaluations: Vector[EvaluationForm])
    case Failed(
        evaluations: Vector[EvaluationForm],
        failures: Vector[(Redeemer, EvalRequest, FailureForm)]
    )
    case Unavailable(
        evaluations: Vector[EvaluationForm],
        redeemer: Redeemer,
        reason: EvalFailure.Unsupported
    )

  /** An observation as it compares: every field as it is, its outcome in its comparable form. */
  private final case class ObservationForm(
      tx: Tx,
      params: ProtocolParams,
      slot: BigInt,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      clock: Option[PosixClock],
      outcome: Phase2.Collected[OutcomeForm]
  )

  private def comparable(o: Phase2.Observation): ObservationForm =
    def result(r: EvalResult): ResultForm = r match
      case EvalResult.Success(used, logs)         => ResultForm.Passed(used, logs)
      case EvalResult.Failure(reason, used, logs) => ResultForm.Failed(formOf(reason), used, logs)
    def evaluations(report: Phase2.Report): Vector[EvaluationForm] =
      report.evaluations.map(e => (e.redeemer, e.request, result(e.result)))
    val outcome = o.outcome.map {
      case Phase2.Outcome.Passed(report) => OutcomeForm.Passed(evaluations(report))
      case Phase2.Outcome.Failed(report, failures) =>
        OutcomeForm.Failed(
          evaluations(report),
          failures.toVector.map(f => (f.redeemer, f.request, formOf(f.reason)))
        )
      case Phase2.Outcome.Unavailable(report, redeemer, reason) =>
        OutcomeForm.Unavailable(evaluations(report), redeemer, reason)
    }
    ObservationForm(o.tx, o.params, o.slot, o.spent, o.references, o.clock, outcome)

  /** The phase-2 observations a side's observer is told (`--plutus-cases`), in order. */
  private final class Observations:
    private val told =
      new java.util.concurrent.atomic.AtomicReference(Vector.empty[Phase2.Observation])
    def observe(o: Phase2.Observation): Unit = { val _ = told.updateAndGet(_ :+ o); () }
    def all: Vector[Phase2.Observation] = told.get

  /** The node's evaluator, counting the scripts it is asked to run, and giving no verdict on the
    * `unsupportedAt` th, as an evaluator's limit does (`EvalFailure.Unsupported`).
    */
  private final class Counting(unsupportedAt: Option[Int]) extends ScriptEvaluator[cats.Id]:
    private val runs = new java.util.concurrent.atomic.AtomicInteger(0)
    def evaluate(request: EvalRequest): EvalResult =
      val n = runs.incrementAndGet()
      if unsupportedAt.contains(n) then
        EvalResult.Failure(
          xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(
            xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted
          ),
          ExUnits.zero,
          Vector.empty
        )
      else ScarabEvaluator.default.evaluate(request)
    def count: Int = runs.get

  /** The era run's evaluator ([[Counting]]), each result it gives kept by its request and the place
    * of the request among the equal ones asked before; and the same results given again
    * ([[replaying]]), each request answered by the result of its place, so that a prepared plan
    * sees the serial run's results (a failure's term compares by identity), whatever the order its
    * jobs run in. A request the serial run never asked is the node's evaluator's.
    */
  private final class Shared(unsupportedAt: Option[Int]) extends ScriptEvaluator[cats.Id]:
    private val counting = Counting(unsupportedAt)
    private val results =
      new java.util.concurrent.atomic.AtomicReference(Map.empty[(EvalRequest, Int), EvalResult])
    private def placed(
        asked: java.util.concurrent.atomic.AtomicReference[Map[EvalRequest, Int]],
        request: EvalRequest
    ): Int =
      asked
        .getAndUpdate(m => m.updated(request, m.getOrElse(request, 0) + 1))
        .getOrElse(request, 0)
    private val asked = new java.util.concurrent.atomic.AtomicReference(Map.empty[EvalRequest, Int])
    def evaluate(request: EvalRequest): EvalResult =
      val place = placed(asked, request)
      val result = counting.evaluate(request)
      val _ = results.updateAndGet(_.updated((request, place), result))
      result
    def count: Int = counting.count
    def replaying: ScriptEvaluator[cats.Id] =
      val again = new java.util.concurrent.atomic.AtomicReference(Map.empty[EvalRequest, Int])
      request =>
        results.get.getOrElse(
          (request, placed(again, request)),
          ScarabEvaluator.default.evaluate(request)
        )

  /** The prepared plan's result is the serial era run's: the same BBODY state, blocks made and
    * counters, or the same failures, defect and naming.
    */
  private def samePlanned(
      name: String,
      serial: ConwayBlockRun.Result,
      planned: Either[Phase2.Defect, ConwayBlockRun.Result]
  ): Unit =
    planned match
      case Left(defect) => fail(s"$name: the plan's defect $defect")
      case Right(Right(Right(p))) =>
        serial match
          case Right(Right(s)) =>
            assert(cats.Eq[LedgerState[Conway]].eqv(p.state.ls, s.state.ls), s"$name: plan state")
            assertEquals(p.state.blocksMade, s.state.blocksMade, s"$name: plan blocks made")
            assertEquals(p.plutus, s.plutus, s"$name: plan counters")
          case other => fail(s"$name: the plan admits the block the serial run judges $other")
      case Right(other) => assertEquals(other, serial, s"$name: the plan")

  /** The state of `before` ticked to the block `raw` as `MirrorRules` ticks it (stamped with the
    * block's number, then the combinator's tick), changed by `change`; then the era BBODY and the
    * flat side on it: the envelope's check of the header's version (`HardForkConfig.check`, as
    * `LedgerRules` calls it), then the flat BBODY (`EraBlock.body`), each on `network` under
    * `build`.
    */
  private def judge(
      before: Before,
      raw: Bytes,
      network: Network = Network.Testnet,
      build: ReferenceBuild = ReferenceBuild.Release11_1,
      change: NewEpochState[Conway] => NewEpochState[Conway] = identity,
      unsupportedAt: Option[Int] = None,
      observe: Boolean = false,
      decoder: ScriptDecoder[cats.Id] = ScarabEvaluator.defaultDecoder
  ): Judged =
    val setup = before.setup
    val block = FlatBlock.decodeShelley(raw) match
      case Right(b: FlatBlock.Shelley) => b
      case other                       => fail(s"${before.name}: not a Shelley block: $other")
    val header = block.header
    val geometry = setup.geometry
    val globals = Globals(
      setup.globals.copy(network = network, referenceBuild = build),
      geometry
    )
    val (flatEvaluator, eraEvaluator) = (Counting(unsupportedAt), Shared(unsupportedAt))
    val (flatObserved, eraObserved, planObserved) = (Observations(), Observations(), Observations())
    // one clock, which both sides' observations hold
    val clock = PosixClock.total(globals.systemStart, geometry)
    val ctx = EraBlock.Context(
      globals,
      setup.praos.forks.transition(geometry.k),
      MirrorStep.afterVotingOf(before.mirror),
      verdicts = Verdicts.Direct,
      evaluator = flatEvaluator,
      observer = Option.when(observe)(flatObserved.observe),
      decoder = decoder,
      clock = Some(clock)
    )
    val nes = MirrorStep.nesOf(before.mirror).getOrElse(fail(s"${before.name}: no Shelley state"))
    val stamped = EraNes.mapUtxo(nes)(_.at(header.blockNo.toLong))
    val ticked: NewEpochState[Conway] =
      ok(s"${before.name} tick")(EraBlock.ticked(ctx)(stamped, header, block)) match
        case i: EraNes.InEra[e] =>
          i.era match
            case ShelleyBasedEra.SConway => change(i.nes)
            case other                   => fail(s"${before.name}: ticked into $other")
    val params = ConwayNewEpochState.gov.get(ticked).curPParams
    // the flat side: the envelope's check of the header's version, then the flat BBODY
    val body = EraBlock.body(ctx)(
      EraNes.InEra(ShelleyBasedEra.SConway, ticked),
      header,
      block,
      EraBlock.decode(block),
      PlutusStats.zero
    )
    val outcome: Either[Vector[BlockFailure], EraBlock.Outcome] = setup.praos.forks
      .check(
        header.era,
        Era.Conway,
        geometry.epochOf(header.slot),
        header.protocolVersion._1,
        params.protocolMajor,
        header.raw.size,
        params.maxBlockHeaderSize,
        block.bodySize,
        params.maxBlockBodySize,
        mainnet = network == Network.Mainnet
      ) match
      case Left(error) => Left(Vector(BlockFailure.Envelope(error)))
      case Right(_)    => Right(body)
    val flat: Verdict = outcome.flatMap[Vector[BlockFailure], (LedgerState[Conway], BlocksMade)] {
      case EraBlock.Outcome.Applied(after, _) =>
        after match
          case i: EraNes.InEra[e] =>
            i.era match
              case ShelleyBasedEra.SConway => Right((i.nes.es.ls, i.nes.bcur))
              case other                   => fail(s"${before.name}: applied in $other")
      case EraBlock.Outcome.Refused(failure) => Left(Vector(failure))
      case EraBlock.Outcome.NotInEra(era)    => fail(s"${before.name}: not in era $era")
    }
    // the era side, on the same state stamped as the flat BBODY stamps it
    val eraBlock = ok(s"${before.name} era block")(ConwayBlock.decode(raw, layout))
    val pp = PParams[Conway](ok(s"${before.name} parameters")(ConwayPParams.fromStored(params)))
    val ls = ticked.es.ls
    val state = ShelleyBbodyState[Conway](
      ls.copy[Conway](utxo =
        ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo.at(header.blockNo.toLong)))
      ),
      ticked.bcur
    )
    // the network, the validation policy and the build's injection are the run's own, from the
    // globals; the system start the globals' (the Shelley genesis's), and the epoch information
    // of one era from the cluster's start
    def programOf(
        evaluator: ScriptEvaluator[cats.Id],
        observing: Option[ConwayBlockRun.Observing],
        verdicts: Verdicts = Verdicts.Direct
    ): ConwayBlockRun.Body = ConwayBlockRun
      .program(
        globals,
        BbodyEnv[Conway](pp, ticked.es.chainAccount),
        eraBlock,
        globals.systemStart,
        Horizon(
          cats.data.NonEmptyVector.one(
            Horizon.Era(
              Horizon.Edge(0, 0, 0),
              None,
              Horizon.Params(
                geometry.epochLength,
                geometry.slotLengthMillis,
                geometry.stabilityWindow.toBigInt,
                geometry.stabilityWindow.toBigInt
              )
            )
          )
        ),
        state,
        evaluator,
        verdicts,
        decoder,
        clock.posix,
        observing
      )
    val run = programOf(
      eraEvaluator,
      Option.when(observe)(ConwayBlockRun.Observing(eraObserved.observe, Some(clock)))
    ).serial
    // the era run prepared, its jobs run last batch first, each batch's last first, with the
    // serial run's results: the same result and observations as the serial run's
    val plan = programOf(
      eraEvaluator.replaying,
      Option.when(observe)(ConwayBlockRun.Observing(planObserved.observe, Some(clock)))
    ).prepare
    samePlanned(
      before.name,
      run,
      plan.resolve(plan.batches.reverse.flatMap(_.reverse).map(plan.execute))
    )
    assertEquals(
      planObserved.all.map(comparable),
      eraObserved.all.map(comparable),
      s"${before.name}: the plan's observations"
    )
    // the graph of the era's transactions is the flat rules'
    EraBlockBody.conway
      .txSeqBlockBody(eraBlock.blockBody)
      .zip(EraBlock.decode(block))
      .foreach {
        case (tx, Right(flatTx)) =>
          assertEquals(
            ConwayPhase2.footprint(tx),
            TxDependencies.Footprint.of(flatTx),
            s"${before.name}: footprint"
          )
        case (_, Left(_)) => ()
      }
    val judged = run.toOption
    val era: Either[ConwayRuleDefect, Verdict] = run.map {
      case Left(refusal) => Left(flatOf(before.name, refusal.failures, block))
      case Right(after)  => Right((after.state.ls, after.state.blocksMade))
    }
    Judged(
      era,
      flat,
      ticked,
      outcome.toOption.collect { case EraBlock.Outcome.Applied(_, plutus) => plutus },
      judged.flatMap(_.toOption).map(_.plutus),
      body match
        case EraBlock.Outcome.Applied(_, _) => true
        case _                              => false
      ,
      judged.flatMap(_.left.toOption),
      judged.flatMap(_.left.toOption).fold(Vector.empty)(_.failures.toChain.toVector),
      eraEvaluator.count,
      flatEvaluator.count,
      eraObserved.all,
      flatObserved.all,
      plan.batches.map(_.map(_.index)),
      o => programOf(eraEvaluator.replaying, o),
      programOf,
      () => eraEvaluator.replaying
    )

  /** The era BBODY's failures in the node's flat form, in their order; LEDGERS's, which the flat
    * form holds by the transaction refused, as that of the block's one transaction, in the place of
    * the first of them (none in a block of more transactions).
    */
  private def flatOf(
      name: String,
      fs: NonEmptyChain[BbodyPredFailure[Conway]],
      block: FlatBlock.Shelley
  ): Vector[BlockFailure] =
    val all = fs.toChain.toVector
    val ledgers = all.collect {
      case ConwayBbodyPredFailure.LedgersFailure(ShelleyLedgersPredFailure.LedgerFailure(f)) =>
        ok(s"$name: flat")(EraLedgerRun.flat(f, _ => None, _ => None))
    }
    // a block of more transactions has no flat form here: the era's failures do not say which
    // transaction each is of, and the flat rules stop at the first refused one
    lazy val rejected = NonEmptyChain.fromSeq(ledgers).flatMap { fs =>
      block.transactions match
        case Vector(t) => Some(BlockFailure.Tx(LedgerFailure.Rejected(t.id, fs)))
        case _         => None
    }
    val firstLedgers = all.indexWhere {
      case ConwayBbodyPredFailure.LedgersFailure(_) => true
      case _                                        => false
    }
    all.zipWithIndex.flatMap {
      case (ConwayBbodyPredFailure.WrongBlockBodySizeBBODY(_), _) |
          (ConwayBbodyPredFailure.InvalidBodyHashBBODY(_), _) =>
        Vector(BlockFailure.BodyCommitment)
      case (ConwayBbodyPredFailure.TooManyExUnits(m), _) =>
        Vector(BlockFailure.BlockExUnitsTooBig(m.supplied, m.expected))
      case (ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig(m), _) =>
        Vector(BlockFailure.RefScriptsTooLarge(m.stored))
      case (ConwayBbodyPredFailure.HeaderProtVerTooHigh(m), _) =>
        Vector(
          BlockFailure.Envelope(
            EnvelopeError.HeaderProtVerTooHigh(BigInt(m.supplied.value), BigInt(m.expected.value))
          )
        )
      case (ConwayBbodyPredFailure.LedgersFailure(_), i) =>
        if i == firstLedgers then rejected.toVector else Vector.empty
    }

  // ---- blocks as bytes -------------------------------------------------------------------------------

  private def node(raw: Bytes): Node = ok("cbor")(Cbor.decode(raw, Cbor.Limits.Unbounded))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def array(parts: Vector[Bytes]): Bytes =
    val n = parts.size
    val head =
      if n < 24 then Bytes.fromArray(Array((0x80 + n).toByte))
      else if n < 256 then Bytes.fromArray(Array(0x98.toByte, n.toByte))
      else Bytes.fromArray(Array(0x99.toByte, (n >> 8).toByte, n.toByte))
    parts.foldLeft(head)(_ ++ _)

  private def encoded(v: Value): Bytes = B.encode(B.leaf(v))

  /** The block `raw`, `[7,
    * [[header body, signature], bodies, witnesses, auxiliary data, invalid]]`, with its body's four
    * parts and its header body's fields as `body` and `header` make them from theirs, every other
    * item the bytes it was read as.
    */
  private def crafted(raw: Bytes)(
      body: Vector[Bytes] => Vector[Bytes],
      header: (Vector[Bytes], Vector[Bytes]) => Vector[Bytes]
  ): Bytes =
    val outer = items(node(raw))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val parts = body(inner.drop(1).map(_.original))
    val newHeader = array(
      Vector(array(header(items(signed(0)).map(_.original), parts)), signed(1).original)
    )
    array(Vector(outer(0).original, array(newHeader +: parts)))

  /** A header body's fields declaring the size and hash (fields 6 and 7) of the body `parts`: their
    * bytes' length and the digest of their digests (`hashAlonzoSegWits`).
    */
  private def committing(fields: Vector[Bytes], parts: Vector[Bytes]): Vector[Bytes] =
    fields
      .updated(6, encoded(Value.UInt(BigInt(parts.map(_.size).sum))))
      .updated(
        7,
        encoded(
          Value.ByteString(
            Blake2b.hash256.hash(Bytes(parts.flatMap(p => Blake2b.hash256.hash(p).value)))
          )
        )
      )

  /** `fields` with the header's protocol version (field 9) of major `major`, its minor kept. */
  private def withMajor(fields: Vector[Bytes], major: Int): Vector[Bytes] =
    val minor = items(node(fields(9)))(1).original
    fields.updated(9, array(Vector(encoded(Value.UInt(BigInt(major))), minor)))

  // ---- the recorded blocks ---------------------------------------------------------------------------

  test(
    "every recorded Conway block of the captures and of fixtures/preview: read by the era decoder as the flat decoder reads it"
  ) {
    assertEquals(eras.size, 3)
    assertEquals(chainDep.size, 50)
    // every Conway block (tag 7) of every block file of fixtures/preview
    val previewFiles = Files
      .list(Path.of("fixtures", "preview"))
      .iterator
      .asScala
      .toVector
      .filter(_.getFileName.toString.endsWith(".hex"))
      .sortBy(_.getFileName.toString)
    val preview = previewFiles.flatMap(f =>
      lines(f).zipWithIndex.collect {
        case (raw, i) if FlatBlock.decodeShelley(raw).exists(_.era == Era.Conway) =>
          (s"${f.getFileName} $i", raw)
      }
    )
    assertEquals(preview.size, 58)
    val recorded = (eras ++ chainDep).map(b => (b.name, b.raw)) ++ preview
    val withTxs = recorded.map { (name, raw) =>
      val flat = FlatBlock.decodeShelley(raw) match
        case Right(b: FlatBlock.Shelley) if b.era == Era.Conway => b
        case other                                              => fail(s"$name: $other")
      val block = ok(name)(ConwayBlock.decode(raw, layout))
      val bb = EraBlockBody.conway
      val h = EraBlockHeader.conway
      val t = EraTx.conway
      val txs = bb.txSeqBlockBody(block.blockBody)
      assertEquals(
        txs.map(tx =>
          t.bodyOf.id[TxLevel.Top](t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using t.bodyOf.top)
        ),
        flat.transactions.map(_.id),
        name
      )
      assertEquals(
        txs.map(t.isValid),
        flat.transactions.map(x => IsPhase2Valid.of(x.isValid)),
        name
      )
      assertEquals(bb.hashBlockBody(block.blockBody).bytes, flat.bodyHash, name)
      val pv = PraosEraBlockHeader.conway.protVerBlockHeader(block)
      assertEquals(bb.blockBodySize(pv, block.blockBody), flat.bodySize, name)
      assertEquals(BigInt(h.blockBodySizeBlockHeader(block)), flat.header.bodySize, name)
      assertEquals(h.blockBodyHashBlockHeader(block).bytes, flat.header.bodyHash, name)
      assertEquals(h.slotNoBlockHeader(block).toBigInt, flat.header.slot, name)
      assertEquals(
        h.blockIssuerBlockHeader(block).bytes,
        Blake2b.hash224.hash(flat.header.issuerVkey),
        name
      )
      assertEquals((BigInt(pv.pvMajor.value), pv.pvMinor.value), flat.header.protocolVersion, name)
      // the units of the block's transactions are the flat rules' sum, refused one step below it
      val units = flat.transactions
        .map(x => ok(name)(Tx.fromBlock(x, Era.Conway)))
        .foldLeft(ExUnits.zero)(_ + _.witnesses.totalExUnits)
      assert(AlonzoBbody.validateExUnits[Conway](txs, units)(using t, t.bodyOf.top).isValid, name)
      if units.mem > 0 then
        val below = ExUnits(units.mem - 1, units.steps)
        assertEquals(
          AlonzoBbody.validateExUnits[Conway](txs, below)(using t, t.bodyOf.top),
          cats.data.Validated.invalidNec(
            new AlonzoBbodyPredFailure.TooManyExUnits[Conway](EraMismatch(units, below))
          ),
          name
        )
      txs.size
    }
    // the Preview blocks hold transactions, some with redeemers
    assertEquals(withTxs.sum, 76)
  }

  test(
    "every recorded Conway block: admitted by both BBODYs with the same state on its testnet; as on mainnet, refused by both for its header's version"
  ) {
    val all = eras ++ chainDep
    val onMainnet = all.map { b =>
      val j = judge(b, b.raw)
      same(b.name, j.era, j.flat)
      // the phase-2 counters, the flat rules' (CNF-2: the recorded blocks run no script, so the
      // counters are zero on both sides; the blocks of built transactions below count scripts)
      assertEquals(j.eraPlutus, j.flatPlutus, b.name)
      assertEquals(j.eraPlutus, Some(PlutusStats.zero), b.name)
      j.era match
        case Right((_, made)) =>
          // counted once more for its issuer
          assertEquals(
            made.blocks.values.map(_.bits).sum,
            j.ticked.bcur.blocks.values.map(_.bits).sum + 1,
            b.name
          )
        case Left(fs) => fail(s"${b.name}: refused $fs")
      val m = judge(b, b.raw, Network.Mainnet)
      same(s"${b.name} on mainnet", m.era, m.flat)
      m.era
    }
    // the clusters' headers declare major 11 and their parameters' 9: as on mainnet, each is past
    // the version after it
    onMainnet.foreach(v =>
      assertEquals(
        v,
        Left(Vector(BlockFailure.Envelope(EnvelopeError.HeaderProtVerTooHigh(11, 10))))
      )
    )
  }

  // ---- refusals crafted from a recorded block ----------------------------------------------------------

  private lazy val first: Before = eras.head

  test("another declared body size or hash: refused by both, with equal failures") {
    val size = crafted(first.raw)(
      identity,
      (fields, parts) =>
        committing(fields, parts).updated(6, encoded(Value.UInt(BigInt(parts.map(_.size).sum + 1))))
    )
    val hash = crafted(first.raw)(
      identity,
      (fields, parts) =>
        committing(fields, parts)
          .updated(7, encoded(Value.ByteString(Bytes(Vector.fill(32)(7.toByte)))))
    )
    val committed = crafted(first.raw)(identity, committing)
    assertEquals(committed, first.raw, "the recorded header commits to its body")
    Vector("size" -> size, "hash" -> hash).foreach { (what, raw) =>
      val j = judge(first, raw)
      assertEquals(j.flat, Left(Vector(BlockFailure.BodyCommitment)), what)
      same(what, j.era, j.flat)
    }
    // the era predicates' own failures, with the reference's mismatches: the body's (supplied)
    // and the header's (expected)
    val actual = sizeOf(first.raw)
    val sized = ok("size")(ConwayBlock.decode(size, layout))
    assertEquals(
      ShelleyBbody
        .validateBlockBodySize(sized, PraosEraBlockHeader.conway.protVerBlockHeader(sized)),
      cats.data.Validated.invalidNec(
        new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](EraMismatch(actual, actual + 1))
      )
    )
    val hashed = ok("hash")(ConwayBlock.decode(hash, layout))
    assertEquals(
      ShelleyBbody
        .validateBlockBodyHash(hashed)
        .leftMap(_.map(f => (f.mismatch.supplied.bytes, f.mismatch.expected.bytes))),
      cats.data.Validated.invalidNec(
        (
          EraBlockBody.conway.hashBlockBody(hashed.blockBody).bytes,
          Bytes(Vector.fill(32)(7.toByte))
        )
      )
    )
  }

  test(
    "named difference: a declared size and hash both other than the body's: the era BBODY records both, the flat BBODY its first"
  ) {
    // the flat BBODY stops at its first failure, and its form of the body's commitment holds
    // neither mismatch (the accumulation of #412); the reference records both
    // (`Alonzo/Rules/Bbody.hs:194-196`, cardano-ledger f649f975)
    val both = crafted(first.raw)(
      identity,
      (fields, parts) =>
        committing(fields, parts)
          .updated(6, encoded(Value.UInt(BigInt(parts.map(_.size).sum + 1))))
          .updated(7, encoded(Value.ByteString(Bytes(Vector.fill(32)(7.toByte)))))
    )
    val j = judge(first, both)
    assertEquals(j.flat, Left(Vector(BlockFailure.BodyCommitment)))
    assertEquals(j.era, Left(Vector(BlockFailure.BodyCommitment, BlockFailure.BodyCommitment)))
    // the era rule's own failures, before their flat form: the size is checked before the hash, and
    // the root's list is the reverse of the order its predicates recorded (each failure put in
    // front, `Extended.hs:708`, cardano-ledger f649f975; `Judgment.flatten`), so the hash's first,
    // as the reference returns the same pair for Dijkstra's BBODY (`DijkstraBbodySuite`,
    // `DijkstraRulesOracleSuite`'s `bbody/other-body-size-and-hash`)
    val actual = FlatBlock.decodeShelley(first.raw) match
      case Right(b: FlatBlock.Shelley) => b
      case other                       => fail(s"$other")
    assertEquals(
      j.eraFailures,
      Vector[BbodyPredFailure[Conway]](
        new ConwayBbodyPredFailure.InvalidBodyHashBBODY[Conway](
          EraMismatch(
            ok("hash")(xyz.kd5ujc.scarab.ledger.state.Hash32.fromBytes(actual.bodyHash)),
            ok("hash")(
              xyz.kd5ujc.scarab.ledger.state.Hash32.fromBytes(Bytes(Vector.fill(32)(7.toByte)))
            )
          )
        ),
        new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](
          EraMismatch(actual.bodySize, actual.bodySize + 1)
        )
      )
    )
  }

  /** The body size of the block `raw`, as the flat decoder computes it. */
  private def sizeOf(raw: Bytes): Long = FlatBlock.decodeShelley(raw) match
    case Right(b: FlatBlock.Shelley) => b.bodySize
    case other                       => fail(s"$other")

  test(
    "the header's major version past the one after the parameters': refused on mainnet by the era BBODY and the flat envelope"
  ) {
    val major =
      ConwayNewEpochState.gov.get(judge(first, first.raw).ticked).curPParams.protocolMajor.toInt
    def at(m: Int) =
      crafted(first.raw)(identity, (fields, parts) => withMajor(committing(fields, parts), m))
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2).foreach { build =>
      val tooHigh = judge(first, at(major + 2), Network.Mainnet, build)
      assertEquals(
        tooHigh.era,
        Left(
          Vector(BlockFailure.Envelope(EnvelopeError.HeaderProtVerTooHigh(major + 2, major + 1)))
        ),
        s"$build"
      )
      same(s"$build too high", tooHigh.era, tooHigh.flat)
      // the flat BBODY alone admits it: the flat rules check the version in the envelope
      assert(tooHigh.flatBbodyAdmits, s"$build")
      // the boundary, and another network, admitted by both
      Vector(
        judge(first, at(major + 1), Network.Mainnet, build),
        judge(first, at(major + 2), Network.Testnet, build)
      ).foreach { j =>
        assert(j.era.isRight, s"$build: ${j.era}")
        same(s"$build admitted", j.era, j.flat)
      }
    }
  }

  test("the gate of the header's version by build, whose clause from version 12 meets no failure") {
    assertEquals(
      HeaderProtVerGate.of(ReferenceBuild.Release11_1),
      HeaderProtVerGate.MainnetOrFromVersion12
    )
    assertEquals(HeaderProtVerGate.of(ReferenceBuild.Release11_2), HeaderProtVerGate.MainnetOnly)
    assertEquals(HeaderProtVerGate.of(ReferenceBuild.LeiosB004fe0c), HeaderProtVerGate.MainnetOnly)
    val v12 = Version.mkVersion(12).getOrElse(fail("12"))
    val v11 = Version.mkVersion(11).getOrElse(fail("11"))
    assert(HeaderProtVerGate.MainnetOrFromVersion12.checks(Network.Testnet, v12))
    assert(!HeaderProtVerGate.MainnetOrFromVersion12.checks(Network.Testnet, v11))
    assert(!HeaderProtVerGate.MainnetOnly.checks(Network.Testnet, v12))
    assert(HeaderProtVerGate.MainnetOnly.checks(Network.Mainnet, v11))
    // from 12 the version after it is the last, past which no header's major decodes: a header at
    // 13 on parameters at 12 is admitted under each build, on testnet
    assertEquals(Version.mkVersion(14), None)
    val at13 =
      crafted(first.raw)(identity, (fields, parts) => withMajor(committing(fields, parts), 13))
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2).foreach { build =>
      val j = judge(
        first,
        at13,
        Network.Testnet,
        build,
        ConwayNewEpochState.gov.modify(g =>
          g.copy(curPParams = g.curPParams.copy(protocolMajor = 12))
        )
      )
      assert(j.era.isRight, s"$build: ${j.era}")
    }
  }

  // ---- blocks of built transactions --------------------------------------------------------------------

  private lazy val third: Before = eras(2)
  private lazy val payer = TestLedger.key()
  private def paid(coin: BigInt): Node = B.out(B.keyAddress(payer), coin)

  /** `alwaysSucceedsWithDatum SPlutusV3` and its hash
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs:109-181`,
    * cardano-ledger 9df9f98c, as `DijkstraBbodyImpSuite` holds it).
    */
  private def hex(s: String): Bytes = ok("hex")(Bytes.fromHex(s))

  private val alwaysSucceedsWithDatumV3: Bytes = hex(
    "58ab0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026464b30013370e90011804800c4cc00888c8c" +
      "94cd4d6600266e1d2000001891909118010019805000c56600266e1d200200189091000c5900b2016300a00221" +
      "4a22940c030004dd518038009803000c528200e37546a66002446464253001005801c009001180398050011803" +
      "00098029baa0022220012233002300500130080018a4d1326330024901035054350049900501"
  )
  private val alwaysSucceedsWithDatumV3Hash: Bytes =
    hex("90a4869bd26787c6e649fbc84b03dfbd7ef4f46fcd619d5931fd0280")

  /** A transaction as a block holds it: its body and witness set's bytes. */
  private final case class Built(body: Bytes, wits: Bytes):
    def transaction: FlatBlock.Transaction = FlatBlock.Transaction(body, wits, None, true)
    def id: Bytes = transaction.id
    def era: ConwayTx = ok("era transaction")(ConwayTx.decode(transaction, layout))

  private def built(body: Node, wits: Node): Built = Built(B.encode(body), B.encode(wits))

  /** The outputs of container bodies that no block holds, a hundred each: the entries a test's UTxO
    * set gets, in order, keyed by their body's id and their indices, each read by the flat decoder.
    */
  private def funded(outputs: Vector[Node]): Vector[(TxIn, TxOut)] =
    outputs.grouped(100).toVector.zipWithIndex.flatMap { (group, n) =>
      val body = B.body(0 -> B.set(B.fresh(n).node), 1 -> B.arr(group*), 2 -> B.uint(0))
      val t = Built(B.encode(body), B.encode(B.cmap()))
      val flat = ok("container")(Tx.fromBlock(t.transaction, Era.Conway))
      TxIn.txouts(t.id, flat.body.outputs)
    }

  /** The block of the third Conway block's header with the transactions `txs` as its body, which
    * its header commits to.
    */
  private def blockOf(
      txs: Vector[Built],
      header: Vector[Bytes] => Vector[Bytes] = identity,
      invalid: Vector[Int] = Vector.empty
  ): Bytes =
    crafted(third.raw)(
      _ =>
        Vector(
          array(txs.map(_.body)),
          array(txs.map(_.wits)),
          encoded(Value.Map(Vector.empty)),
          array(invalid.map(i => encoded(Value.UInt(BigInt(i)))))
        ),
      (fields, parts) => header(committing(fields, parts))
    )

  /** The ticked state with `utxo` added to its UTxO set (enterprise addresses alone, so no stake
    * changes) and its parameters as `params` makes them.
    */
  private def holding(
      utxo: Vector[(TxIn, TxOut)],
      params: ProtocolParams => ProtocolParams = identity
  ): NewEpochState[Conway] => NewEpochState[Conway] =
    ConwayNewEpochState.ls
      .modify(ls =>
        ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo ++ utxo)))
      )
      .andThen(ConwayNewEpochState.gov.modify(g => g.copy(curPParams = params(g.curPParams))))

  private def in(utxo: Vector[(TxIn, TxOut)], ix: Int): Node =
    val i = utxo(ix)._1
    B.arr(B.bytes(i.id), B.uint(i.index))

  test(
    "reference scripts past the block's limit: refused by both with equal failures, within it admitted with the same state"
  ) {
    val script = B.purposeIsWellformedNoDatumV3
    val scriptSize = script.size.toLong
    val perTx = 100
    // transactions of 100 references each, as many as the limit needs to be passed
    val limit = 1024L * 1024
    val count = (limit / (perTx * scriptSize) + 1).toInt
    val refOuts = Vector.fill(count * perTx)(B.refScriptOut(B.keyAddress(payer), 10000000, script))
    val fees = Vector.fill(count)(paid(20000000))
    val utxo = funded(refOuts ++ fees)
    val txs = (0 until count).toVector.map { i =>
      val body = B.body(
        0 -> B.set(in(utxo, count * perTx + i)),
        1 -> B.arr(paid(17000000)),
        2 -> B.uint(3000000),
        18 -> B.set((0 until perTx).map(j => in(utxo, i * perTx + j))*)
      )
      built(body, B.witnesses(body, Vector(payer)))
    }
    val total = count * perTx * scriptSize
    assert(total > limit && (count - 1) * perTx * scriptSize <= limit, s"$total")
    Vector(9, 10, 11).foreach { major =>
      // the body is past the cluster's 65,536 bytes, which the flat side's envelope checks before
      // its BBODY (BBODY reads no such limit): a limit that holds it
      val world =
        holding(utxo, _.copy(protocolMajor = major, maxBlockBodySize = BigInt(1024 * 1024)))
      val refused = judge(third, blockOf(txs), change = world)
      assertEquals(
        refused.flat,
        Left(Vector(BlockFailure.RefScriptsTooLarge(Mismatch(total, limit)))),
        s"$major"
      )
      same(s"$major refused", refused.era, refused.flat)
      val admitted = judge(third, blockOf(txs.init), change = world)
      assert(admitted.era.isRight, s"$major: ${admitted.era}")
      same(s"$major admitted", admitted.era, admitted.flat)
    }
  }

  test(
    "reference scripts created in the block count from version 11, as the flat rules count them"
  ) {
    val script = B.purposeIsWellformedNoDatumV3
    val utxo = funded(Vector(paid(50000000), paid(20000000)))
    val producer =
      val body = B.body(
        0 -> B.set(in(utxo, 0)),
        1 -> B.arr(B.refScriptOut(B.keyAddress(payer), 20000000, script), paid(27000000)),
        2 -> B.uint(3000000)
      )
      built(body, B.witnesses(body, Vector(payer)))
    val consumer =
      val body = B.body(
        0 -> B.set(in(utxo, 1)),
        1 -> B.arr(paid(17000000)),
        2 -> B.uint(3000000),
        18 -> B.set(B.arr(B.bytes(producer.id), B.uint(0)))
      )
      built(body, B.witnesses(body, Vector(payer)))
    val txs = Vector(producer, consumer)
    val flatTxs = txs.map(t => ok("flat")(Tx.fromBlock(t.transaction, Era.Conway)))
    val eraUtxo = UTxO[Conway](
      utxo.map((i, o) => i -> ok("stored")(StoredTxOut.conway.fromStored(o))).toMap
    )
    Vector(10 -> 0L, 11 -> script.size.toLong).foreach { (major, expected) =>
      val pv = ProtVer(
        Version.mkVersion(major).getOrElse(fail("major")),
        Unsigned.lit[Unsigned.Width.W32](0)
      )
      val t = EraTx.conway
      val era = ConwayBbody.totalRefScriptSizeInBlock[Conway](pv, txs.map(_.era), eraUtxo)(using
        t,
        EraTxBody.conway,
        t.bodyOf.top,
        EraTxBody.conway.top
      )
      val flat = FlatBbody.refScriptsSize(
        ConwayNewEpochState.gov
          .get(judge(third, third.raw).ticked)
          .curPParams
          .copy(protocolMajor = major),
        Utxo.fromMap(utxo.toMap),
        flatTxs
      )
      assertEquals((era, flat), (expected, expected), s"$major")
      val j = judge(third, blockOf(txs), change = holding(utxo, _.copy(protocolMajor = major)))
      assert(j.era.isRight, s"$major: ${j.era}")
      same(s"$major", j.era, j.flat)
    }
  }

  test(
    "reference scripts made in the block alone past the block's limit: admitted at version 10, refused at 11 by both"
  ) {
    // the version switch of `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:360-370`,
    // cardano-ledger f649f975) through BBODY: up to 10 each transaction is measured against the set
    // before the block, which holds none of the scripts; from 11 the outputs of the transactions
    // before it count too. Producers of 100 outputs holding the script each, then as many
    // transactions referencing a producer's 100 outputs each, together past the limit
    val script = B.purposeIsWellformedNoDatumV3
    val scriptSize = script.size.toLong
    val perTx = 100
    val limit = 1024L * 1024
    val count = (limit / (perTx * scriptSize) + 1).toInt
    val fund = BigInt(600000000)
    val funds = funded(Vector.fill(2 * count)(paid(fund)))
    val producers = (0 until count).toVector.map { i =>
      val body = B.body(
        0 -> B.set(in(funds, i)),
        1 -> B.arr(
          (Vector.fill(perTx)(B.refScriptOut(B.keyAddress(payer), 5000000, script)) :+
            paid(fund - perTx * 5000000 - 3000000))*
        ),
        2 -> B.uint(3000000)
      )
      built(body, B.witnesses(body, Vector(payer)))
    }
    val consumers = (0 until count).toVector.map { i =>
      val body = B.body(
        0 -> B.set(in(funds, count + i)),
        1 -> B.arr(paid(fund - 3000000)),
        2 -> B.uint(3000000),
        18 -> B.set((0 until perTx).map(j => B.arr(B.bytes(producers(i).id), B.uint(j)))*)
      )
      built(body, B.witnesses(body, Vector(payer)))
    }
    val total = count * perTx * scriptSize
    assert(total > limit, s"$total")
    val raw = blockOf(producers ++ consumers)
    // the producers' bodies are past the cluster's transaction size, and the block past its body
    // size, which the flat side's envelope checks before its BBODY: limits that hold them (BBODY
    // reads neither)
    def world(major: Int) = holding(
      funds,
      _.copy(
        protocolMajor = major,
        maxTxSize = BigInt(128 * 1024),
        maxBlockBodySize = BigInt(4 * 1024 * 1024)
      )
    )
    val at10 = judge(third, raw, change = world(10))
    assert(at10.era.isRight, s"${at10.era}")
    same("10", at10.era, at10.flat)
    val at11 = judge(third, raw, change = world(11))
    assertEquals(
      at11.eraFailures,
      Vector[BbodyPredFailure[Conway]](
        new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Conway](EraMismatch(total, limit))
      )
    )
    assertEquals(at11.flat, Left(Vector(BlockFailure.RefScriptsTooLarge(Mismatch(total, limit)))))
    same("11", at11.era, at11.flat)
  }

  test(
    "the block's units past maxBlockExUnits, in either component: refused by both with equal failures; within, the script run and the same state"
  ) {
    val datum = B.uint(0)
    val utxo = funded(
      Vector(
        B.datumHashOut(B.scriptAddress(alwaysSucceedsWithDatumV3Hash), 10000000, B.dataHash(datum)),
        paid(20000000),
        paid(30000000)
      )
    )
    assertEquals(B.scriptHash(3, alwaysSucceedsWithDatumV3), alwaysSucceedsWithDatumV3Hash)
    val params = ConwayNewEpochState.gov.get(judge(third, third.raw).ticked).curPParams
    val pp = PParams[Conway](ok("parameters")(ConwayPParams.fromStored(params)))
    // the units the redeemer declares, within the transaction's and the block's limits
    val units = ExUnits(2000000, 1000000000)
    assert(units.fits(AlonzoEraPParams.ppMaxTxExUnitsL[Conway].get(pp).units))
    assert(units.fits(params.maxBlockExUnits))
    def spend(integrity: Bytes): Built =
      val body = B.body(
        0 -> B.set(in(utxo, 0), in(utxo, 1)),
        1 -> B.arr(paid(27000000)),
        2 -> B.uint(3000000),
        11 -> B.bytes(integrity),
        13 -> B.set(in(utxo, 2))
      )
      built(
        body,
        B.witnesses(
          body,
          Vector(payer),
          4 -> B.set(datum),
          5 -> B.cmap(
            B.arr(B.uint(0), B.uint(0)) -> B
              .arr(B.uint(0), B.arr(B.uint(units.mem), B.uint(units.steps)))
          ),
          7 -> B.set(B.bytes(alwaysSucceedsWithDatumV3))
        )
      )
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Conway, TxLevel.Top](
        pp,
        spend(Bytes(Vector.fill(32)(0.toByte))).era,
        Set(PlutusLanguage.V3)
      )(using
        EraTx.conway,
        AlonzoEraPParams.conway,
        xyz.kd5ujc.scarab.ledger.era.EraTxWits.conway,
        EraTx.conway.bodyOf.top
      )
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    val raw = blockOf(Vector(spend(integrity)))
    val admitted = judge(third, raw, change = holding(utxo))
    assert(admitted.era.isRight, s"${admitted.era}")
    same("admitted", admitted.era, admitted.flat)
    // the script ran, as the flat rules count it, and its output is spent
    assertEquals((admitted.eraEvaluated, admitted.flatEvaluated), (1, 1))
    assertEquals(admitted.flatPlutus, Some(PlutusStats(1, 0, 1, 1)))
    assertEquals(admitted.eraPlutus, admitted.flatPlutus)
    admitted.era.foreach((ls, _) => assert(ls.utxo.utxo.utxo.get(utxo.head._1).isEmpty))
    Vector(
      ExUnits(units.mem - 1, params.maxBlockExUnits.steps),
      ExUnits(params.maxBlockExUnits.mem, units.steps - 1)
    ).foreach { below =>
      val refused = judge(third, raw, change = holding(utxo, _.copy(maxBlockExUnits = below)))
      assertEquals(
        refused.flat,
        Left(Vector(BlockFailure.BlockExUnitsTooBig(units, below))),
        s"$below"
      )
      same(s"$below", refused.era, refused.flat)
    }
  }

  /** The datum the script outputs of the counters' blocks lock with, and the units of each of their
    * redeemers.
    */
  private val lockDatum: Node = B.uint(0)
  private val redeemerUnits: ExUnits = ExUnits(1000000, 500000000)

  /** A transaction spending the inputs at `inputs` of `utxo` (the script outputs among them by
    * `scripts`, each with a redeemer for its place among the sorted inputs and `lockDatum` in the
    * witnesses), paying `out` with a fee of 3 ada and the collateral at `collateral` if any, its
    * script integrity hash the one its witnesses make with Plutus V3 if `languages` holds it.
    */
  private def spending(
      utxo: Vector[(TxIn, TxOut)],
      inputs: Vector[Int],
      scripts: Vector[(Int, Bytes)],
      collateral: Option[Int],
      out: BigInt,
      outputs: Vector[Node] = Vector.empty,
      datums: Vector[Node] = Vector(lockDatum),
      languages: Set[PlutusLanguage] = Set(PlutusLanguage.V3),
      references: Vector[Int] = Vector.empty
  ): Built =
    val params = ConwayNewEpochState.gov.get(judge(third, third.raw).ticked).curPParams
    val pp = PParams[Conway](ok("parameters")(ConwayPParams.fromStored(params)))
    val sorted = inputs.sortBy(i => utxo(i)._1)
    val redeemers = scripts.map((i, _) =>
      B.arr(B.uint(0), B.uint(sorted.indexOf(i))) -> B.arr(
        B.uint(0),
        B.arr(B.uint(redeemerUnits.mem), B.uint(redeemerUnits.steps))
      )
    )
    val more = Vector(
      Option.when(datums.nonEmpty)(4 -> B.set(datums*)),
      Option.when(redeemers.nonEmpty)(5 -> B.cmap(redeemers*)),
      Option.when(scripts.nonEmpty)(7 -> B.set(scripts.map(s => B.bytes(s._2)).distinct*))
    ).flatten
    def withHash(integrity: Bytes): Built =
      val body = B.body(
        Vector(
          Some(0 -> B.set(inputs.map(in(utxo, _))*)),
          Some(1 -> B.arr((outputs :+ paid(out))*)),
          Some(2 -> B.uint(3000000)),
          Some(11 -> B.bytes(integrity)),
          collateral.map(c => 13 -> B.set(in(utxo, c))),
          Option.when(references.nonEmpty)(18 -> B.set(references.map(in(utxo, _))*))
        ).flatten*
      )
      built(body, B.witnesses(body, Vector(payer), more*))
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Conway, TxLevel.Top](
        pp,
        withHash(Bytes(Vector.fill(32)(0.toByte))).era,
        languages
      )(using
        EraTx.conway,
        AlonzoEraPParams.conway,
        xyz.kd5ujc.scarab.ledger.era.EraTxWits.conway,
        EraTx.conway.bodyOf.top
      )
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    withHash(integrity)

  private def locked(script: Bytes, coin: BigInt): Node =
    B.datumHashOut(B.scriptAddress(B.scriptHash(3, script)), coin, B.dataHash(lockDatum))

  test(
    "the phase-2 counters of BBODY's events: a valid transaction of two scripts, an invalid one whose scripts all fail, one whose scripts pass and fail, and a hash with no redeemer, the flat rules' counts"
  ) {
    val (succeeds, fails) = (alwaysSucceedsWithDatumV3, B.alwaysFailsWithDatumV3)
    // 0, 1: two outputs at the script that passes; 2: at the one that fails; 3, 4: one at each;
    // 5 to 11: fees and collateral (an invalid transaction's own, which it spends)
    val utxo = funded(
      Vector(
        locked(succeeds, 10000000),
        locked(succeeds, 10000000),
        locked(fails, 10000000),
        locked(succeeds, 10000000),
        locked(fails, 10000000)
      ) ++ Vector.fill(7)(paid(30000000))
    )
    val valid =
      spending(utxo, Vector(0, 1, 5), Vector(0 -> succeeds, 1 -> succeeds), Some(6), 47000000)
    val allFail = spending(utxo, Vector(2, 7), Vector(2 -> fails), Some(10), 37000000)
    val passAndFail =
      spending(utxo, Vector(3, 4, 8), Vector(3 -> succeeds, 4 -> fails), Some(11), 47000000)
    // an output locked by the datum's hash, the datum given in the witnesses alone (a supplemental
    // datum), so that the body declares a script integrity hash and no script runs
    val datumOnly = spending(
      utxo,
      Vector(9),
      Vector.empty,
      None,
      17000000,
      outputs = Vector(locked(succeeds, 10000000)),
      languages = Set.empty
    )
    // each block's transactions, those flagged invalid and the counters; a valid transaction's
    // passed scripts then an invalid one's failed ones are two transactions
    Vector[(String, Vector[Built], Vector[Int], PlutusStats)](
      ("valid", Vector(valid), Vector.empty, PlutusStats(1, 0, 2, 1)),
      ("all fail", Vector(allFail), Vector(0), PlutusStats(0, 1, 1, 1)),
      ("pass and fail", Vector(passAndFail), Vector(0), PlutusStats(0, 1, 2, 1)),
      ("valid, then all fail", Vector(valid, allFail), Vector(1), PlutusStats(1, 1, 3, 2)),
      ("all fail, then valid", Vector(allFail, valid), Vector(0), PlutusStats(1, 1, 3, 2)),
      (
        "every kind",
        Vector(passAndFail, valid, datumOnly, allFail),
        Vector(0, 3),
        PlutusStats(1, 2, 5, 4)
      ),
      ("datum only", Vector(datumOnly), Vector.empty, PlutusStats(0, 0, 0, 1))
    ).foreach { (what, txs, invalid, expected) =>
      val j = judge(third, blockOf(txs, invalid = invalid), change = holding(utxo))
      assert(j.era.isRight, s"$what: ${j.era}")
      same(what, j.era, j.flat)
      // the plan deferred the scripts of each transaction whose scripts ran
      assertEquals(j.deferred.flatten.size, txs.count(_ != datumOnly), what)
      assertEquals(j.flatPlutus, Some(expected), what)
      assertEquals(j.eraPlutus, j.flatPlutus, what)
      // the phase-2 observations (`--plutus-cases`), one by transaction whose scripts ran, and
      // with the observer the same verdict and counters
      val o = judge(third, blockOf(txs, invalid = invalid), change = holding(utxo), observe = true)
      assertEquals(o.flatObserved.size, txs.count(_ != datumOnly), what)
      assertEquals(o.eraObserved.map(comparable), o.flatObserved.map(comparable), what)
      same(what, o.era, j.era)
      assertEquals(o.eraPlutus, j.eraPlutus, what)
    }
  }

  test(
    "a script the evaluator gives no verdict for: the era run names its transaction and redeemer as the flat rules name them, whichever script of the block it is"
  ) {
    val (succeeds, fails) = (alwaysSucceedsWithDatumV3, B.alwaysFailsWithDatumV3)
    val utxo = funded(
      Vector(
        locked(succeeds, 10000000),
        locked(succeeds, 10000000),
        locked(succeeds, 10000000),
        locked(fails, 10000000)
      ) ++ Vector.fill(4)(paid(30000000))
    )
    // a valid transaction of two scripts, then an invalid one of a script that passes and one
    // that fails: the four scripts the evaluator is asked to run, in the order the rules run them
    val valid =
      spending(utxo, Vector(0, 1, 4), Vector(0 -> succeeds, 1 -> succeeds), Some(5), 47000000)
    val passAndFail =
      spending(utxo, Vector(2, 3, 6), Vector(2 -> succeeds, 3 -> fails), Some(7), 47000000)
    val raw = blockOf(Vector(valid, passAndFail), invalid = Vector(1))
    val named = (1 to 4).map { n =>
      val j = judge(third, raw, change = holding(utxo), unsupportedAt = Some(n))
      val failure = j.flat match
        case Left(Vector(BlockFailure.Tx(failure: LedgerFailure.Unavailable))) => failure
        case other => fail(s"$n: the flat rules' ${other.map(_._1)}")
      assertEquals(j.eraRun.left.toOption, Some(ConwayRuleDefect.ScriptUnavailable(failure)), s"$n")
      // the evaluation stops at the script with no verdict on both sides
      assertEquals((j.eraEvaluated, j.flatEvaluated), (n, n), s"$n")
      // the transactions observed, the last with no verdict on the script, and with the observer
      // the same naming
      val o =
        judge(third, raw, change = holding(utxo), unsupportedAt = Some(n), observe = true)
      assertEquals(o.flatObserved.size, if n <= 2 then 1 else 2, s"$n")
      assertEquals(o.eraObserved.map(comparable), o.flatObserved.map(comparable), s"$n")
      assertEquals(o.eraRun.left.toOption, j.eraRun.left.toOption, s"$n")
      (failure.txId, failure.location)
    }
    // each of the four scripts named once, the first two of the valid transaction
    assertEquals(
      named.map(_._1).toVector,
      Vector(valid.id, valid.id, passAndFail.id, passAndFail.id)
    )
    assertEquals(named.distinct.size, 4)
  }

  test(
    "two scripts of one transaction whose requests are equal at distinct redeemers: the era run names and observes each by its place, as the flat rules do"
  ) {
    // a Plutus V1 script's staking credential, registered, unregistered (certificate 0, the
    // Shelley form), registered again, and unregistered with its refund (certificate 2, Conway's
    // form): a V1 context names both unregistrations by the one form it has for them
    // (`transTxCertV1V2`, `Conway/TxInfo.hs:406-418`, cardano-ledger 9df9f98c), so with equal
    // redeemers the two scripts' contexts, and their requests, are equal
    val v1 = hex("4d01000033222220051200120011")
    val credential = Credential.Script(B.scriptHash(1, v1))
    val params = ConwayNewEpochState.gov.get(judge(third, third.raw).ticked).curPParams
    val pp = PParams[Conway](ok("parameters")(ConwayPParams.fromStored(params)))
    val deposit = params.keyDeposit
    val utxo = funded(Vector(paid(30000000), paid(30000000)))
    val staking = B.arr(B.uint(1), B.bytes(credential.hash))
    val redeemer = B.arr(B.uint(0), B.arr(B.uint(redeemerUnits.mem), B.uint(redeemerUnits.steps)))
    def withHash(integrity: Bytes): Built =
      val body = B.body(
        0 -> B.set(in(utxo, 0)),
        1 -> B.arr(paid(27000000 + deposit)),
        2 -> B.uint(3000000),
        4 -> B.arr(
          B.arr(B.uint(1), staking),
          B.arr(B.uint(0), staking),
          B.arr(B.uint(8), staking, B.uint(deposit))
        ),
        11 -> B.bytes(integrity),
        13 -> B.set(in(utxo, 1))
      )
      built(
        body,
        B.witnesses(
          body,
          Vector(payer),
          3 -> B.set(B.bytes(v1)),
          5 -> B.cmap(
            B.arr(B.uint(2), B.uint(0)) -> redeemer,
            B.arr(B.uint(2), B.uint(2)) -> redeemer
          )
        )
      )
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Conway, TxLevel.Top](
        pp,
        withHash(Bytes(Vector.fill(32)(0.toByte))).era,
        Set(PlutusLanguage.V1)
      )(using
        EraTx.conway,
        AlonzoEraPParams.conway,
        xyz.kd5ujc.scarab.ledger.era.EraTxWits.conway,
        EraTx.conway.bodyOf.top
      )
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    val tx = withHash(integrity)
    // the credential registered with the cluster's deposit, which the deposits held count
    val registered = holding(utxo).andThen(
      ConwayNewEpochState.accounts
        .modify(accounts =>
          ConwayAccounts(
            accounts.states.updated(
              ok("credential")(
                xyz.kd5ujc.scarab.ledger.rules.Convert.credential[Staking](credential)
              ),
              ConwayAccountState(
                ok("balance")(CompactCoin.fromBigInt(0)),
                ok("deposit")(CompactCoin.fromBigInt(deposit)),
                None,
                None
              )
            )
          )
        )
        .andThen(
          ConwayNewEpochState.ls.modify(ls =>
            ls.copy[Conway](utxo =
              ls.utxo.copy(deposited = Coin(ls.utxo.deposited.value + deposit))
            )
          )
        )
    )
    val raw = blockOf(Vector(tx))
    val admitted = judge(third, raw, change = registered, observe = true)
    assert(admitted.era.isRight, s"${admitted.era}")
    same("admitted", admitted.era, admitted.flat)
    assertEquals(admitted.eraPlutus, admitted.flatPlutus)
    // the two requests are equal, at certificates 0 and 2
    val evaluations = admitted.flatObserved match
      case Vector(Phase2.Observation(_, _, _, _, _, _, Right(Phase2.Outcome.Passed(report)))) =>
        report.evaluations
      case other => fail(s"the flat rules' observations $other")
    assertEquals(
      evaluations.map(e => (e.redeemer.tag, e.redeemer.index)).toSet,
      Set[(Int, BigInt)]((2, 0), (2, 2))
    )
    assertEquals(evaluations.map(_.request).distinct.size, 1)
    assertEquals(admitted.eraObserved.map(comparable), admitted.flatObserved.map(comparable))
    // with no verdict on either, each named and observed by its own redeemer
    val named = Vector(1, 2).map { n =>
      Vector(false, true).map { observe =>
        val j = judge(third, raw, change = registered, unsupportedAt = Some(n), observe = observe)
        val failure = j.flat match
          case Left(Vector(BlockFailure.Tx(failure: LedgerFailure.Unavailable))) => failure
          case other => fail(s"$n: the flat rules' ${other.map(_._1)}")
        assertEquals(
          j.eraRun.left.toOption,
          Some(ConwayRuleDefect.ScriptUnavailable(failure)),
          s"$n"
        )
        assertEquals(j.eraObserved.map(comparable), j.flatObserved.map(comparable), s"$n")
        failure.location
      }.distinct
    }
    assertEquals(named.map(_.size), Vector(1, 1))
    assertEquals(named.flatten.distinct.size, 2)
  }

  // ---- the era run prepared for bounded parallel evaluation (ADR 0018) -------------------------------

  /** A block of four script transactions that depend on each other: the first spends a script
    * output and creates another, which the second spends, its collateral the first's other output;
    * the third, flagged invalid, reads that output as a reference input, and the fourth spends it
    * after both read it. The batches are the first, the second and third, the fourth.
    */
  private final case class Dependent(utxo: Vector[(TxIn, TxOut)], txs: Vector[Built])

  private lazy val dependent: Dependent =
    val (succeeds, fails) = (alwaysSucceedsWithDatumV3, B.alwaysFailsWithDatumV3)
    // 0, 4, 7: script outputs; 1, 3, 5: fees; 2, 6, 8: collateral
    val utxo = funded(
      Vector(
        locked(succeeds, 10000000),
        paid(30000000),
        paid(30000000),
        paid(30000000),
        locked(fails, 10000000),
        paid(30000000),
        paid(30000000),
        locked(succeeds, 10000000),
        paid(30000000)
      )
    )
    val first = spending(
      utxo,
      Vector(0, 1),
      Vector(0 -> succeeds),
      Some(2),
      27000000,
      outputs = Vector(locked(succeeds, 10000000))
    )
    // 9: the first's script output, 10: its other output
    val all = utxo ++ TxIn.txouts(
      first.id,
      ok("first")(Tx.fromBlock(first.transaction, Era.Conway)).body.outputs
    )
    Dependent(
      utxo,
      Vector(
        first,
        spending(all, Vector(9, 3), Vector(9 -> succeeds), Some(10), 37000000),
        spending(all, Vector(4, 5), Vector(4 -> fails), Some(6), 37000000, references = Vector(10)),
        spending(all, Vector(10, 7), Vector(7 -> succeeds), Some(8), 34000000)
      )
    )

  test(
    "dependent script transactions: the prepared era run batches them by their reads and writes and comes to the serial run's verdict, counters, naming and observations"
  ) {
    val Dependent(utxo, txs) = dependent
    val raw = blockOf(txs, invalid = Vector(2))
    val admitted = judge(third, raw, change = holding(utxo), observe = true)
    assert(admitted.era.isRight, s"${admitted.era}")
    same("admitted", admitted.era, admitted.flat)
    assertEquals(admitted.eraPlutus, admitted.flatPlutus)
    assertEquals(admitted.eraPlutus, Some(PlutusStats(3, 1, 4, 4)))
    assertEquals(admitted.eraObserved.map(comparable), admitted.flatObserved.map(comparable))
    assertEquals(admitted.deferred, Vector(Vector(0), Vector(1, 2), Vector(3)))
    // no verdict on each script in turn: the plan stops where the serial run stops, and names it
    (1 to 4).foreach { n =>
      Vector(false, true).foreach { observe =>
        val j =
          judge(third, raw, change = holding(utxo), unsupportedAt = Some(n), observe = observe)
        val failure = j.flat match
          case Left(Vector(BlockFailure.Tx(failure: LedgerFailure.Unavailable))) => failure
          case other => fail(s"$n: the flat rules' ${other.map(_._1)}")
        assertEquals(j.eraRun.left.toOption, Some(ConwayRuleDefect.ScriptUnavailable(failure)))
        assertEquals(j.eraObserved.map(comparable), j.flatObserved.map(comparable), s"$n")
      }
    }
    // a verdict that is not the flag's: the second flagged invalid though its script passes, the
    // third flagged valid though its script fails; the serial run's refusal, every failure kept
    Vector(Vector(1, 2), Vector.empty[Int]).foreach { invalid =>
      val j = judge(third, blockOf(txs, invalid = invalid), change = holding(utxo), observe = true)
      assert(j.eraFailures.nonEmpty, s"$invalid: ${j.eraRun}")
      assertEquals(j.eraObserved.map(comparable), j.flatObserved.map(comparable), s"$invalid")
    }
  }

  test(
    "the prepared era run through the node's bounded workers: the serial run's result at one, two and four workers"
  ) {
    val Dependent(utxo, txs) = dependent
    Vector(Vector(2), Vector(1, 2), Vector.empty[Int])
      .traverse_ { invalid =>
        val j = judge(third, blockOf(txs, invalid = invalid), change = holding(utxo))
        val serial = j.replayed(None).serial
        Vector(1, 2, 4).traverse_ { n =>
          ParallelPlutus.Workers
            .from(n)
            .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
            .flatMap(_(j.replayed(None)))
            .map(result => samePlanned(s"$invalid at $n workers", serial, Right(result)))
        }
      }
  }

  /** The node's evaluator but on `at`, the request of a script whose evaluation ends in `defect`, a
    * raw defect of the implementation.
    */
  private final class Defective(at: EvalRequest) extends ScriptEvaluator[cats.Id]:
    val defect = new IllegalStateException("an evaluator defect")
    def evaluate(request: EvalRequest): EvalResult =
      if request == at then throw defect else ScarabEvaluator.default.evaluate(request)

  /** The request of the first script of the `n` th transaction the flat rules observed. */
  private def requestOf(j: Judged, n: Int): EvalRequest =
    j.flatObserved.lift(n).map(_.outcome) match
      case Some(Right(Phase2.Outcome.Passed(report))) =>
        report.evaluations.headOption.fold(fail(s"$n: no evaluation"))(_.request)
      case Some(Right(Phase2.Outcome.Failed(report, _))) =>
        report.evaluations.headOption.fold(fail(s"$n: no evaluation"))(_.request)
      case other => fail(s"$n: observed $other")

  test(
    "a raw defect of a script's evaluation: the prepared era run raises the very defect the serial run raises, after the same observations, and none the serial run never reaches"
  ) {
    val Dependent(utxo, txs) = dependent
    val admitted =
      judge(third, blockOf(txs, invalid = Vector(2)), change = holding(utxo), observe = true)
    // the third transaction's script ends in a defect: the first two observed, then the defect
    val defective = Defective(requestOf(admitted, 2))
    val (serialSeen, planSeen) = (Observations(), Observations())
    val serial =
      Either.catchNonFatal(
        admitted
          .program(
            defective,
            Some(ConwayBlockRun.Observing(serialSeen.observe, None)),
            Verdicts.Direct
          )
          .serial
      )
    assert(serial.left.exists(_ eq defective.defect), s"$serial")
    val plan =
      admitted
        .program(defective, Some(ConwayBlockRun.Observing(planSeen.observe, None)), Verdicts.Direct)
        .prepare
    val planned = plan.resolve(plan.batches.flatten.map(plan.execute))
    assert(planned.left.exists(_.cause eq defective.defect), s"$planned")
    assertEquals(planSeen.all.map(comparable), serialSeen.all.map(comparable))
    assertEquals(serialSeen.all.size, 2)
    // the fourth's script ends in a defect, after the second's verdict is not its flag's: the serial
    // run records that failure and runs no later script, so the plan's result is the refusal
    val refused = judge(third, blockOf(txs, invalid = Vector(1, 2)), change = holding(utxo))
    val late = Defective(requestOf(admitted, 3))
    val serialRefusal = refused.program(late, None, Verdicts.Direct).serial
    assert(serialRefusal.exists(_.isLeft), s"$serialRefusal")
    val latePlan = refused.program(late, None, Verdicts.Direct).prepare
    samePlanned(
      "late",
      serialRefusal,
      latePlan.resolve(latePlan.batches.flatten.map(latePlan.execute))
    )
  }

  test(
    "a raw defect of the rules after deferred scripts: the prepared era run comes to the serial run's result, whether the scripts before it pass, one's verdict is not its flag's, or one has none"
  ) {
    val Dependent(utxo, txs) = dependent
    // the signature check of the third transaction ends in a defect, after the first two
    // transactions' scripts were deferred
    val defect = new IllegalStateException("a verdict provider defect")
    val verdicts = new Verdicts:
      export Verdicts.Direct.{opCert, praosVrf, tpraosVrf, keyHash, takePrepared}
      def witness(
          key: Bytes,
          signature: Bytes,
          message: Bytes
      ): Either[xyz.kd5ujc.scarab.ledger.ProviderDefect, Boolean] =
        if message == txs(2).id then throw defect
        else Verdicts.Direct.witness(key, signature, message)
    Vector[(String, Vector[Int], Option[Int])](
      ("passing", Vector(2), None),
      ("not the flag's", Vector(1, 2), None),
      ("no verdict", Vector(2), Some(2))
    ).foreach { (what, invalid, unsupportedAt) =>
      val j = judge(
        third,
        blockOf(txs, invalid = invalid),
        change = holding(utxo),
        unsupportedAt = unsupportedAt
      )
      val (serialSeen, planSeen) = (Observations(), Observations())
      val serial = Either.catchNonFatal(
        j.program(
          j.replaying(),
          Some(ConwayBlockRun.Observing(serialSeen.observe, None)),
          verdicts
        ).serial
      )
      val plan = j
        .program(j.replaying(), Some(ConwayBlockRun.Observing(planSeen.observe, None)), verdicts)
        .prepare
      assertEquals(plan.jobs.map(_.index), Vector(0, 1), what)
      val planned = plan.resolve(plan.batches.flatten.map(plan.execute))
      (serial, planned) match
        case (Left(raised), Left(landed)) => assert(landed.cause eq raised, what)
        case (Right(result), _)           => samePlanned(what, result, planned)
        case other                        => fail(s"$what: $other")
      assertEquals(planSeen.all.map(comparable), serialSeen.all.map(comparable), what)
      // with every script passing, and with a verdict that is not its flag's, the serial run
      // reaches the defect (the rules accumulate every failure); with no verdict it stops there
      unsupportedAt match
        case None => assert(serial.left.exists(_ eq defect), s"$what: $serial")
        case Some(_) =>
          assert(
            serial.exists(_.left.exists {
              case ConwayRuleDefect.ScriptUnavailable(_) => true
              case _                                     => false
            }),
            s"$what: $serial"
          )
    }
  }

  test(
    "missing completed values are evaluated, and another plan's do not type-check: the prepared era run never takes one for a pass"
  ) {
    val Dependent(utxo, txs) = dependent
    Vector(Vector(2), Vector(1, 2)).foreach { invalid =>
      val j = judge(third, blockOf(txs, invalid = invalid), change = holding(utxo))
      val serial = j.replayed(None).serial
      val plan = j.replayed(None).prepare
      samePlanned(s"$invalid missing", serial, plan.resolve(Vector.empty))
      // another plan's completions, executed by its own evaluator, are not this plan's: a plan
      // whose evaluator gives no verdict cannot be handed the passes of one whose evaluator does
      val noVerdict: ScriptEvaluator[cats.Id] = _ =>
        EvalResult.Failure(
          EvalFailure.Unsupported(xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted),
          ExUnits.zero,
          Vector.empty
        )
      val withheld = j.program(noVerdict, None, Verdicts.Direct)
      val withheldPlan = withheld.prepare
      val admitting = j.replayed(None).prepare
      assertEquals(admitting.jobs.size, withheldPlan.jobs.size)
      assertEquals(
        compileErrors("withheldPlan.resolve(withheldPlan.jobs.map(withheldPlan.execute))"),
        ""
      )
      assert(
        compileErrors("withheldPlan.resolve(withheldPlan.jobs.map(admitting.execute))").nonEmpty
      )
      assert(
        compileErrors("withheldPlan.resolve(admitting.jobs.map(admitting.execute))").nonEmpty
      )
      // its own, missing, are its evaluator's: the block withheld as the serial run withholds it
      val withheldSerial = withheld.serial
      assert(
        withheldSerial.left.exists {
          case ConwayRuleDefect.ScriptUnavailable(_) => true
          case _                                     => false
        },
        s"$invalid: $withheldSerial"
      )
      samePlanned(s"$invalid withheld", withheldSerial, withheldPlan.resolve(Vector.empty))
    }
  }

  test("a transaction whose input is missing: LEDGERS's refusal, embedded, the flat LEDGER's") {
    val utxo = funded(Vector(paid(20000000)))
    val body = B.body(
      0 -> B.set(in(utxo, 0), B.fresh(0x77).node),
      1 -> B.arr(paid(17000000)),
      2 -> B.uint(3000000)
    )
    val j = judge(
      third,
      blockOf(Vector(built(body, B.witnesses(body, Vector(payer))))),
      change = holding(utxo)
    )
    assert(j.flat.isLeft, s"${j.flat}")
    same("missing input", j.era, j.flat)
    // the input LEDGERS refuses, as UTXO's `BadInputsUTxO` names it
    val missing = TxIn(B.fresh(0x77).txId, 0)
    assertEquals(
      j.eraFailures.collect {
        case ConwayBbodyPredFailure.LedgersFailure(
              ShelleyLedgersPredFailure.LedgerFailure(
                xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayUtxowFailure(
                  xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxowPredFailure.UtxoFailure(
                    xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxoPredFailure.BadInputsUTxO(ins)
                  )
                )
              )
            ) =>
          ins.toSortedSet.toSet
      },
      Vector(Set(missing))
    )
  }

  // ---- a defect of ours after an established refusal (#85) --------------------------------------------

  /** The outputs of the blocks of a later defect: 0 to 2 key outputs, 3 a key output with a
    * reference script that only a damaged store holds (its script hash not its bytes'), which the
    * era does not read, 4 an output at the script that passes, 5 and 6 key outputs.
    */
  private lazy val withDamaged: Vector[(TxIn, TxOut)] =
    val utxo = funded(
      Vector(
        paid(20000000),
        paid(20000000),
        paid(30000000),
        B.refScriptOut(B.keyAddress(payer), 10000000, B.purposeIsWellformedNoDatumV3),
        locked(alwaysSucceedsWithDatumV3, 10000000),
        paid(30000000),
        paid(30000000)
      )
    )
    val (in, out) = utxo(3)
    utxo.updated(
      3,
      in -> out.copy(scriptRef = out.scriptRef.map(_.copy(hash = Bytes(Vector.fill(28)(7.toByte)))))
    )

  /** A payment whose input 0x77 no output holds: UTXO's `BadInputsUTxO`. */
  private lazy val missingInput: Built =
    val body = B.body(
      0 -> B.set(in(withDamaged, 0), B.fresh(0x77).node),
      1 -> B.arr(paid(17000000)),
      2 -> B.uint(3000000)
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** A payment whose collateral is the damaged output: LEDGERS reads it into the era where it reads
    * the transaction's inputs, collateral and reference inputs, and BBODY's read before LEDGERS
    * (inputs and reference inputs alone) does not.
    */
  private lazy val damagedCollateral: Built =
    val body = B.body(
      0 -> B.set(in(withDamaged, 1)),
      1 -> B.arr(paid(17000000)),
      2 -> B.uint(3000000),
      13 -> B.set(in(withDamaged, 3))
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** A payment reading the damaged output as a reference input, which BBODY reads into the era
    * before LEDGERS, for the reference scripts' size.
    */
  private lazy val damagedReference: Built =
    val body = B.body(
      0 -> B.set(in(withDamaged, 1)),
      1 -> B.arr(paid(17000000)),
      2 -> B.uint(3000000),
      18 -> B.set(in(withDamaged, 3))
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** A payment creating an output with a reference script, which UTXOW asks the decoder about. */
  private lazy val referenceScriptOut: Built =
    val body = B.body(
      0 -> B.set(in(withDamaged, 2)),
      1 -> B.arr(
        B.refScriptOut(B.keyAddress(payer), 10000000, B.purposeIsWellformedNoDatumV3),
        paid(17000000)
      ),
      2 -> B.uint(3000000)
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** A transaction of a script that passes, with its fee and collateral. */
  private lazy val passingScript: Built =
    spending(withDamaged, Vector(4, 5), Vector(4 -> alwaysSucceedsWithDatumV3), Some(6), 37000000)

  /** The stored output the era does not read, as the run raises it. */
  private val unreadable: ConwayRuleDefect => Boolean =
    case ConwayRuleDefect.UtxoState(UtxoStateDefect.StoredOutput(input, _)) =>
      input == withDamaged(3)._1
    case _ => false

  /** The refusal of `j`'s run, stopped by a defect `defect` accepts, on the failures `failures`. */
  private def stopped(
      what: String,
      j: Judged,
      failures: Vector[BbodyPredFailure[Conway]],
      defect: ConwayRuleDefect => Boolean
  ): Unit =
    j.eraRun match
      case Right(Left(_)) =>
        j.eraRefusal match
          case Some(ConwayBlockRun.Refusal.Stopped(fs, raised)) =>
            assertEquals(fs.toChain.toVector, failures, what)
            assert(defect(raised), s"$what: stopped by $raised")
          case other => fail(s"$what: not stopped by a defect: $other")
      case other => fail(s"$what: no refusal: $other")

  /** The failures of the block of `txs` (the one before a defect, alone), flagged by `invalid`. */
  private def failuresOf(
      txs: Vector[Built],
      invalid: Vector[Int] = Vector.empty
  ): Vector[BbodyPredFailure[Conway]] =
    judge(third, blockOf(txs, invalid = invalid), change = holding(withDamaged)).eraRefusal match
      case Some(ConwayBlockRun.Refusal.Failed(fs)) => fs.toChain.toVector
      case other                                   => fail(s"not refused: $other")

  test(
    "an earlier transaction refused, then a later one's stored output the era does not read: the block refused on the earlier failure, the defect recorded as ours"
  ) {
    val earlier = failuresOf(Vector(missingInput))
    // the earlier transaction's refusal alone, UTXO's missing input
    assert(
      earlier.exists {
        case ConwayBbodyPredFailure.LedgersFailure(_) => true
        case _                                        => false
      },
      s"$earlier"
    )
    val j = judge(
      third,
      blockOf(Vector(missingInput, damagedCollateral)),
      change = holding(withDamaged)
    )
    // the earlier failure, and none after the defect; the flat rules refuse the block too
    stopped("refused, then a defect", j, earlier, unreadable)
    assertEquals(j.eraFailures, earlier)
    assert(j.flat.isLeft, s"${j.flat}")
    // the defect at the first transaction, nothing recorded before it: no verdict, even though a
    // later transaction would be refused
    Vector(Vector(damagedCollateral), Vector(damagedCollateral, missingInput)).foreach { txs =>
      val alone = judge(third, blockOf(txs), change = holding(withDamaged))
      assert(alone.eraRun.left.exists(unreadable), s"${txs.size}: ${alone.eraRun}")
      assertEquals(alone.eraRefusal, None, s"${txs.size}")
    }
    // a gap: the damaged output as a later transaction's reference input (or input) is read by
    // BBODY before LEDGERS judges any transaction, so nothing is recorded before the defect and the
    // block is withheld, though the reference refuses it on the earlier transaction
    val early = judge(
      third,
      blockOf(Vector(missingInput, damagedReference)),
      change = holding(withDamaged)
    )
    assert(early.eraRun.left.exists(unreadable), s"${early.eraRun}")
    assertEquals(early.eraRefusal, None)
    // the prepared plan's result is the serial run's in each (`judge`'s `samePlanned`)
  }

  test(
    "an earlier transaction refused, then a later one's script the decoder cannot judge: the block refused on the earlier failure, the defect recorded as ours"
  ) {
    // the decoder gives no verdict on any script, so on the reference script the third
    // transaction's output holds; the earlier transactions hold none
    val noVerdict: ScriptDecoder[cats.Id] = (_, _, _) =>
      Left(ScriptDecodeFailure.Unavailable(xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted))
    val undecided: ConwayRuleDefect => Boolean =
      case ConwayRuleDefect.Ledger(LedgerFailure.Unavailable(txId, _, _)) =>
        txId == referenceScriptOut.id
      case _ => false
    val earlier = failuresOf(Vector(missingInput))
    val j = judge(
      third,
      blockOf(Vector(missingInput, referenceScriptOut)),
      change = holding(withDamaged),
      decoder = noVerdict
    )
    stopped("refused, then a defect", j, earlier, undecided)
    val alone = judge(
      third,
      blockOf(Vector(referenceScriptOut, missingInput)),
      change = holding(withDamaged),
      decoder = noVerdict
    )
    assert(alone.eraRun.left.exists(undecided), s"${alone.eraRun}")
  }

  test(
    "a later defect after deferred scripts: the prepared era run comes to the serial run's refusal or no verdict, through the node's bounded workers too"
  ) {
    // the script transaction flagged valid: no failure before the defect, so no verdict; flagged
    // invalid, its script passes, a failure the preparation does not record (its verdict lands
    // later), so the plan's result is the serial run's refusal, stopped by the defect; and a
    // phase-1 refusal after it, which the preparation records too; and both, the mismatch, which
    // only the serial run records, before the phase-1 refusal
    val mismatch = failuresOf(Vector(passingScript), invalid = Vector(0))
    val missing = failuresOf(Vector(passingScript, missingInput))
    val both = failuresOf(Vector(passingScript, missingInput), invalid = Vector(0))
    Vector[(String, Vector[Built], Vector[Int], Option[Vector[BbodyPredFailure[Conway]]])](
      ("flagged valid", Vector(passingScript, damagedCollateral), Vector.empty, None),
      ("flagged invalid", Vector(passingScript, damagedCollateral), Vector(0), Some(mismatch)),
      (
        "a phase-1 refusal",
        Vector(passingScript, missingInput, damagedCollateral),
        Vector.empty,
        Some(missing)
      ),
      (
        "flagged invalid, then a phase-1 refusal",
        Vector(passingScript, missingInput, damagedCollateral),
        Vector(0),
        Some(both)
      )
    ).traverse_ { (what, txs, invalid, failures) =>
      val j = judge(
        third,
        blockOf(txs, invalid = invalid),
        change = holding(withDamaged),
        observe = true
      )
      assertEquals(j.deferred.flatten, Vector(0), what)
      failures match
        case None     => assert(j.eraRun.left.exists(unreadable), s"$what: ${j.eraRun}")
        case Some(fs) => stopped(what, j, fs, unreadable)
      val serial = j.replayed(None).serial
      Vector(1, 2, 4).traverse_ { n =>
        ParallelPlutus.Workers
          .from(n)
          .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
          .flatMap(_(j.replayed(None)))
          .map(result => samePlanned(s"$what at $n workers", serial, Right(result)))
      }
    }
  }

  test(
    "two transactions of one block proposing: the era run holds the proposals across them and writes back the flat BBODY's record"
  ) {
    // the era run reads the node's record of the proposals once, where GOV first reads them, holds
    // them across the block's transactions, and writes them back once after it (`ConwayBlockRun`):
    // the second transaction's GOV judges the first one's proposal, and the record after the block
    // is the flat rules' (the cluster's parameters are at major 9, the bootstrap phase, where an
    // info action may be proposed and its return account is not looked up)
    val params = ConwayNewEpochState.gov.get(judge(third, third.raw).ticked).curPParams
    val deposit = params.govActionDeposit
    val fund = deposit + 20000000
    val utxo = funded(Vector(paid(fund), paid(fund)))
    def proposing(ix: Int): Built =
      val body = B.body(
        0 -> B.set(in(utxo, ix)),
        1 -> B.arr(paid(17000000)),
        2 -> B.uint(3000000),
        20 -> B.set(
          B.arr(
            B.uint(deposit),
            B.bytes(B.rewardAccount(payer)),
            B.arr(B.uint(6)),
            B.arr(
              B.leaf(Value.Text(s"https://example.com/$ix")),
              B.bytes(Bytes(Vector.fill(32)(0)))
            )
          )
        )
      )
      built(body, B.witnesses(body, Vector(payer)))
    val txs = Vector(proposing(0), proposing(1))
    val j = judge(third, blockOf(txs), change = holding(utxo))
    assert(j.era.isRight, s"${j.era}")
    same("proposing", j.era, j.flat)
    j.era.foreach((ls, _) =>
      assertEquals(
        ls.utxo.gov.proposals.actions.map(_.id),
        txs.map(t => xyz.kd5ujc.scarab.ledger.GovActionId(t.id, 0))
      )
    )
    // a block with no transaction leaves the record as it was, never read
    val empty = judge(third, blockOf(Vector.empty), change = holding(utxo))
    same("empty", empty.era, empty.flat)
  }

  test("the injections into Conway's BBODY are the reference's compositions") {
    // LEDGERS's and below, `shelleyToConwayBbodyPredFailure . Shelley.LedgersFailure .
    // injectFailure` (`Conway/Rules/Bbody.hs:164-165`, cardano-ledger f649f975), through LEDGERS's
    // own injection
    val treasury =
      new xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[
        Conway
      ](EraMismatch(xyz.kd5ujc.scarab.ledger.state.Coin(1), xyz.kd5ujc.scarab.ledger.state.Coin(2)))
    assertEquals(
      summon[InjectBbodyFailure[
        xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure[Conway],
        Conway
      ]].injectFailure(treasury),
      ConwayBbodyPredFailure.LedgersFailure[Conway](
        ShelleyLedgersPredFailure.LedgerFailure(treasury)
      )
    )
    val m = EraMismatch[RelEQ, Long](1L, 2L)
    assertEquals(
      summon[InjectBbodyFailure[ShelleyBbodyPredFailure[Conway], Conway]]
        .injectFailure(new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)),
      new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)
    )
    val u = EraMismatch[RelLTEQ, ExUnits](ExUnits(1, 2), ExUnits(0, 0))
    assertEquals(
      summon[InjectBbodyFailure[AlonzoBbodyPredFailure[Conway], Conway]]
        .injectFailure(new AlonzoBbodyPredFailure.TooManyExUnits[Conway](u)),
      new ConwayBbodyPredFailure.TooManyExUnits[Conway](u)
    )
    assertEquals(
      summon[InjectBbodyFailure[AlonzoBbodyPredFailure[Conway], Conway]].injectFailure(
        AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure(
          new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)
        )
      ),
      new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)
    )
  }
