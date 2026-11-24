// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.Id
import cats.data.{NonEmptyList, NonEmptySet, NonEmptyVector}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.{Horizon, PosixClock, TranslationError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.{hex, json, node}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraImpBatch
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Build,
  ChainAccountState,
  Coin,
  Conway,
  EpochNo,
  Hash32,
  LedgerState,
  Network,
  ShelleyBasedEra,
  TxIx
}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalResult, ExUnits, PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.protocol.{Era as FlatEra, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** The reference's BBODY cases that run for Conway, each with its exact expectation, at each of
  * Conway's protocol versions, on blocks made only by `ConwayBlock.decode` of bytes whose header
  * commits to its body, judged by the node's own era run (`ConwayBlockRun`), serially and prepared
  * for its bounded parallel evaluation ([[ConwayPlanned.check]]: the plan's jobs run in reverse and
  * with none run, and at one, two, four and eight workers, through the node's own scheduler in
  * `ConwayBbodyImpWorkersSuite`), the transactions built as the Imp's fixups leave them (real CBOR,
  * real signatures, the reference's script binaries checked by hash), every check run and every
  * script evaluated.
  *
  * What runs for Conway at cardano-ledger f649f975: `Conway/Imp.hs:52-66` runs Babbage's driver, so
  * Alonzo's `BBODY.spec` (`Babbage/Imp.hs:36`, `Alonzo/Imp.hs:31-34`), and Conway's own
  * (`Conway/Imp.hs:55`), each `withImpInitEachEraVersion`, at each of `eraProtVersions` (9, 10 and
  * 11: `ProtVerLow ConwayEra = 9`, `ProtVerHigh ConwayEra = 11`,
  * `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:132-133`), Alonzo's
  * for each of `eraLanguages` (V1 to V3: `eraMaxLanguage = PlutusV3`, `Conway/Scripts.hs:98`):
  *   - Alonzo's (`Alonzo/Imp/BbodySpec.hs`): for each language, a block admitted with a
  *     phase-2-invalid and a valid transaction of each script purpose (spending, minting,
  *     withdrawing, certifying), each invalid one first refused flagged valid with exactly its
  *     script's failure, on the script stake credential registered before it (`:46`); the block's
  *     execution units past `maxBlockExUnits`, each script spend after the transaction producing
  *     its collateral, as the block records them (`:91`);
  *   - Conway's (`Conway/Imp/BbodySpec.hs`): the block's reference scripts past
  *     `maxRefScriptSizePerBlock`, held before the block (`:43`) and, from version 11
  *     (`whenMajorVersionAtLeast @11`), created in it (`:86`, which at 9 and 10 runs nothing, and
  *     so runs here at 11 alone); the function `totalRefScriptSizeInBlock` on a block's
  *     transactions as they are applied, and on every prefix of them before (`:122`), and with a
  *     collateral return holding the script, its transaction first refused flagged valid with
  *     exactly its script's failure (`:195`, an `it` at f649f975).
  *
  * Shelley's (`Shelley/Imp/BbodySpec.hs`) are not in f649f975's tree: Shelley's `Imp.hs` runs no
  * BBODY spec there (`Shelley/Imp.hs`), and no test of that tree names `WrongBlockBodySizeBBODY` or
  * `InvalidBodyHashBBODY`. They are ported from cardano-ledger 9df9f98c, its first pin that has
  * them, as Conway's driver runs them there (`Shelley/Imp/BbodySpec.hs:28`, `:43`, `:58`), against
  * f649f975's rule: `Shelley/Rules/Bbody.hs` differs between the two pins only by the signal's name
  * (`BbodySignal`, `TPraosBbodySignal`) and an `Ord` instance of the failure, and the Imp's block
  * is made the same way (`tryTxsInBlock'`, `Shelley/ImpTest.hs:1494-1542` at f649f975). Neither pin
  * has a case of `HeaderProtVerTooHigh`, which Conway's BBODY checks only on mainnet below version
  * 12 (`Conway/Rules/Bbody.hs:294-312`, f649f975), and the Imp's network is a testnet (`sgNetworkId =
  * Testnet`, `Shelley/ImpTest.hs:823`): it has no oracle case here.
  *
  * The parameters are Preview's genesis files' (a Conway testnet: its Shelley parameters are the
  * Imp's, `Shelley/ImpTest.hs:832-847`, and so are its Alonzo ones but the block's units), with the
  * Imp's units, 10,000,000 and 10,000,000,000 a transaction and 200,000,000 and 200,000,000,000 a
  * block (`agMaxTxExUnits`, `agMaxBlockExUnits`, `Alonzo/ImpTest.hs:422-446`), the cost models of a
  * recorded Preview transaction at version 11 (the Imp's are `testingCostModel`, which no fixture
  * holds; every unit a case declares or asserts is the scripts' own on the models in force) and the
  * case's protocol version. A transaction the Imp's fixups fund from its root output is funded from
  * an input of its own, but for the last of `:122`, which spends the output its root spend leaves,
  * as the Imp's root then is.
  *
  * Incomplete (CNF-2), the draws, each fixed: the payer's address where the Imp draws a fresh key's
  * (`freshKeyAddr_`); the fees and amounts, fixed and not the fixups' (`fixupTxOuts` draws an
  * amount before `ensureMinCoinTxOut`, and `fixupFees` derives the change from the root's balance):
  * what the cases show is that their fixed fees and outputs satisfy the ledger's minima where
  * LEDGER applies them; of `:46`, the minted token's name and one token (`mkTokenMintingTx`,
  * `Mary/ImpTest.hs:44-54`), and the certificates' forms, the registration with its deposit and the
  * unregistration with its refund (`conwayGenRegTxCert`, `conwayGenUnRegTxCert`,
  * `Conway/ImpTest.hs:423-452`, which draw them or the legacy forms); of `:43` and `:86`, the
  * counts (`genNumAdditionsExceeding`), the largest its generator allows. The refusals flagged
  * valid (`:46`, `:195`) are compared by each failing script's machine error and trace and the
  * request it ran: the machine's failure holds terms, equal only as the same object.
  */
class ConwayBbodyImpSuite extends FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val tx = EraTx.conway
  private val bodies = EraTxBody.conway
  private given bodies.Level[TxLevel.Top] = bodies.top
  private val B = DijkstraImpBatch

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  /** Conway's protocol versions, each of which every case runs at. */
  private val versions: Vector[Int] = Vector(9, 10, 11)

  private val layout: StateLayout = ok(StateLayout.of(ReferenceBuild.Release11_1))

  // ---- the parameters, the globals and the state -----------------------------------------------------

  private lazy val genesis: flat.ProtocolParams =
    def file(era: String) = json(s"fixtures/preview/genesis/$era-genesis.json")
    ok(flat.ProtocolParams.fromGenesis(file("shelley"), file("alonzo"), Some(file("conway"))))

  private lazy val costModels: Map[Int, Vector[BigInt]] =
    json("fixtures/preview/plutus/slot124905885-561eca9a.json").hcursor
      .get[Map[Int, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)

  /** The stored parameters at protocol version `major`. */
  private def stored(major: Int): flat.ProtocolParams =
    genesis.copy(
      protocolMajor = major,
      protocolMinor = 0,
      maxTxExUnits = ExUnits(10000000, BigInt(10000000000L)),
      maxBlockExUnits = ExUnits(200000000, BigInt(200000000000L)),
      costModels = costModels
    )

  private def pp(major: Int): PParams[Conway] =
    PParams[Conway](ok(ConwayPParams.fromStored(stored(major))))

  private val globals: flat.rules.Globals =
    flat.TestLedger.globals.copy(ledger =
      flat.TestLedger.globals.ledger
        .copy(network = Network.Testnet, referenceBuild = ReferenceBuild.Release11_1)
    )

  /** One era from the start at the globals' geometry, as `fixedEpochInfo` is. */
  private val horizon: Horizon =
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

  private val slotToPosix: BigInt => Either[TranslationError, BigInt] = s => Right(s * 1000)
  private val clock: PosixClock = PosixClock.Total(_ * 1000)

  private val context: UtxoContext[Conway] = UtxoContext[Conway](
    Network.Testnet,
    globals.systemStart,
    horizon,
    flat.TxEnv.Validation.All,
    ConwayUtxo.injectShelleyFailure(ReferenceBuild.Release11_1)
  )

  /** The ledger state at version `major` holding exactly the outputs `utxo`. */
  private def holding(major: Int, utxo: Map[TxIn, TxOut]): LedgerState[Conway] =
    Build.funded[Conway](Build.nes[Conway](0L, stored(major)), utxo).es.ls

  /** The outputs given, each at its input, read by the node's flat decoder from bodies holding
    * them, a hundred each.
    */
  private def outputs(entries: (B.In, Node)*): Map[TxIn, TxOut] =
    entries
      .grouped(100)
      .flatMap { group =>
        val body = B.body(
          0 -> B.set(B.fresh(0xff).node),
          1 -> B.arr(group.map(_._2)*),
          2 -> B.uint(0)
        )
        val read = ok(
          flat.Tx.fromBlock(
            flat.Block.Transaction(B.encode(body), B.encode(B.cmap()), None, true),
            FlatEra.Conway
          )
        )
        assertEquals(read.body.outputs.size, group.size)
        group.map(_._1).zip(read.body.outputs).map((in, o) => TxIn(in.txId, in.ix) -> o)
      }
      .toMap

  private def stateUtxo(state: LedgerState[Conway]): Map[TxIn, TxOut] =
    state.utxo.utxo.utxo.toMap

  // ---- transactions as the fixups leave them ---------------------------------------------------------

  private lazy val payer = flat.TestLedger.key()
  private def paid(coin: BigInt): Node = B.out(B.keyAddress(payer), coin)

  /** A transaction as a block holds it: its body's and its witnesses' bytes and its flag, and in
    * the era's types.
    */
  private final case class Built(body: Bytes, wits: Bytes, valid: Boolean):
    def transaction: flat.Block.Transaction = flat.Block.Transaction(body, wits, None, valid)
    def id: Bytes = transaction.id
    def era: ConwayTx = ok(ConwayTx.decode(transaction, layout))

  private def built(body: Node, wits: Node, valid: Boolean = true): Built =
    Built(B.encode(body), B.encode(wits), valid)

  private def outOf(t: Built, ix: Int = 0): B.In = B.In(t.id, ix)

  /** `mkBasicTx mkBasicTxBody` as the fixups leave it: the payer's input `in` of 10 ada spent, a
    * fee of 2 ada and the change to the payer, signed by the payer.
    */
  private def basic(in: B.In): Built =
    val body = B.body(0 -> B.set(in.node), 1 -> B.arr(paid(8000000)), 2 -> B.uint(2000000))
    built(body, B.witnesses(body, Vector(payer)))

  /** `sendCoinTo` of `makeCollateralInput` (`Alonzo/ImpTest.hs:144-149`): 30 ada to the payer, a
    * transaction the Imp records, funded from `in` of 40 ada.
    */
  private def collateralOf(in: B.In): Built =
    val body = B.body(
      0 -> B.set(in.node),
      1 -> B.arr(paid(30000000), paid(8000000)),
      2 -> B.uint(2000000)
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** An output holding `script` of the language tagged `tag` as its reference script. */
  private def refOut(coin: BigInt, tag: Int, script: Node): Node =
    B.cmap(
      B.uint(0) -> B.bytes(B.keyAddress(payer)),
      B.uint(1) -> B.uint(coin),
      B.uint(3) -> B.tagged(24, B.bytes(B.encode(B.arr(B.uint(tag), script))))
    )

  private def fundOf(i: Int): B.In = B.In(Blake2b.hash256.hash(B.encode(B.uint(i))))

  // ---- blocks made from bytes ------------------------------------------------------------------------

  /** The first recorded Conway block of `fixtures/eras`, which has no transaction: its bytes. */
  private lazy val plain: Bytes =
    Files
      .readAllLines(Path.of("fixtures/eras/blocks.hex"))
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(hex)
      .find(raw =>
        items(node(raw)).headOption.exists(_.value match
          case Value.UInt(tag) => tag == 7
          case _               => false)
      )
      .getOrElse(fail("no Conway block"))

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
    * parts and its header body's fields as `body` and `header` make them, every other item the
    * bytes it was read as.
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

  /** The header the Imp makes for a block (`tryTxsInBlock'`, `Shelley/ImpTest.hs:1506-1520`): its
    * body's size and hash (fields 6 and 7: the segments' length and the digest of their digests,
    * `hashAlonzoSegWits`), and the parameters' version, `major` and 0 (field 9).
    */
  private def imp(major: Int)(fields: Vector[Bytes], parts: Vector[Bytes]): Vector[Bytes] =
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
      .updated(9, array(Vector(encoded(Value.UInt(BigInt(major))), encoded(Value.UInt(0)))))

  /** A block of `txs` at version `major`, its header as the Imp makes it and then as `modify`
    * changes it; the invalid transactions' indices those flagged invalid.
    */
  private def blockOf(
      major: Int,
      txs: Vector[Built],
      modify: Vector[Bytes] => Vector[Bytes] = identity
  ): Bytes =
    crafted(plain)(
      _ =>
        Vector(
          array(txs.map(_.body)),
          array(txs.map(_.wits)),
          encoded(Value.Map(Vector.empty)),
          array(txs.zipWithIndex.collect {
            case (t, i) if !t.valid => encoded(Value.UInt(BigInt(i)))
          })
        ),
      (fields, parts) => modify(imp(major)(fields, parts))
    )

  private def decoded(raw: Bytes): Block[ConwayBlockHeader, Conway] =
    ok(ConwayBlock.decode(raw, layout))

  private lazy val slot: BigInt =
    EraBlockHeader.conway.slotNoBlockHeader(decoded(plain)).toBigInt

  // ---- the node's run of a block ---------------------------------------------------------------------

  /** How a prepared block is run with a number of workers ([[ConwayPlanned.check]]): on as many
    * threads here, through the node's scheduler in `ConwayBbodyImpWorkersSuite` (node).
    */
  protected def runner: ConwayPlanned.Runner = ConwayPlanned.threaded

  /** The node's era run of the block `raw` (`ConwayBlockRun.program`) on `state` with `params`, as
    * a body program of an evaluator and the node's phase-2 observer.
    */
  private def program(raw: Bytes, state: LedgerState[Conway], params: PParams[Conway])(
      evaluator: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[Id],
      observing: Option[ConwayBlockRun.Observing]
  ): ConwayBlockRun.Body =
    ConwayBlockRun.program(
      globals,
      BbodyEnv[Conway](params, ChainAccountState(Coin(0), Coin(0))),
      decoded(raw),
      globals.systemStart,
      horizon,
      ShelleyBbodyState[Conway](state, BlocksMade.empty),
      evaluator,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slotToPosix,
      observing
    )

  /** The node's run of the block `raw`, serial and prepared, each to the same result
    * ([[ConwayPlanned.check]]).
    */
  private def checked(
      name: String,
      raw: Bytes,
      state: LedgerState[Conway],
      params: PParams[Conway],
      evaluator: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[Id] = ScarabEvaluator.default
  ): ConwayPlanned.Checked =
    ConwayPlanned.check(name, evaluator, clock, runner)(program(raw, state, params))

  /** The failures the node's run refuses the block `raw` for, the rules having run to its end
    * (`Refusal.Failed`): a defect, a stopped run or an admitted block fails the test.
    */
  private def refused(
      name: String,
      raw: Bytes,
      state: LedgerState[Conway],
      params: PParams[Conway]
  ): Vector[BbodyPredFailure[Conway]] =
    checked(name, raw, state, params).serial match
      case Right(Left(ConwayBlockRun.Refusal.Failed(fs))) => fs.toChain.toVector
      case other => fail(s"$name: not refused by the rules alone: $other")

  private def into[F](f: F)(using i: InjectBbodyFailure[F, Conway]): BbodyPredFailure[Conway] =
    i.injectFailure(f)

  // ---- LEDGER on one transaction, as the Imp submits it ----------------------------------------------

  /** LEDGER with every check on `t` from `state`, at the block's slot (`trySubmitTx`,
    * `Shelley/ImpTest.hs:1254-1300`).
    */
  private def ledgerOn(t: Built, state: LedgerState[Conway], params: PParams[Conway]) =
    val era = t.era
    val read = bodies.allInputs(tx.body[TxLevel.Top](era))
    val utxo = ok(ConwayUTxO.fromStored(stateUtxo(state).view.filterKeys(read).toMap))
    EraLedgerRun.ledger(
      globals,
      LedgerEnv[Conway](
        slot,
        Some(ok(EpochNo.fromBigInt(globals.epochOf(slot)))),
        TxIx.minBound,
        params,
        ChainAccountState(Coin(0), Coin(0))
      ),
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](slotToPosix, params, utxo, Map.empty, era),
      context,
      state,
      ScarabEvaluator.default,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  /** `t` submitted (`submitTx`): admitted by LEDGER, and the state after it, whose UTxO set is as
    * `impAlonzoExpectTxSuccess` (`Alonzo/ImpTest.hs`) and Babbage's collateral return require: a
    * valid transaction's inputs gone and its outputs there, an invalid one's inputs there, its
    * collateral gone and its collateral return there.
    */
  private def step(state: LedgerState[Conway], t: Built, params: PParams[Conway]) =
    val after = ledgerOn(t, state, params) match
      case Right(Right((ls, _))) => ls
      case other                 => fail(s"not admitted: $other")
    val body = tx.body[TxLevel.Top](t.era)
    val (inputs, collateral) = (bodies.inputs[TxLevel.Top](body), bodies.collateralInputs(body))
    val now = stateUtxo(after)
    def stored(utxo: UTxO[Conway]) =
      utxo.unUTxO.view.mapValues(StoredTxOut.conway.toStored).toMap
    tx.isValid(t.era) match
      case IsPhase2Valid.Phase2Valid =>
        inputs.foreach(in => assert(!now.contains(in), s"$in spent"))
        (collateral -- inputs).foreach(in => assert(now.contains(in), s"$in kept"))
        stored(UTxOs.txouts[Conway, TxLevel.Top](body)).foreach((in, o) =>
          assertEquals(now.get(in), Some(o), s"$in")
        )
      case IsPhase2Valid.Phase2Invalid =>
        (inputs -- collateral).foreach(in => assert(now.contains(in), s"$in kept"))
        collateral.foreach(in => assert(!now.contains(in), s"$in spent"))
        UTxOs
          .txouts[Conway, TxLevel.Top](body)
          .unUTxO
          .keys
          .foreach(in => assert(!now.contains(in), s"$in not created"))
        stored(BabbageUtxo.collOuts[Conway](body)).foreach((in, o) =>
          assertEquals(now.get(in), Some(o), s"$in")
        )
    after

  /** The transactions submitted in turn: the state after each. */
  private def submitted(
      state: LedgerState[Conway],
      txs: Vector[Built],
      params: PParams[Conway]
  ): Vector[LedgerState[Conway]] =
    txs.scanLeft(state)(step(_, _, params)).drop(1)

  /** The UTxO set of `state` in the era's types. */
  private def utxoOf(state: LedgerState[Conway]): UTxO[Conway] =
    ok(ConwayUTxO.fromStored(stateUtxo(state)))

  test("the control: BBODY admits a block of one transaction as the Imp makes it") {
    versions.foreach { v =>
      val in1 = B.fresh(0x01)
      val state = holding(v, outputs(in1 -> paid(10000000)))
      val raw = blockOf(v, Vector(basic(in1)))
      checked(s"control at $v", raw, state, pp(v)).serial match
        case Right(Right(_)) => ()
        case other           => fail(s"$v: not admitted: $other")
    }
  }

  // ---- Shelley's: `Shelley/Imp/BbodySpec.hs`, cardano-ledger 9df9f98c ---------------------------------

  versions.foreach { v =>
    test(s"version $v: WrongBlockBodySizeBBODY (Shelley/Imp/BbodySpec.hs:28, 9df9f98c)") {
      // `blockBodySizeBlockHeaderL %~ (+ 1)`: supplied the body's size, expected the header's; the
      // transaction `submitAnyTx_ $ mkBasicTx mkBasicTxBody`, at the top level, the one Conway has
      val in1 = B.fresh(0x01)
      val state = holding(v, outputs(in1 -> paid(10000000)))
      val t = basic(in1)
      val _ = submitted(state, Vector(t), pp(v))
      val size = EraBlockBody.conway.blockBodySize(
        EraPParams.conway.ppProtocolVersionL.get(pp(v)),
        decoded(blockOf(v, Vector(t))).blockBody
      )
      val raw = blockOf(v, Vector(t), _.updated(6, encoded(Value.UInt(BigInt(size) + 1))))
      assertEquals(
        refused(s"WrongBlockBodySizeBBODY at $v", raw, state, pp(v)),
        Vector(
          into(
            new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](Mismatch(size, size + 1))
          )
        )
      )
    }

    test(s"version $v: InvalidBodyHashBBODY (Shelley/Imp/BbodySpec.hs:43, 9df9f98c)") {
      // `blockBodyHashBlockHeaderL .~ invalidBodyHash` of an `arbitrary` hash, fixed: supplied the
      // body's hash, expected the header's
      val in1 = B.fresh(0x01)
      val state = holding(v, outputs(in1 -> paid(10000000)))
      val t = basic(in1)
      val _ = submitted(state, Vector(t), pp(v))
      val invalid = ok(Hash32.fromBytes(Bytes.fromArray(Array.fill(32)(9.toByte))))
      val raw = blockOf(v, Vector(t), _.updated(7, encoded(Value.ByteString(invalid.bytes))))
      assertEquals(
        refused(s"InvalidBodyHashBBODY at $v", raw, state, pp(v)),
        Vector(
          into(
            new ShelleyBbodyPredFailure.InvalidBodyHashBBODY[Conway](
              Mismatch(EraBlockBody.conway.hashBlockBody(decoded(raw).blockBody), invalid)
            )
          )
        )
      )
    }

    test(s"version $v: LedgersFailure (Shelley/Imp/BbodySpec.hs:58, 9df9f98c)") {
      // `modifyBlockBody protVer $ txSeqBlockBodyL %~ \txs -> txs <> txs`, the header committing to
      // the body: among the failures (`withTxsInModifiedFailingSubsetBlockM`), the block's inputs as
      // bad
      val in1 = B.fresh(0x01)
      val state = holding(v, outputs(in1 -> paid(10000000)))
      val once = basic(in1)
      val _ = submitted(state, Vector(once), pp(v))
      val raw = blockOf(v, Vector(once, once))
      val badInputs = NonEmptySet
        .fromSet(
          SortedSet.from(
            EraBlockBody.conway
              .txSeqBlockBody(decoded(raw).blockBody)
              .flatMap(t => bodies.inputs[TxLevel.Top](tx.body[TxLevel.Top](t)))
          )
        )
        .getOrElse(fail("no input"))
      val expected = Set(
        into[ConwayUtxoPredFailure[Conway]](ConwayUtxoPredFailure.BadInputsUTxO(badInputs))
      )
      val failures = refused(s"LedgersFailure at $v", raw, state, pp(v)).toSet
      assertEquals(failures.intersect(expected), expected)
    }
  }

  // ---- Alonzo's: `Alonzo/Imp/BbodySpec.hs`, for each language -----------------------------------------

  /** The scripts of `Examples.hs` the cases use. */
  private enum Example:
    case AlwaysSucceedsWithDatum, AlwaysFailsWithDatum, AlwaysSucceedsNoDatum, AlwaysFailsNoDatum,
      EvenRedeemerNoDatum, PurposeIsWellformedNoDatum

  /** A script of `libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs` at
    * f649f975: the line of its binary, the binary, and the hash its comment lists.
    */
  private final case class Binary(line: Int, script: Bytes, hash: Bytes)

  private val examples: Map[(Example, PlutusLanguage), Binary] = Map(
    (Example.AlwaysSucceedsNoDatum, PlutusLanguage.V1) -> Binary(
      49,
      hex(
        "582d01000033333222222253330053370e900118039baa30033006300437540022c224002aae755d12b9a5" +
          "573cae85"
      ),
      hex("6bd534d263a1213113b775e4e8386e47e6181a33e40ab3ea623b5fe8")
    ),
    (Example.AlwaysSucceedsNoDatum, PlutusLanguage.V2) -> Binary(
      65,
      hex(
        "582d01000033333222222253330053370e900118039baa30033006300437540022c224002aae755d12b9a5" +
          "573cae85"
      ),
      hex("a98c0f498abacf6dea126d707b1ba5cc27e523c20929ec0ac705087f")
    ),
    (Example.AlwaysSucceedsNoDatum, PlutusLanguage.V3) -> Binary(
      82,
      hex(
        "588f0101009800aab9daba2ab9aaab9eaba1ab9c488888896600264b30013370e90011804000c4c8c8ca4d" +
          "6600266e1d2000002894004c02000515980099b874800800a250028b2012402400324a14a2300700230090" +
          "0137546008600e600a00314a28030dd5191919191803980500198030011802801180380098021baa0018a4" +
          "d13263300249010350543500800200a1"
      ),
      hex("b1d5bc8ced627156f403786ad7c281dcc510735957aa364fb9376d85")
    ),
    (Example.AlwaysSucceedsWithDatum, PlutusLanguage.V1) -> Binary(
      124,
      hex(
        "5833010000333332222222253330063370e900118041baa30043007300537540022240022646a002002caa" +
          "e755d12b9a5573cae841"
      ),
      hex("8b4702c463ab040a4a40c07f6509113e4627777bdd758d28e1a0d038")
    ),
    (Example.AlwaysSucceedsWithDatum, PlutusLanguage.V2) -> Binary(
      140,
      hex(
        "5833010000333332222222253330063370e900118041baa30043007300537540022240022646a002002caa" +
          "e755d12b9a5573cae841"
      ),
      hex("39cbd07a196c241b6cb2f9b74748021cfbf4d5c972371b770961635f")
    ),
    (Example.AlwaysSucceedsWithDatum, PlutusLanguage.V3) -> Binary(
      157,
      hex(
        "588f0101009800aab9daba2ab9aaab9eaba1ab9c488888896600264b30013370e90011804000c4c8c8ca4d" +
          "6600266e1d2000002894004c02000515980099b874800800a250028b2012402400324a34a0300700230090" +
          "0137546008600e600a00314a08030dd5191919191803980500198030011802801180380098021baa0018a4" +
          "d13263300249010350543500800200a1"
      ),
      hex("96aebdd3eccef1bab31331a0248f2e7352ef54c541b5a13db5a4a0cf")
    ),
    (Example.AlwaysFailsNoDatum, PlutusLanguage.V1) -> Binary(
      201,
      hex(
        "582d01000033333222222253330053370e900118039baa30033006300437540022240022caae755d12b9a5" +
          "573cae85"
      ),
      hex("f9a6538bd9f37acb481ad68164b230ca1120ffe9e80c0c43dbcfc809")
    ),
    (Example.AlwaysFailsNoDatum, PlutusLanguage.V2) -> Binary(
      219,
      hex(
        "582d01000033333222222253330053370e900118039baa30033006300437540022240022caae755d12b9a5" +
          "573cae85"
      ),
      hex("b59fa1404727374ea47698b98af590b5914fdcc0270c3f7efc16b229")
    ),
    (Example.AlwaysFailsNoDatum, PlutusLanguage.V3) -> Binary(
      237,
      hex(
        "587e0101009800aab9daba2ab9aaab9eaba1ab9c488888896600264b30013370e90011804000c4c8ca4d66" +
          "00266e1d2000001894004c01c00515980099b8748008006250028b20104020600e00524a34a03009001375" +
          "46008600e600a00314a08030dd518019803180318021baa0018a4d1326330024910350543500800200a1"
      ),
      hex("c319009e5f76b963bfff7d37fa437135ba6ccb342f3b482965c4ccb2")
    ),
    (Example.AlwaysFailsWithDatum, PlutusLanguage.V1) -> Binary(
      283,
      hex(
        "582e010000333332222222253330063370e900118041baa30043007300537540022c224002aae755d12b9a" +
          "5573cae841"
      ),
      hex("6c8f5c35d35af805fba76f8f2f45d1b1d50e5c9d5f84017290391fef")
    ),
    (Example.AlwaysFailsWithDatum, PlutusLanguage.V2) -> Binary(
      304,
      hex(
        "582e010000333332222222253330063370e900118041baa30043007300537540022c224002aae755d12b9a" +
          "5573cae841"
      ),
      hex("11d7df2781d1aeabd368404485d865cc4119037762e645e64e5c1fbe")
    ),
    (Example.AlwaysFailsWithDatum, PlutusLanguage.V3) -> Binary(
      322,
      hex(
        "587e0101009800aab9daba2ab9aaab9eaba1ab9c488888896600264b30013370e90011804000c4c8ca4d66" +
          "00266e1d2000001894004c01c00515980099b8748008006250028b20104020600e00524a14a23009001375" +
          "46008600e600a00314a28030dd518019803180318021baa0018a4d1326330024910350543500800200a1"
      ),
      hex("f4d4c7784c77c4088eecc6555e6192897571ca00c455d18f51f993cf")
    ),
    (Example.EvenRedeemerNoDatum, PlutusLanguage.V1) -> Binary(
      516,
      hex(
        "583f01000033333222222253330053370e900118039baa30033006300437540022c2a66600a66e1d200033" +
          "70c6eb40092004112001165573aae895cd2ab9e57421"
      ),
      hex("5f7ede70b1b9ac720d0a63fddc39dc727a706f53fe5898a68afe254d")
    ),
    (Example.EvenRedeemerNoDatum, PlutusLanguage.V2) -> Binary(
      534,
      hex(
        "583f01000033333222222253330053370e900118039baa30033006300437540022c2a66600a66e1d200033" +
          "70c6eb40092004112001165573aae895cd2ab9e57421"
      ),
      hex("ddeba040ae64532a549a3ab42ebecf38dc6738590ba828130bd855ab")
    ),
    (Example.EvenRedeemerNoDatum, PlutusLanguage.V3) -> Binary(
      551,
      hex(
        "58950101009800aab9daba2ab9aaab9eaba1ab9c4888888966002646464b30013370e9001180500144c8ca" +
          "4d6600266e1d2000001894004c02400515980099b8748008006250028b20144028601200524a1300349860" +
          "160026ea8c018c024c01c00a2600293201023370e900019b86375a600c00690021baa30043007001300630" +
          "043754003149a264c6600492010350543500800200a1"
      ),
      hex("b52db3a5ea3dddeee36249fdbb1dbf9c91e987a7e7b364c0e08cf817")
    ),
    (Example.PurposeIsWellformedNoDatum, PlutusLanguage.V1) -> Binary(
      678,
      hex(
        "5901fc0100003333333322222222223232323232323232533300e3370e90000008a9998071919800800999" +
          "80280411111111110039311980225023330103375e6ea4dd718071807804180a0009251233003003001100" +
          "2161533300e3370e90010008b099299980799b874801000854ccc03cc048cccc0048cc01cdd5000911a999" +
          "80919b874800000848c8c848cc00400c008dd69809180a80118088008b00091a8009119ba548000cc05000" +
          "8cc050dd4000a5eb808cdd7980718078041a800910011998028041111111111002930b08018a99980799b8" +
          "74801800854ccc03cc048cccc004800480048cdd7980718078040009998028041111111111003130b08018" +
          "991a800800b11119118010009918008009119980425eb8088c94ccc058c0180044c8cc064c020008004cc0" +
          "140140084cc014014008c01c008004c044014480048894ccc02c004400c4cc008c030004c03c004888c8c8" +
          "c8c8c8c8c8c8c8cccccccccc02cdd6180a0049bac30140083756602800e6eacc050018dd6180a0029bac30" +
          "140043014003375860280046eb0c050004cc034dd5180a180b800911a99980c19b874800000848dd7180b8" +
          "008b000980b000980a800980a0009809800980900098088009808000980780098061baa0032233001300f0" +
          "02300a0023754600c6012004600a002600a6ea800555ceaba35740ae895cd2ab9e5742aae7c1"
      ),
      hex("310918097581287d2c2a519462e4be9a0e50f758f4af7edba5ccf105")
    ),
    (Example.PurposeIsWellformedNoDatum, PlutusLanguage.V2) -> Binary(
      712,
      hex(
        "5901b50100003333333322222222223232323232323232533300e3370e90000008a999807199801ba9375c" +
          "6018601a00c66600a00e4444444444440104c20042c2a66601c66e1d2002001161533300e3370e90020008" +
          "a9998071998019000980618068031998028039111111111110031308010b099299980799b874801800854c" +
          "cc03cc048c004ccc01802088888888888801c9858400c4c8d40040058c8c00400488ccc0192f5c044a6660" +
          "2466ebcc040c0440280084cc050008cc0100100044cc010010004004c04401448004888c8cc0040040088c" +
          "c01528119980819baf3005004301400124a2466006006002444a66601400220062660046016002601c0024" +
          "44646464646464646464646466666666666601a6eb0c05402cdd6180a8051bac30150093756602a0106eac" +
          "c05401cdd6180a8031bab301500530150043758602a0066eacc054008dd5980a800991a99980c19b874800" +
          "0c07000448dd7180b8008b180b8009baa30153018001301700130160013015001301400130130013012001" +
          "30110013010001300f001300e001300b37540066ea8c018c024008c014004c014dd5000aab9d5746ae815d" +
          "12b9a5573cae8555cf81"
      ),
      hex("316aa8a6e1a55f18d96458559eb9ce4330fb9b3066cd8fdaef3d1742")
    ),
    (Example.PurposeIsWellformedNoDatum, PlutusLanguage.V3) -> Binary(
      749,
      hex(
        "5902420101009800aab9daba3aba0aba2ab9aaab9eaba1ab9caab9f4888888888966002646464646464646" +
          "464b30013370e9000000c66002ea66eb8c034c038012653001237560032374c00323259800980a800c6005" +
          "180080090124c005d6c8dd4000c96600266e2120000018c00400518012024800a0049bab300e0084004444" +
          "64600200246600e97adef6c6029300330053013001911980c19bb0301a0033006002330040040019198018" +
          "01800900244c96600266e1d2002002899191949acc004cdc3a4000003128009809000a2b30013370e90010" +
          "00c4a00516404c8098c04800a4942602d300120019000c8cdd79809002180918099baa0019bac301100c40" +
          "1030160013754601e6024002601e00b15980099b874801000a33001200198071807802cdd5980700320068" +
          "acc004cdc3a400c005132301498009000c8006466ebcc040c04c0080066eb0c03c0210021807802c566002" +
          "66e1d20080028cc0048006601c601e00b3756601c60226022602260226022602200c801a2b30013370e900" +
          "500144c9289807802c4ca40060026404080810102020404044464460040026460020024530014bd70488c9" +
          "66002600c0031323301a300800200133005005002899802802801202e3007002800a010403c60240064446" +
          "46600200200446600a940a600266ebcc014010c05800649464660060060028090889660020031003899801" +
          "18068009808000a016375460126018601800a601600260140026012601260126012002600c6ea8c014004c" +
          "014dd5000c526899319801a4810350543500800200c1"
      ),
      hex("d23d266927a22839c801aadf6f9e96a5b6f02d65e68dcedbad432c91")
    )
  )

  private def example(e: Example, lang: PlutusLanguage): Binary =
    examples.getOrElse((e, lang), fail(s"no $e for $lang"))

  /** Conway's languages (`eraLanguages`), with each one's tag and witness set key. */
  private val languages: Vector[(PlutusLanguage, Int, Int)] =
    Vector((PlutusLanguage.V1, 1, 3), (PlutusLanguage.V2, 2, 6), (PlutusLanguage.V3, 3, 7))

  test("the reference's example scripts hash as their comments list") {
    examples.foreach { case ((e, lang), b) =>
      val tag = languages.find(_._1 == lang).map(_._2).getOrElse(fail(s"$lang"))
      assertEquals(B.scriptHash(tag, b.script), b.hash, s"$e $lang (Examples.hs:${b.line})")
    }
    assertEquals(example(Example.PurposeIsWellformedNoDatum, PlutusLanguage.V2).script.size, 440)
    assertEquals(examples.size, 3 * Example.values.size)
  }

  languages.foreach { (lang, _, witnessKey) =>
    val succeeds = example(Example.AlwaysSucceedsWithDatum, lang)
    versions.foreach { v =>
      test(s"version $v, $lang: enforces ppMaxBlockExUnits (Alonzo/Imp/BbodySpec.hs:91)") {
        // `txCount = 1 + max (bMem `div` tMem) (bSteps `div` tSteps)` transactions, each spending an
        // output of the script with the datum 0 (`PlutusArgs (P.I 0) (Just $ P.I 0)`,
        // `Alonzo/ImpTest.hs:400`) and the redeemer `(Data (P.I 0), maxTxUnits)`
        val params = pp(v)
        val maxBlockUnits = AlonzoEraPParams.ppMaxBlockExUnitsL.get(params).units
        val maxTxUnits = AlonzoEraPParams.ppMaxTxExUnitsL.get(params).units
        val txCount =
          1 + (maxBlockUnits.mem / maxTxUnits.mem).max(maxBlockUnits.steps / maxTxUnits.steps)
        assertEquals(txCount, BigInt(21))
        val mismatch = Mismatch[Relation.RelLTEQ, ExUnits](
          ExUnits(txCount * maxTxUnits.mem, txCount * maxTxUnits.steps),
          maxBlockUnits
        )
        val datum = B.uint(0)
        val redeemers = B.cmap(
          B.arr(B.uint(0), B.uint(0)) ->
            B.arr(B.uint(0), B.arr(B.uint(maxTxUnits.mem), B.uint(maxTxUnits.steps)))
        )
        // each spend as `submitTx_` leaves it, after the transaction its fixups submit while the
        // block records (`addCollateralInput`, `Alonzo/ImpTest.hs:151-164`;
        // `withTxsInFailingBlock`, `Shelley/ImpTest.hs:1429-1453`): the collateral's producer
        // (`collateralOf`), then the spend, the script's output first among its inputs (the
        // redeemer's index 0, as `fixupRedeemerIndices` leaves it), the fee's input, the producer's
        // 30 ada as its collateral, the change, the datum, the script and the integrity hash. The
        // state before the block holds the scripts' outputs (`produceScript`, before it) and the
        // inputs that fund the pairs, not the collateral
        val pairs = (0 until txCount.toInt).toVector.map { i =>
          val (scriptIn, feeIn, fund) = (B.fresh(0x10 + i), B.fresh(0x40 + i), B.fresh(0x70 + i))
          val producer = collateralOf(fund)
          def txOf(integrity: Bytes) =
            val body = B.body(
              0 -> B.set(scriptIn.node, feeIn.node),
              1 -> B.arr(paid(12000000)),
              2 -> B.uint(3000000),
              11 -> B.bytes(integrity),
              13 -> B.set(outOf(producer).node)
            )
            built(
              body,
              B.witnesses(
                body,
                Vector(payer),
                4 -> B.set(datum),
                5 -> redeemers,
                witnessKey -> B.set(B.bytes(succeeds.script))
              )
            )
          val integrity = ScriptIntegrity
            .mkScriptIntegrity[Conway, TxLevel.Top](
              params,
              txOf(Bytes(Vector.fill(32)(0.toByte))).era,
              Set(lang)
            )
            .map(ScriptIntegrity.hashScriptIntegrity(_))
            .getOrElse(fail("no script integrity"))
          val external = Vector(
            scriptIn -> B.datumHashOut(B.scriptAddress(succeeds.hash), 5000000, B.dataHash(datum)),
            feeIn -> paid(10000000),
            fund -> paid(40000000)
          )
          (Vector(producer, txOf(integrity)), external)
        }
        val txs = pairs.flatMap(_._1)
        val state = holding(v, outputs(pairs.flatMap(_._2)*))
        assertEquals(txs.size, 2 * txCount.toInt)
        // the exact refusal is BBODY's alone: LEDGERS admitted each transaction, as the Imp's
        // `submitTx_` did
        val raw = blockOf(v, txs)
        assertEquals(
          refused(s"ppMaxBlockExUnits at $v, $lang", raw, state, params),
          Vector(into(new AlonzoBbodyPredFailure.TooManyExUnits[Conway](mismatch)))
        )
        // past the block's units the node's run is never prepared (#85): not worthwhile, and its
        // plan has no job; at every worker count it evaluates exactly the scripts the serial run
        // evaluates
        val body = program(raw, state, params)(ScarabEvaluator.default, None)
        assert(!body.worthwhile)
        assertEquals(body.prepare.jobs, Vector.empty)
        val serialRuns = CountingEvaluator()
        val _ = program(raw, state, params)(serialRuns, None).serial
        assertEquals(serialRuns.count, txCount.toInt)
        ConwayPlanned.workers.foreach { n =>
          val runs = CountingEvaluator()
          val _ = runner(program(raw, state, params)(runs, None), n)
          assertEquals(runs.count, serialRuns.count, s"$n workers")
        }
      }
    }
  }

  /** An evaluator that counts the scripts it is asked to run. */
  private final class CountingEvaluator extends xyz.kd5ujc.scarab.plutus.ScriptEvaluator[Id]:
    private val runs = java.util.concurrent.atomic.AtomicInteger(0)
    def evaluate(request: xyz.kd5ujc.scarab.plutus.EvalRequest): EvalResult =
      val _ = runs.incrementAndGet()
      ScarabEvaluator.default.evaluate(request)
    def count: Int = runs.get

  /** The scripts of `t` with their contexts on `utxo` (`plutusScriptsWithContextStAnnTx`), each run
    * with the units its redeemer declares: what `evalTxExUnits` and `evalPlutusScriptsWithLogs` run
    * (`fixupRedeemers` and `impScriptPredicateFailure`, `Alonzo/ImpTest.hs:203-237, 490-507`).
    */
  private def evaluated(
      params: PParams[Conway],
      utxo: UTxO[Conway],
      t: Built
  ): Vector[(PlutusWithContext, EvalResult)] =
    val stAnnTx =
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](slotToPosix, params, utxo, Map.empty, t.era)
    EraUTxO.conway
      .plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)(using EraUTxO.conway.txOf.bodyOf.top)
      .fold(e => fail(s"$e"), _.toVector)
      .map(pwc => pwc -> ScarabEvaluator.default.evaluate(PlutusWithContext.evalRequest(pwc)))

  /** The redeemer of a transaction's one Plutus purpose `key` and its integrity hash as the fixups
    * leave them (`txWithMaxRedeemers`, `fixupRedeemers`, `fixupPPHash`, `Alonzo/ImpTest.hs:203-260,
    * 318-327`): the redeemer the case gives (`explicit`), kept; else the script's redeemer data
    * with the units the script spends when its redeemer declares the parameters' most units a
    * transaction may use, or those most where it fails; then the integrity hash of `lang` over the
    * parameters. `make` builds the transaction of a redeemer map and a hash.
    */
  private def fixedUp(
      params: PParams[Conway],
      utxo: UTxO[Conway],
      lang: PlutusLanguage,
      key: Node,
      data: Node,
      explicit: Option[(Node, ExUnits)],
      make: (Node, Option[Bytes]) => Built
  ): Built =
    val maxTx = AlonzoEraPParams.ppMaxTxExUnitsL.get(params).units
    def redeemers(d: Node, u: ExUnits) =
      B.cmap(key -> B.arr(d, B.arr(B.uint(u.mem), B.uint(u.steps))))
    val estimated = evaluated(params, utxo, make(redeemers(data, maxTx), None)) match
      case Vector((_, EvalResult.Success(used, _))) => Some(used)
      case Vector((_, _: EvalResult.Failure))       => None
      case other                                    => fail(s"not one script: $other")
    val (d, u) = explicit.getOrElse(data -> estimated.getOrElse(maxTx))
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Conway, TxLevel.Top](params, make(redeemers(d, u), None).era, Set(lang))
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    make(redeemers(d, u), Some(integrity))

  /** `impScriptPredicateFailure` (`Alonzo/ImpTest.hs:490-507`) injected into LEDGER
    * (`submitPhase2Invalid`, `:517-532`): the transaction flagged valid refused for the scripts
    * that fail on `utxo`, each described as `scriptFailureToFailureDescription` gives it, through
    * Conway's UTXOS, UTXO, UTXOW and LEDGER.
    */
  private def scriptPredicateFailure(
      params: PParams[Conway],
      utxo: UTxO[Conway],
      t: Built
  ): LedgerPredFailure[Conway] =
    val failures = NonEmptyList
      .fromList(evaluated(params, utxo, t).toList.collect {
        case (pwc, EvalResult.Failure(reason, _, _)) =>
          FailureDescription.scriptFailureToFailureDescription(ScriptFailure(reason, pwc))
      })
      .getOrElse(fail("no script failed"))
    ConwayLedgerPredFailure.ConwayUtxowFailure(
      ConwayUtxowPredFailure.UtxoFailure(
        ConwayUtxoPredFailure.UtxosFailure(
          ConwayUtxosPredFailure.ValidationTagMismatch[Conway](
            IsPhase2Valid.Phase2Valid,
            TagMismatchDescription.FailedUnexpectedly(failures)
          )
        )
      )
    )

  /** A refusal of UTXOS's for failing scripts, comparable: the flag, and each failure by its
    * machine's error and the script's trace and by the request it ran.
    */
  private def comparable(f: LedgerPredFailure[Conway]) = f match
    case ConwayLedgerPredFailure.ConwayUtxowFailure(
          ConwayUtxowPredFailure.UtxoFailure(
            ConwayUtxoPredFailure.UtxosFailure(
              ConwayUtxosPredFailure.ValidationTagMismatch(
                flag,
                TagMismatchDescription.FailedUnexpectedly(fs)
              )
            )
          )
        ) =>
      flag -> fs.toList.map { case FailureDescription.PlutusFailure(failure, pwc) =>
        val cause = failure match
          case EvalFailure.Script(
                xyz.kd5ujc.scarab.plutus.ScriptFailure.Uplc(EvaluationError.Cek(cek), logs)
              ) =>
            Right(cek.error -> logs)
          case other => Left(other)
        cause -> PlutusWithContext.evalRequest(pwc)
      }
    case other => fail(s"not a refusal of failing scripts: $other")

  /** `submitPhase2Invalid` (`Alonzo/ImpTest.hs:517-532`) of `flagged`: first refused flagged valid
    * with exactly its scripts' failure, then submitted with only its flag set invalid.
    */
  private def phase2Invalid(
      state: LedgerState[Conway],
      flagged: Built,
      params: PParams[Conway]
  ): Built =
    assert(flagged.valid)
    val refusal = ledgerOn(flagged, state, params) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"not refused: $other")
    assertEquals(
      refusal.map(comparable),
      Vector(comparable(scriptPredicateFailure(params, utxoOf(state), flagged)))
    )
    flagged.copy(valid = false)

  /** The index of `target` among `inputs` as a body orders them, a spending redeemer's. */
  private def spendingIx(target: B.In, inputs: Vector[B.In]): Int =
    def key(i: B.In) = TxIn(i.txId, i.ix)
    SortedSet.from(inputs.map(key)).toVector.indexOf(key(target))

  languages.foreach { (lang, _, witnessKey) =>
    versions.foreach { v =>
      test(s"version $v, $lang: succeeds with eight Plutus scripts (Alonzo/Imp/BbodySpec.hs:46)") {
        val params = pp(v)
        val maxTx = AlonzoEraPParams.ppMaxTxExUnitsL.get(params).units
        val deposit = EraPParams.ppKeyDepositCompactL[Conway].get(params).toBigInt
        val even = example(Example.EvenRedeemerNoDatum, lang)
        val failsWithDatum = example(Example.AlwaysFailsWithDatum, lang)
        val succeedsWithDatum = example(Example.AlwaysSucceedsWithDatum, lang)
        val failsNoDatum = example(Example.AlwaysFailsNoDatum, lang)
        val succeedsNoDatum = example(Example.AlwaysSucceedsNoDatum, lang)
        // `ScriptHashObj evenRedeemerNoDatumHash`, and its reward account on the testnet
        val credential = B.arr(B.uint(1), B.bytes(even.hash))
        val accountAddress = Bytes(0xf0.toByte +: even.hash.value)
        // `PlutusArgs`' redeemers (`plutusTestScripts`, `Alonzo/ImpTest.hs:391-417`): 0 for the
        // always succeeding and failing scripts, 2 for `evenRedeemerNoDatum`; the datum 0
        val (zero, one, two) = (B.uint(0), B.uint(1), B.uint(2))
        val datum = zero

        // `produceScript` (`Shelley/ImpTest.hs:2166-2176`): an output of the script with the datum
        // hash `fixupOutputDatums` adds (`Alonzo/ImpTest.hs:329-343`), the change after it
        def produceScript(in: B.In, hash: Bytes): Built =
          val body = B.body(
            0 -> B.set(in.node),
            1 -> B.arr(
              B.datumHashOut(B.scriptAddress(hash), 5000000, B.dataHash(datum)),
              paid(3000000)
            ),
            2 -> B.uint(2000000)
          )
          built(body, B.witnesses(body, Vector(payer)))
        // a transaction of the case's `fields` as the fixups leave it: its collateral input, the
        // script (`fixupScriptWits`), the datum (`fixupDatums`), the redeemer and integrity hash
        // (`fixedUp`), signed by the payer (`updateAddrTxWits`)
        def plutus(
            state: LedgerState[Conway],
            fields: Vector[(Int, Node)],
            collateral: B.In,
            script: Bytes,
            datums: Vector[Node],
            key: Node,
            data: Node,
            explicit: Option[(Node, ExUnits)]
        ): Built =
          fixedUp(
            params,
            utxoOf(state),
            lang,
            key,
            data,
            explicit,
            (rdmrs, integrity) =>
              val body = B.body(
                (fields ++ Vector(13 -> B.set(collateral.node)) ++
                  integrity.map(h => 11 -> B.bytes(h)).toVector).sortBy(_._1)*
              )
              val more = (Option.when(datums.nonEmpty)(4 -> B.set(datums*)).toVector ++
                Vector(5 -> rdmrs, witnessKey -> B.set(B.bytes(script)))).sortBy(_._1)
              built(body, B.witnesses(body, Vector(payer), more*))
          )
        // `mkTokenMintingTx` (`Mary/ImpTest.hs:44-54`), its draws fixed: the asset name, one
        // token, and the payer's address
        val name = Bytes.fromArray("eight".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        def minting(policy: Bytes): (Int, Node) =
          9 -> B.cmap(B.bytes(policy) -> B.cmap(B.bytes(name) -> B.uint(1)))
        def minted(policy: Bytes): Node = B.arr(
          B.bytes(B.keyAddress(payer)),
          B.arr(B.uint(2000000), B.cmap(B.bytes(policy) -> B.cmap(B.bytes(name) -> B.uint(1))))
        )

        // the funds of each transaction, the Imp's root output's part (`addRootTxIn`)
        val fund = (0 until 20).map(fundOf).toVector
        val start = holding(
          v,
          outputs(
            fund.zipWithIndex.map((in, i) =>
              in -> paid(if i >= 2 && i <= 10 then 40000000 else 10000000)
            )*
          )
        )

        // before the block: `registerStakeCredential` of the script's credential
        // (`Shelley/ImpTest.hs:1915-1929`), `RegDepositTxCert` with the parameters' deposit, one
        // of the two forms `conwayGenRegTxCert` draws (`Conway/ImpTest.hs:440-452`), which the
        // script witnesses (`getScriptWitnessConwayTxCert`, `Conway/TxCert.hs:756-780`); its
        // collateral's producer before it
        val registrationCollateral = collateralOf(fund(10))
        val afterCollateral = step(start, registrationCollateral, params)
        val registration = plutus(
          afterCollateral,
          Vector(
            0 -> B.set(fund(19).node),
            1 -> B.arr(paid(BigInt(5000000) - deposit)),
            2 -> B.uint(5000000),
            4 -> B.set(B.arr(B.uint(7), credential, B.uint(deposit)))
          ),
          outOf(registrationCollateral),
          even.script,
          Vector.empty,
          B.arr(B.uint(2), B.uint(0)),
          two,
          None
        )
        val before = step(afterCollateral, registration, params)

        // the block, as `withTxsInBlock_` records it: each transaction submitted in turn, the
        // producing and collateral ones among them. A phase-2-invalid one is first refused flagged
        // valid with exactly its scripts' failure, then submitted flagged invalid without fixups
        // (`submitPhase2Invalid`); a valid one is submitted (`submitTx_`)
        val failingIn = outOf(produceScript(fund(0), failsWithDatum.hash))
        val succeedingIn = outOf(produceScript(fund(1), succeedsWithDatum.hash))
        val collaterals = (2 to 9).map(i => collateralOf(fund(i))).toVector
        def spending(in: B.In, fee: B.In, script: Bytes, c: Int)(state: LedgerState[Conway]) =
          plutus(
            state,
            Vector(0 -> B.set(in.node, fee.node), 1 -> B.arr(paid(10000000)), 2 -> B.uint(5000000)),
            outOf(collaterals(c)),
            script,
            Vector(datum),
            B.arr(B.uint(0), B.uint(spendingIx(in, Vector(in, fee)))),
            zero,
            None
          )
        def mint(policy: Bytes, script: Bytes, fee: B.In, c: Int)(state: LedgerState[Conway]) =
          plutus(
            state,
            Vector(
              0 -> B.set(fee.node),
              1 -> B.arr(minted(policy), paid(3000000)),
              2 -> B.uint(5000000),
              minting(policy)
            ),
            outOf(collaterals(c)),
            script,
            Vector.empty,
            B.arr(B.uint(1), B.uint(0)),
            zero,
            None
          )
        // `dex i = (Data $ P.I i, maxExUnits)`, at `mkWithdrawingPurpose (AsIx 0)` and
        // `mkCertifyingPurpose (AsIx 0)`
        def withdrawing(i: Node, fee: B.In, c: Int)(state: LedgerState[Conway]) =
          plutus(
            state,
            Vector(
              0 -> B.set(fee.node),
              1 -> B.arr(paid(5000000)),
              2 -> B.uint(5000000),
              5 -> B.cmap(B.bytes(accountAddress) -> B.uint(0))
            ),
            outOf(collaterals(c)),
            even.script,
            Vector.empty,
            B.arr(B.uint(3), B.uint(0)),
            two,
            Some(i -> maxTx)
          )
        // `genUnRegTxCert`: `UnRegDepositTxCert` with the account's deposit, one of the two forms
        // `conwayGenUnRegTxCert` draws (`Conway/ImpTest.hs:423-438`), refunded to the change
        def certifying(i: Node, fee: B.In, c: Int)(state: LedgerState[Conway]) =
          plutus(
            state,
            Vector(
              0 -> B.set(fee.node),
              1 -> B.arr(paid(BigInt(5000000) + deposit)),
              2 -> B.uint(5000000),
              4 -> B.set(B.arr(B.uint(8), credential, B.uint(deposit)))
            ),
            outOf(collaterals(c)),
            even.script,
            Vector.empty,
            B.arr(B.uint(2), B.uint(0)),
            two,
            Some(i -> maxTx)
          )
        enum Submit:
          case Fixed(t: Built)
          case Phase2Invalid(make: LedgerState[Conway] => Built)
          case Valid(make: LedgerState[Conway] => Built)
        // "notValidatingTx" and "validatingTx" (`Alonzo/Imp/BbodySpec.hs:51-56`)
        val spends = Vector(
          Submit.Fixed(produceScript(fund(0), failsWithDatum.hash)),
          Submit.Fixed(collaterals(0)),
          Submit.Phase2Invalid(spending(failingIn, fund(11), failsWithDatum.script, 0)),
          Submit.Fixed(produceScript(fund(1), succeedsWithDatum.hash)),
          Submit.Fixed(collaterals(1)),
          Submit.Valid(spending(succeedingIn, fund(12), succeedsWithDatum.script, 1))
        )
        // "notValidatingTxWithMint" and "validatingTxWithMint" (`:58-61`)
        val mints = Vector(
          Submit.Fixed(collaterals(2)),
          Submit.Phase2Invalid(mint(failsNoDatum.hash, failsNoDatum.script, fund(13), 2)),
          Submit.Fixed(collaterals(3)),
          Submit.Valid(mint(succeedsNoDatum.hash, succeedsNoDatum.script, fund(14), 3))
        )
        // "notValidatingTxWithWithdrawal" and "validatingTxWithWithdrawal" (`:69-78`)
        val withdrawals = Vector(
          Submit.Fixed(collaterals(4)),
          Submit.Phase2Invalid(withdrawing(one, fund(15), 4)),
          Submit.Fixed(collaterals(5)),
          Submit.Valid(withdrawing(zero, fund(16), 5))
        )
        // "notValidatingTxWithCert" and "validatingTxWithCert" (`:80-89`)
        val certs = Vector(
          Submit.Fixed(collaterals(6)),
          Submit.Phase2Invalid(certifying(one, fund(17), 6)),
          Submit.Fixed(collaterals(7)),
          Submit.Valid(certifying(zero, fund(18), 7))
        )
        val (_, txs) = (spends ++ mints ++ withdrawals ++ certs)
          .foldLeft((before, Vector.empty[Built])) { case ((state, done), submit) =>
            val t = submit match
              case Submit.Fixed(t)            => t
              case Submit.Valid(make)         => make(state)
              case Submit.Phase2Invalid(make) => phase2Invalid(state, make(state), params)
            (step(state, t, params), done :+ t)
          }
        assertEquals(txs.size, 18)
        assertEquals(txs.count(!_.valid), 4)
        // the block of the eighteen, on the state after the registration, admitted by the node's
        // run, serially and prepared: its eight script transactions are the plan's eight jobs, each
        // landed with the verdict its flag announces, to the serial run's result
        val c = checked(s"eight Plutus scripts at $v, $lang", blockOf(v, txs), before, params)
        c.serial match
          case Right(Right(_)) => ()
          case other           => fail(s"not admitted: $other")
        assert(c.worthwhile)
        assertEquals(c.jobs, 8)
        // observed for the node's phase-2 observer, each script transaction in the block's order,
        // its one script passed where it is flagged valid and failed where flagged invalid
        assertEquals(
          c.observations.map(_.tx.body.id),
          txs
            .filter(t => tx.witsOf.rdmrsTxWits(tx.wits[TxLevel.Top](t.era)).entries.nonEmpty)
            .map(_.id)
        )
        c.observations.foreach(o =>
          o.outcome match
            case Right(flat.Phase2.Outcome.Passed(report)) =>
              assert(o.tx.isValid, s"$o")
              assertEquals(report.scripts, 1)
            case Right(flat.Phase2.Outcome.Failed(report, _)) =>
              assert(!o.tx.isValid, s"$o")
              assertEquals(report.scripts, 1)
            case other => fail(s"not one script ran: $other")
        )
      }
    }
  }

  // ---- Conway's: `Conway/Imp/BbodySpec.hs` ----------------------------------------------------------

  private lazy val purposeIsWellformedNoDatumV2: Binary =
    example(Example.PurposeIsWellformedNoDatum, PlutusLanguage.V2)

  /** `genNumAdditionsExceeding sc maxSingle toExceed` (`Conway/Imp/BbodySpec.hs:244-252`) with the
    * largest value its generator allows drawn each time: counts whose multiples of `sc` sum past
    * `toExceed`, each multiple at most `maxSingle`.
    */
  private def genNumAdditionsExceeding(sc: Long, maxSingle: Long, toExceed: Long): Vector[Long] =
    val x = math.min(toExceed / sc, maxSingle / sc)
    Vector.unfold(0L)(tot => Option.when(tot <= toExceed)((x, tot + x * sc)))

  private val maxScriptsPerTx = 100L

  /** The counts of `:53-64` and `:92-101` for the V2 script, the script's size and the block's
    * limit.
    */
  private def scriptCounts(params: PParams[Conway]): (Long, Long, Vector[Long]) =
    val scriptSize = purposeIsWellformedNoDatumV2.script.size.toLong
    val p = ConwayEraPParams.conway
    val maxRefScriptSizePerTx = p.ppMaxRefScriptSizePerTxG.get(params).value.toLong
    val maxRefScriptSizePerBlock = p.ppMaxRefScriptSizePerBlockG.get(params).value.toLong
    val maxSingle = math.min(maxRefScriptSizePerTx, maxScriptsPerTx * scriptSize)
    val counts = genNumAdditionsExceeding(scriptSize, maxSingle, maxRefScriptSizePerBlock)
    // the generator's bounds
    assert(counts.forall(n => n >= 1 && n * scriptSize <= maxSingle), s"$counts")
    assert(counts.sum * scriptSize > maxRefScriptSizePerBlock)
    assert(counts.init.sum * scriptSize <= maxRefScriptSizePerBlock)
    // at Conway's limits (204,800 a transaction, 1,048,576 a block) and the script's 440 bytes: 24
    // transactions of 100 references each, 1,056,000 bytes
    assertEquals(counts, Vector.fill(24)(100L))
    (scriptSize, maxRefScriptSizePerBlock, counts)

  /** A transaction referencing `refIns` (`mkTxWithRefInputs`, `Babbage/ImpTest.hs`) as the fixups
    * leave it: spending `ins`, of `coin` in all, a fee of 3 ada and the change to the payer.
    */
  private def referencing(ins: Vector[B.In], coin: BigInt, refIns: Vector[B.In]): Built =
    val body = B.body(
      0 -> B.set(ins.map(_.node)*),
      1 -> B.arr(paid(coin - 3000000)),
      2 -> B.uint(3000000),
      18 -> B.set(refIns.map(_.node)*)
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** `produceRefScript` 's transaction (`Babbage/ImpTest.hs`): an output holding the V2 script,
    * funded from `in`, the change to the payer.
    */
  private def producing(in: B.In): Built =
    val body = B.body(
      0 -> B.set(in.node),
      1 -> B.arr(
        refOut(5000000, 2, B.bytes(purposeIsWellformedNoDatumV2.script)),
        paid(3000000)
      ),
      2 -> B.uint(2000000)
    )
    built(body, B.witnesses(body, Vector(payer)))

  versions.foreach { v =>
    test(s"version $v: BodyRefScriptsSizeTooBig (Conway/Imp/BbodySpec.hs:43)") {
      // the reference scripts produced before the block, each transaction of the block spending the
      // 100 ada sent to it before the block (`sendCoinTo addr (Coin 100_000_000)`) and, for the root
      // input `addRootTxIn` adds, an input of its own, and referencing as many of the scripts as its
      // count; the exact refusal is BBODY's alone, LEDGERS having admitted each, as `submitTx_` did
      val params = pp(v)
      val (scriptSize, maxRefScriptSizePerBlock, counts) = scriptCounts(params)
      val refIns = counts.indices.toVector.map(i =>
        (0 until counts(i).toInt).toVector.map(j => fundOf(1000 * (i + 1) + j))
      )
      val txs = counts.indices.toVector.map(i =>
        referencing(Vector(fundOf(i), fundOf(100 + i)), 110000000, refIns(i))
      )
      val state = holding(
        v,
        outputs(
          (counts.indices
            .flatMap(i =>
              Vector(fundOf(i) -> paid(100000000), fundOf(100 + i) -> paid(10000000))
            ) ++
            refIns.flatten.map(
              _ -> refOut(5000000, 2, B.bytes(purposeIsWellformedNoDatumV2.script))
            ))*
        )
      )
      assertEquals(
        refused(s"BodyRefScriptsSizeTooBig at $v", blockOf(v, txs), state, params),
        Vector(
          into(
            new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Conway](
              Mismatch(scriptSize * counts.sum, maxRefScriptSizePerBlock)
            )
          )
        )
      )
    }
  }

  test(
    "version 11: BodyRefScriptsSizeTooBig with reference scripts in the same block (Conway/Imp/BbodySpec.hs:86)"
  ) {
    // `whenMajorVersionAtLeast @11`: at 9 and 10 the case runs nothing. Each count's reference
    // scripts produced in the block, each by a transaction of its own, then the transaction
    // referencing them (`mkBasicTx mkBasicTxBody & referenceInputsTxBodyL .~ refIns`), each funded
    // from an input of its own; the state before the block holds none of the scripts
    val v = 11
    val params = pp(v)
    val (scriptSize, maxRefScriptSizePerBlock, counts) = scriptCounts(params)
    val groups = counts.indices.toVector.map { i =>
      val producers =
        (0 until counts(i).toInt).toVector.map(j => producing(fundOf(1000 * (i + 1) + j)))
      (producers, referencing(Vector(fundOf(i)), 10000000, producers.map(outOf(_))))
    }
    val txs = groups.flatMap((producers, consumer) => producers :+ consumer)
    val state = holding(
      v,
      outputs(
        (counts.indices.map(i => fundOf(i) -> paid(10000000)) ++
          groups.indices.flatMap(i =>
            (0 until counts(i).toInt).map(j => fundOf(1000 * (i + 1) + j) -> paid(10000000))
          ))*
      )
    )
    assertEquals(
      refused("BodyRefScriptsSizeTooBig in the same block at 11", blockOf(v, txs), state, params),
      Vector(
        into(
          new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Conway](
            Mismatch(scriptSize * counts.sum, maxRefScriptSizePerBlock)
          )
        )
      )
    )
  }

  // ---- `totalRefScriptSizeInBlock` on transactions as they are applied ---------------------------------

  /** `RequireSignature` of a fresh key (`Shelley/Scripts.hs`), its bytes, hash and address. */
  private lazy val scriptKey = flat.TestLedger.key()
  private lazy val native: Node = B.arr(B.uint(0), B.bytes(scriptKey.hash))
  private lazy val nativeHash: Bytes = B.scriptHash(0, B.encode(native))

  private def total(params: PParams[Conway], txs: Vector[Built], utxo: UTxO[Conway]): Long =
    ConwayBbody.totalRefScriptSizeInBlock[Conway](
      EraPParams.conway.ppProtocolVersionL.get(params),
      txs.map(_.era),
      utxo
    )

  versions.foreach { v =>
    test(s"version $v: totalRefScriptSizeInBlock (Conway/Imp/BbodySpec.hs:122)") {
      val params = pp(v)
      // `originalBytesSize` of the native script: its bytes
      val scriptSize = B.encode(native).size.toLong
      assertEquals(scriptSize, 32L)
      val (scriptSpendIn, scriptSpendIn2, rootIn) = (B.fresh(0x21), B.fresh(0x22), B.fresh(0x23))
      val (fee1, fee2, fee3, fee4) = (B.fresh(0x24), B.fresh(0x25), B.fresh(0x26), B.fresh(0x27))
      // `produceRefScriptsTx`: an output holding the native script, the change after it
      def producingNative(in: B.In): Built =
        val body = B.body(
          0 -> B.set(in.node),
          1 -> B.arr(refOut(5000000, 0, native), paid(3000000)),
          2 -> B.uint(2000000)
        )
        built(body, B.witnesses(body, Vector(payer)))
      // `submitTxWithRefInputs`: the input and the reference inputs, with an input of its own for
      // the fee, signed by the payer and the script's key
      def spendingWithRefs(in: B.In, fee: B.In, refIns: Vector[B.In]): Built =
        val body = B.body(
          0 -> B.set(in.node, fee.node),
          1 -> B.arr(paid(13000000)),
          2 -> B.uint(2000000),
          18 -> B.set(refIns.map(_.node)*)
        )
        built(body, B.witnesses(body, Vector(payer, scriptKey)))
      val refScriptTx1 = producingNative(fee1)
      val refScriptTx1In = outOf(refScriptTx1)
      val refScriptTx2 = producingNative(fee2)
      val spendScriptWithRefScriptTx =
        spendingWithRefs(scriptSpendIn, fee3, Vector(refScriptTx1In))
      val spendScriptWithTwoRefScriptsTx =
        spendingWithRefs(scriptSpendIn2, fee4, Vector(refScriptTx1In, outOf(refScriptTx2)))
      val spendRootUtxoTx = referencing(Vector(rootIn), 10000000, Vector(refScriptTx1In))
      // not submitted: applying it would remove the reference script from the UTxO set. `fixupTx`
      // adds the root input, the output `spendRootUtxoTx` leaves (its change, its last output), and
      // balances it, the reference script's output both spent and referenced
      val spendRefScriptTx = referencing(
        Vector(refScriptTx1In, outOf(spendRootUtxoTx)),
        12000000,
        Vector(refScriptTx1In)
      )
      val before = holding(
        v,
        outputs(
          scriptSpendIn -> B.out(B.scriptAddress(nativeHash), 5000000),
          scriptSpendIn2 -> B.out(B.scriptAddress(nativeHash), 5000000),
          rootIn -> paid(10000000),
          fee1 -> paid(10000000),
          fee2 -> paid(10000000),
          fee3 -> paid(10000000),
          fee4 -> paid(10000000)
        )
      )
      val applied = Vector(
        refScriptTx1,
        refScriptTx2,
        spendScriptWithRefScriptTx,
        spendScriptWithTwoRefScriptsTx,
        spendRootUtxoTx
      )
      val afters = submitted(before, applied, params).map(utxoOf)
      // the sixth balanced on the set the five leave: what it consumes is what it produces
      // (`fixupFees`), no deposit refunded and no pool registered
      val spendRefScriptBody = tx.body[TxLevel.Top](spendRefScriptTx.era)
      assertEquals(
        EraUTxO.conway
          .getConsumedValue[TxLevel.Top](params, _ => None, afters(4), spendRefScriptBody)(using
            EraUTxO.conway.txOf.bodyOf.top
          ),
        EraUTxO.conway.getProducedValue(params, _ => false, spendRefScriptBody)
      )
      // within `simulateThenRestore`, each transaction's size against the UTxO set after it, the
      // last against the set after the five (`mkTxWithExpectedSize`, `:136-139`), and the whole list
      // against the set after the five (`:182`)
      val txsWithSizes = Vector(
        (refScriptTx1, 0L, afters(0)),
        (refScriptTx2, 0L, afters(1)),
        (spendScriptWithRefScriptTx, scriptSize, afters(2)),
        (spendScriptWithTwoRefScriptsTx, 2 * scriptSize, afters(3)),
        (spendRootUtxoTx, scriptSize, afters(4)),
        (spendRefScriptTx, scriptSize, afters(4))
      )
      txsWithSizes.foreach((t, size, utxo) => assertEquals(total(params, Vector(t), utxo), size))
      assertEquals(total(params, txsWithSizes.map(_._1), afters(4)), txsWithSizes.map(_._2).sum)
      // on the state before them, restored: each prefix's sum from version 11, 0 below it
      // (`isPostV10`, `:187-190`)
      val restored = utxoOf(before)
      txsWithSizes.indices.foreach { n =>
        val prefix = txsWithSizes.take(n + 1)
        assertEquals(
          total(params, prefix.map(_._1), restored),
          if v >= 11 then prefix.map(_._2).sum else 0L,
          s"$n"
        )
      }
    }

    test(
      s"version $v: Use a reference script in a collateral output (Conway/Imp/BbodySpec.hs:195)"
    ) {
      val params = pp(v)
      val scriptSize = B.encode(native).size.toLong
      val failing = example(Example.AlwaysFailsNoDatum, PlutusLanguage.V3)
      val (failingPlutusTxIn, feeIn, collateral, scriptTxIn, fee2) =
        (B.fresh(0x31), B.fresh(0x32), B.fresh(0x33), B.fresh(0x34), B.fresh(0x35))
      val before = holding(
        v,
        outputs(
          failingPlutusTxIn -> B.out(B.scriptAddress(failing.hash), 5000000),
          feeIn -> paid(10000000),
          collateral -> paid(30000000),
          scriptTxIn -> B.out(B.scriptAddress(nativeHash), 5000000),
          fee2 -> paid(10000000)
        )
      )
      // `submitPhase2Invalid`: spending the output of `alwaysFailsNoDatum SPlutusV3` (no datum, the
      // redeemer 0, its units as `fixedUp` leaves them: the parameters' most a transaction may use,
      // the estimate having failed), its collateral return the output holding the native script
      // (at a key address: `freshKeyAddr_` below version 12, `:210-215`)
      val flaggedValid = fixedUp(
        params,
        utxoOf(before),
        PlutusLanguage.V3,
        B.arr(B.uint(0), B.uint(spendingIx(failingPlutusTxIn, Vector(failingPlutusTxIn, feeIn)))),
        B.uint(0),
        None,
        (rdmrs, integrity) =>
          val body = B.body(
            (Vector(
              0 -> B.set(failingPlutusTxIn.node, feeIn.node),
              1 -> B.arr(paid(13000000)),
              2 -> B.uint(2000000),
              13 -> B.set(collateral.node),
              16 -> refOut(25000000, 0, native)
            ) ++ integrity.map(h => 11 -> B.bytes(h)).toVector).sortBy(_._1)*
          )
          built(
            body,
            B.witnesses(body, Vector(payer), 5 -> rdmrs, 7 -> B.set(B.bytes(failing.script)))
          )
      )
      val maxTx = AlonzoEraPParams.ppMaxTxExUnitsL.get(params).units
      assertEquals(tx.getTotalExUnits[TxLevel.Top](flaggedValid.era), maxTx)
      val createCollateralTx = phase2Invalid(before, flaggedValid, params)
      assertEquals(tx.isValid(createCollateralTx.era), IsPhase2Valid.Phase2Invalid)
      // `txInAt 1`: the collateral return, after the one output
      val refScriptTxIn = outOf(createCollateralTx, 1)
      val useBody = B.body(
        0 -> B.set(scriptTxIn.node, fee2.node),
        1 -> B.arr(paid(13000000)),
        2 -> B.uint(2000000),
        18 -> B.set(refScriptTxIn.node)
      )
      val useCollateralTx = built(useBody, B.witnesses(useBody, Vector(payer, scriptKey)))
      val afters =
        submitted(before, Vector(createCollateralTx, useCollateralTx), params).map(utxoOf)
      assertEquals(total(params, Vector(createCollateralTx), afters(0)), 0L)
      assertEquals(
        total(params, Vector(createCollateralTx, useCollateralTx), afters(1)),
        scriptSize
      )
      // on the state before them, restored (`isPostV10`, `:236-238`)
      assertEquals(
        total(params, Vector(createCollateralTx, useCollateralTx), utxoOf(before)),
        if v >= 11 then scriptSize else 0L
      )
    }
  }
