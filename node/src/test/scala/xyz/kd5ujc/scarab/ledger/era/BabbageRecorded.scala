// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.data.NonEmptyVector
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CollectError as FlatCollectError,
  Horizon,
  PlutusCase,
  PosixClock,
  PpupFailure,
  ProtocolParams,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.era.babbage.{BabbageTestKit, BabbageTx}
import xyz.kd5ujc.scarab.ledger.era.rules.ShelleyPpupPredFailure
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, Tick}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Build,
  NewEpochState,
  ShelleyBasedEra,
  ShelleyCertState,
  SlotNo,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.{Family, RefusalEra}
import xyz.kd5ujc.scarab.protocol.{Era as ProtocolEra, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** The recorded Babbage transactions the era rules of UTXO and UTXOS are held to the flat rules and
  * the reference on (`BabbageUtxosSuite`, `BabbageUtxoSuite`), each read both ways with the state
  * it is judged on:
  *
  *   - the 22 Babbage submissions of `fixtures/reference/v1-context` (protocol 7 and 8), each with
  *     the outputs it spends, references and puts up as collateral, and the reference's verdict;
  *   - the 32 Babbage cases of the refusal corpora, each with the reference's state ticked to the
  *     slot it was judged at and its UTxO set, and the reference's refusal.
  *
  * Neither corpus places its slot past an era history's horizon, so each is judged with one era
  * from the start that never ends ([[covering]]); the forecast-horizon corpora's cases carry the
  * era history of their cluster (`BabbageUtxoSuite`).
  */
object BabbageRecorded extends munit.Assertions:
  given ShelleyBasedEra[Babbage] = ShelleyBasedEra.SBabbage

  def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  def slotNo(n: BigInt): SlotNo = right(s"slot $n")(SlotNo.fromBigInt(n))

  def hex(s: String): Bytes = right(s)(Bytes.fromHex(s))

  /** One era from slot 0 at the geometry of `globals`, which never ends: every slot translates. */
  def covering(globals: Globals): Horizon =
    val g = globals.geometry
    val window = g.stabilityWindow.toBigInt
    Horizon(
      NonEmptyVector.one(
        Horizon.Era(
          Horizon.Edge(0, 0, 0),
          None,
          Horizon.Params(g.epochLength, g.slotLengthMillis, window, window)
        )
      )
    )

  /** A transaction both ways, with the state and parameters it is judged on, the clock its scripts'
    * contexts are given and the era history UTXO's forecast is checked against.
    */
  final case class Judged(
      name: String,
      flat: FlatTx,
      typed: BabbageTx,
      known: Map[TxIn, FlatTxOut],
      pp: ProtocolParams,
      cert: ShelleyCertState,
      state: UTxOState[Babbage],
      globals: Globals,
      slot: BigInt,
      clock: PosixClock,
      epochInfo: Horizon
  ):
    /** The flat rules' environment at `slot`, of the same geometry the era rules ask (`Globals`).
      */
    def ctx: TxEnv =
      val epoch = globals.epochOf(slot)
      TestLedger.env.copy(
        slot = slot,
        epoch = epoch,
        network = globals.networkId.wire,
        firstSlotNextEpoch = globals.firstSlot(epoch + 1),
        stabilityWindow = globals.stabilityWindow,
        updateQuorum = globals.quorum,
        clock = Some(clock)
      )

  // ---- the recorded V1-context submissions

  final case class Verdict(accepted: Boolean, text: String)

  lazy val v1: Vector[(PlutusCase, Verdict)] =
    Vector("babbage-pv7", "babbage-pv8").flatMap { name =>
      val path = Path.of(s"fixtures/reference/v1-context/$name.json")
      if !Files.isRegularFile(path) then fail(s"missing committed fixture $path")
      right(name)(parse(Files.readString(path))).asArray
        .getOrElse(fail(s"$name: not an array"))
        .map { j =>
          val reference = j.hcursor.downField("reference")
          PlutusCase.fromJson(j) -> Verdict(
            right("accepted")(reference.get[Boolean]("accepted")),
            right("error")(reference.get[String]("error"))
          )
        }
    }

  def v1Judged(c: PlutusCase): Judged =
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, c.tx.isValid)
    val known = c.spent ++ c.references ++ c.collateral
    val nes: NewEpochState[Babbage] = Build.funded(Build.nes[Babbage](5, c.params), known)
    val ls = nes.es.ls
    Judged(
      c.name,
      c.tx,
      right(s"${c.name} typed")(BabbageTx.decode(parts, StateLayout.Node11_1)),
      known,
      c.params,
      ls.cert,
      ls.utxo,
      TestLedger.globals,
      TestLedger.env.slot,
      TestLedger.env.clock.getOrElse(fail("no clock")),
      covering(TestLedger.globals)
    )

  def caseNamed(name: String): PlutusCase =
    v1.map(_._1).find(_.name == name).getOrElse(fail(s"no case $name"))

  // ---- the refusal corpora's Babbage cases, with their recorded states

  /** A recorded case: its judgment both ways and the reference's reason. */
  final case class Refused(judged: Judged, reason: Bytes, families: Vector[Family])

  lazy val refusals: Vector[Refused] =
    val loaded = right("the refusal corpus")(
      ReferenceRefusalSuite.load(Path.of("fixtures/reference/refusals"))
    )
    loaded.corpora.filter(_.era == RefusalEra.Babbage).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val globals = Globals(setup.globals, setup.geometry)
      val codecs = right(s"${c.path} layout")(ReferenceRefusalSuite.stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val nes = right(s"$what state")(
          codecs.newEpochState[Babbage].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val known = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        val funded = NewEpochState
          .ledger[Babbage]
          .modify(ls =>
            ls.copy[Babbage](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(known))))
          )(nes)
        val slot = rc.tipSlot + 1
        val ticked = right(s"$what tick")(
          Sts(Tick.sts[Babbage])(globals, (), funded, right("slot")(SlotNo.fromBigInt(slot)))
        )
        val ls = ticked.es.ls
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        Refused(
          Judged(
            what,
            right(s"$what flat")(FlatTx.fromEnvelope(bytes, ProtocolEra.Babbage)),
            right(s"$what typed")(BabbageTx.fromEnvelope(bytes, StateLayout.Node11_1)),
            known,
            ls.utxo.gov.curPParams,
            ls.cert,
            ls.utxo,
            globals,
            slot,
            PosixClock.Total(_ * 1000),
            covering(globals)
          ),
          rc.reason,
          rc.families
        )
      }
    }

  // ---- UTXOS's failures, compared as one type

  /** A failure to collect a script with its context, the purpose that has no redeemer by its tag
    * and index.
    */
  enum Collect:
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoWitness(hash: Bytes)
    case NoCostModel(language: xyz.kd5ujc.scarab.plutus.PlutusLanguage)
    case BadTranslation(error: TranslationError)

  /** A purpose's tag and index, of the scripts the body needs (`getScriptsNeeded`) whose item it
    * names, as `BabbagePlutusContextRefusalsSuite` reads them.
    */
  def purposeIx(
      needed: Vector[AlonzoPlutusPurpose[AsIxItem, Babbage]],
      purpose: AlonzoPlutusPurpose[AsItem, Babbage]
  ): Collect.NoRedeemer =
    val p = needed
      .find(p =>
        EraScript.babbage.hoistPlutusPurpose[AsIxItem, AsItem](
          [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
        )(p) == purpose
      )
      .getOrElse(fail(s"$purpose is not needed"))
    val ix = EraScript.babbage
      .hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(
        p
      )
    Collect.NoRedeemer(ix.tag, ix.ix.value)

  /** The era's failure to collect, as [[Collect]]. */
  def collectOf(needed: Vector[AlonzoPlutusPurpose[AsIxItem, Babbage]])(
      e: CollectError[Babbage]
  ): Collect = e match
    case CollectError.NoRedeemer(purpose) => purposeIx(needed, purpose)
    case CollectError.NoWitness(h)        => Collect.NoWitness(h)
    case CollectError.NoCostModel(l)      => Collect.NoCostModel(l)
    case CollectError.BadTranslation(e) =>
      Collect.BadTranslation(BabbageTestKit.flatContextError(e))

  /** The flat failure to collect, as [[Collect]]. */
  def flatCollectOf(e: FlatCollectError): Collect = e match
    case FlatCollectError.NoRedeemer(p)     => Collect.NoRedeemer(p.tag, BigInt(p.index))
    case FlatCollectError.NoWitness(h)      => Collect.NoWitness(h)
    case FlatCollectError.NoCostModel(l)    => Collect.NoCostModel(l)
    case FlatCollectError.BadTranslation(e) => Collect.BadTranslation(e)

  /** PPUP's failure as the flat failure, whose projection it is. */
  def flatPpupOf(f: ShelleyPpupPredFailure[Babbage]): PpupFailure = f match
    case ShelleyPpupPredFailure.NonGenesisUpdatePPUP(m) =>
      PpupFailure.NonGenesisUpdate(
        xyz.kd5ujc.scarab.ledger.Mismatch(m.supplied.map(_.bytes), m.expected.map(_.bytes))
      )
    case ShelleyPpupPredFailure.PPUpdateWrongEpoch(c, e, vp) =>
      PpupFailure.PPUpdateWrongEpoch(c.toBigInt, e.toBigInt, vp)
    case ShelleyPpupPredFailure.PVCannotFollowPPUP(pv) =>
      PpupFailure.PVCannotFollow(BigInt(pv.pvMajor.value), pv.pvMinor.value)

  /** The scripts the body of `j` needs, by their purpose's index and item. */
  def needed(j: Judged, utxo: UTxO[Babbage]): Vector[AlonzoPlutusPurpose[AsIxItem, Babbage]] =
    val t = EraTx.babbage
    EraUTxO.babbage
      .getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](j.typed)(using t.bodyOf.top))(using
        t.bodyOf.top
      )
      .unAlonzoScriptsNeeded
      .map(_._1)
