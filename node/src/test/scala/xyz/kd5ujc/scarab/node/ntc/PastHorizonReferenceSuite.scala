// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.consensus.state.{EraHistory, MirrorClock, Past, Placement}
import xyz.kd5ujc.scarab.consensus.ForkTrigger
import xyz.kd5ujc.scarab.ledger.{
  LedgerFailure,
  PlutusStats,
  PosixClock,
  Tx,
  TxEnv,
  TxIn,
  TxOut,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.rules.{Counted, ShelleyLedger, ShelleyLedgerEnv, Sts, Tick}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  Conway,
  EraTag,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.forge.BlockBuilder
import xyz.kd5ujc.scarab.node.mempool.{
  Entry,
  LocalSubmission,
  MempoolEnv,
  MempoolLedger,
  MempoolState,
  Source
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** The refusals of a validity upper bound past the forecast horizon that the reference node
  * (11.1.3) sent over LocalTxSubmission, recorded on throwaway devnets in
  * `fixtures/reference/plutus-forecast-horizon` (provenance in its README): the complete
  * `ApplyTxErr`, the state the transaction was judged against at the recorded tip and the genesis
  * of the cluster. Each case is put through Scarab's own rules with the clock of the era history at
  * that tip (the eras of the cluster as its genesis places them, the transition of the era known
  * where the next era's epoch is configured) and the bytes must equal the reference's.
  *
  * A corpus directory that is absent is an incomplete gate: the suite fails.
  */
class PastHorizonReferenceSuite extends FunSuite:
  private val root = Path.of("fixtures/reference/plutus-forecast-horizon")

  private final case class Recorded(
      dir: Path,
      era: Era,
      item: Bytes,
      reason: Option[Bytes],
      tip: BigInt,
      state: Bytes,
      utxo: Bytes,
      label: String
  )

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private lazy val recorded: Vector[Recorded] =
    // committed fixtures: a missing directory fails rather than leaving no cases to run
    if !Files.isDirectory(root) then fail(s"missing committed fixture directory $root")
    else
      val corpora = Files.list(root).iterator.asScala.toVector.sorted.filter(Files.isDirectory(_))
      corpora.flatMap { dir =>
        val cases = dir.resolve("cases")
        Files.list(cases).iterator.asScala.toVector.sorted.map { file =>
          val json = parse(Files.readString(file)).fold(e => fail(e.message), identity).hcursor
          def text(k: String) = json.get[String](k).fold(e => fail(e.message), identity)
          val era = text("era") match
            case "alonzo"  => Era.Alonzo
            case "babbage" => Era.Babbage
            case "conway"  => Era.Conway
            case other     => fail(s"era $other")
          Recorded(
            dir,
            era,
            hex(text("item")),
            Option.when(!json.get[Boolean]("accepted").getOrElse(false))(hex(text("reason"))),
            json.downField("tip").get[BigInt]("slot").fold(e => fail(e.getMessage), identity),
            Bytes.fromArray(Files.readAllBytes(dir.resolve(text("state")))),
            Bytes.fromArray(Files.readAllBytes(dir.resolve(text("utxo")))),
            text("label")
          )
        }
      }

  test("the recorded corpus holds a case for each era from protocol version 5") {
    assertEquals(
      recorded.map(_.era).toSet,
      Set(Era.Alonzo, Era.Babbage, Era.Conway),
      "incomplete: no reference refusal for the eras missing here"
    )
  }

  /** The era history of the cluster at `tip`, from its genesis: every era starts at the epoch the
    * configuration gives it (`Test<Era>HardForkAtEpoch`), and the era's transition is known where
    * the next era's epoch is configured.
    */
  private def clockAt(setup: NodeSetup, era: Era, epoch: BigInt, tip: BigInt): PosixClock =
    val placement = Placement(setup.geometry, setup.byronHistory, setup.praos.forks)
    val forks = setup.praos.forks
    val starts: Vector[BigInt] = Era.values.toVector.take(era.index + 1).map {
      case Era.Byron   => BigInt(0)
      case Era.Shelley => setup.geometry.firstShelleyEpoch
      case other =>
        forks.triggers(other) match
          case ForkTrigger.AtEpoch(e)   => e
          case ForkTrigger.AtVersion(_) => fail(s"$other is not placed by an epoch")
    }
    val ends = starts.tail.zipWithIndex.map((e, i) => i -> placement.bound(e)).toMap
    val past = (0 until era.index).toVector.map(i => Past(placement.bound(starts(i)), ends(i)))
    val history = EraHistory.horizonHistory(
      setup.geometry,
      setup.byronHistory,
      era,
      past,
      placement.bound(starts(era.index))
    )
    val transition = EraHistory.transition(
      forks,
      BigInt(setup.geometry.securityParam),
      era,
      epoch,
      None,
      0,
      Some(tip)
    )
    PosixClock.forecast(
      setup.geometry,
      history,
      transition,
      Option.when(era == Era.Alonzo)(tip + 1)
    )

  private def withUtxo[E <: EraTag](
      nes: NewEpochState[E],
      utxo: Map[TxIn, TxOut]
  ): NewEpochState[E] =
    NewEpochState
      .ledger[E]
      .modify(ls => ls.copy[E](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(utxo)))))(nes)

  private def stateOf[E <: EraTag](r: Recorded)(using ShelleyBasedEra[E]): NewEpochState[E] =
    val nes = HaskellState.decode[E](r.state).fold(e => fail(e.render), identity)
    val utxo = UtxoCodecs.utxo.decodeBytes(r.utxo).fold(e => fail(e.render), identity)
    withUtxo(nes, utxo)

  private def setupOf(r: Recorded): NodeSetup =
    NodeSetup.fromDirectory(r.dir.resolve("genesis")).fold(e => fail(e.message), identity)

  private def envelope(item: Bytes, era: Era): Tx =
    val payload = Cbor.decode(item).toOption.map(_.value).collect { case Value.Arr(Vector(_, p)) =>
      p.value match
        case Value.Tag(_, b) =>
          b.value match
            case Value.ByteString(bytes) => bytes
            case other                   => fail(s"item: $other")
        case other => fail(s"item: $other")
    }
    Tx.fromEnvelope(payload.getOrElse(fail("item")), era).fold(e => fail(e.message), identity)

  /** The reference's era-`E` LEDGER rule and encoding on the recorded state ticked to the slot
    * after the tip, the transaction judged as the first of a block there.
    */
  private def replayPreConway[E <: EraTag](r: Recorded, era: Era)(using
      ShelleyBasedEra[E],
      xyz.kd5ujc.scarab.ledger.rules.PreConway[E]
  ): Option[Bytes] =
    val setup = setupOf(r)
    val env = MempoolEnv(setup.globals, setup.geometry)
    val nes = stateOf[E](r)
    val slot = SlotNo.fromBigInt(r.tip + 1).fold(e => fail(e.toString), identity)
    val ticked = Sts(Tick.sts[E])(env.rules, (), nes, slot).fold(e => fail(e.show), identity)
    val txEnv = TxEnv(
      slot = slot.toBigInt,
      epoch = ticked.epoch.toBigInt,
      network = env.globals.network,
      firstSlotNextEpoch = env.geometry.firstSlot(ticked.epoch.toBigInt + 1),
      stabilityWindow = env.geometry.stabilityWindow,
      txIndex = 0,
      updateQuorum = env.globals.updateQuorum,
      clock = Some(clockAt(setup, era, nes.epoch.toBigInt, r.tip)),
      evaluator = env.evaluator,
      decoder = env.decoder
    )
    Sts(ShelleyLedger.sts[E])(
      env.rules,
      ShelleyLedgerEnv(txEnv, ticked.es.chainAccount),
      Counted(ticked.es.ls, PlutusStats.zero),
      envelope(r.item, era)
    ) match
      case Left(LedgerFailure.Rejected(_, f)) =>
        Some(
          PreConwayFailureCbor
            .applyTxError(era, Vector(f))
            .getOrElse(fail(s"no encoding for ${f.detail}"))
        )
      case Left(other) => fail(s"not judged: ${other.message}")
      case Right(_)    => None

  private def replayConway(r: Recorded): Option[Bytes] =
    val setup = setupOf(r)
    val nes = stateOf[Conway](r)(using ShelleyBasedEra.SConway)
    val env = MempoolEnv(
      setup.globals,
      setup.geometry,
      clock = Some(clockAt(setup, Era.Conway, nes.epoch.toBigInt, r.tip))
    )
    val ledger =
      MempoolLedger.tick(nes, Some(r.tip), env).fold(e => fail(e.show), identity)
    val pool = MempoolState.empty(Right(ledger), r.tip, None)
    LocalSubmission
      .unwrap(r.item)
      .flatMap(tx => pool.add(tx, env, Source.Local).map(_ => ()))
      .fold(
        rejection => Some(LocalSubmission.reasonItem(rejection).fold(e => fail(s"$e"), identity)),
        _ => None
      )

  recorded.foreach { r =>
    test(s"${r.era} ${r.label}: Scarab gives the reference's verdict, a refusal byte for byte") {
      val ours = r.era match
        case Era.Alonzo =>
          replayPreConway[Alonzo](r, Era.Alonzo)(using
            ShelleyBasedEra.SAlonzo,
            summon
          )
        case Era.Babbage =>
          replayPreConway[Babbage](r, Era.Babbage)(using
            ShelleyBasedEra.SBabbage,
            summon
          )
        case _ => replayConway(r)
      assertEquals(ours.map(_.hex), r.reason.map(_.hex))
    }
  }

  recorded.filter(_.era == Era.Conway).foreach { r =>
    test(s"${r.label}: the forger leaves out what the horizon refuses, and takes it without one") {
      val setup = setupOf(r)
      val nes = stateOf[Conway](r)(using ShelleyBasedEra.SConway)
      val bounded = clockAt(setup, Era.Conway, nes.epoch.toBigInt, r.tip)
      val env = MempoolEnv(setup.globals, setup.geometry, clock = Some(bounded))
      val ledger = MempoolLedger.tick(nes, Some(r.tip), env).fold(e => fail(e.show), identity)
      val raw = LocalSubmission.unwrap(r.item).fold(e => fail(s"$e"), identity)
      val tx = Tx.fromEnvelope(raw, Era.Conway).fold(e => fail(e.message), identity)
      val entry = Entry(tx.body.id, raw, tx, 0)
      val limits =
        BlockBuilder.Limits(ledger.params.maxBlockBodySize.toLong, ledger.params.maxBlockExUnits)
      def chosen(clock: PosixClock) =
        BlockBuilder
          .select(
            ledger,
            r.tip + 1,
            ledger.epoch,
            setup.geometry,
            setup.globals,
            Vector(entry),
            limits,
            Some(clock)
          )
          .fold(e => fail(e.message), _.size)
      assertEquals(chosen(bounded), 0)
      assertEquals(chosen(PosixClock.total(setup.geometry)), 1)
    }
  }

  // the devnet that starts in Conway has every era begin at epoch zero, so a mirror at the start of
  // the chain, extended into Conway, is the one the cluster has (a chain that walks through the eras
  // places them where it enters them, which the follower scenarios check)
  recorded.filter(_.era == Era.Conway).foreach { r =>
    test(s"${r.era} ${r.label}: the mirror places the eras as the genesis of the cluster does") {
      // at Origin the safe zone starts at slot zero, as for the tip -1
      val setup = setupOf(r)
      val placement = Placement(setup.geometry, setup.byronHistory, setup.praos.forks)
      val ours = MirrorClock.of(setup.mirrorStart, placement, r.era, r.tip + 1)
      val expected = clockAt(setup, r.era, 0, -1)
      def horizon(clock: PosixClock) = clock match
        case f: PosixClock.Forecast => f.horizon
        case e: PosixClock.Extended => e.base.horizon
        case other                  => fail(s"$other")
      assertEquals(horizon(ours), horizon(expected))
    }
  }
