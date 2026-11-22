// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Id
import cats.data.{NonEmptyList, NonEmptySet}
import munit.FunSuite
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.{Nonce, Unsigned}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ChainAccountState,
  Coin,
  Dijkstra,
  EpochNo,
  Hash32,
  LedgerState,
  Network,
  TxIx,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalResult, ExUnits, PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The reference's BBODY cases that run for Dijkstra (`eras/dijkstra/impl/testlib/Test/Cardano/
  * Ledger/Dijkstra/Imp.hs`, cardano-ledger 9df9f98c, which runs Conway's driver and so Alonzo's and
  * Shelley's, each `withImpInitEachEraVersion`, at Dijkstra's one version, 12), each with its exact
  * expectation, on blocks made only by `DijkstraBlock.decode` of bytes whose header commits to its
  * body, the transactions built as the Imp's fixups leave them (`DijkstraImpBatch`: real CBOR, real
  * signatures, the reference's script binaries checked by hash), every check run and every script
  * evaluated:
  *   - Shelley's (`Shelley/Imp/BbodySpec.hs`): a header's declared size and hash past its body's,
  *     and a block holding its transaction twice, refused by LEDGERS with at least the inputs as
  *     bad (`:28`, `:43`, `:58`);
  *   - Alonzo's (`Alonzo/Imp/BbodySpec.hs`): for each of Dijkstra's four languages, a block
  *     admitted with a phase-2-invalid and a valid transaction of each script purpose (spending,
  *     minting, withdrawing, certifying), each invalid one first refused flagged valid with exactly
  *     its script's failure, on the script stake credential registered before it (`:49`, which runs
  *     for Dijkstra: `disableInConformanceIt` is an `it`, `Shelley/ImpTest.hs:880-888`); the
  *     block's execution units past `maxBlockExUnits`, each script spend after the transaction
  *     producing its collateral, as the block records them (`:96`);
  *   - Conway's (`Conway/Imp/BbodySpec.hs`): the block's reference scripts past
  *     `maxRefScriptSizePerBlock`, held before the block (`:43`) and created in it (`:86`); the
  *     function `totalRefScriptSizeInBlock` on a block's transactions as they are applied, and on
  *     every prefix of them before (`:122`), and with a collateral return holding the script, its
  *     transaction first refused flagged valid with exactly its script's failure (`:195`);
  *   - Dijkstra's (`Dijkstra/Imp/BbodySpec.hs`): a Peras certificate and a header's highest major
  *     version below the parameters' (`:25`, `:38`).
  *
  * The parameters are the testnet's genesis files' with the chain's cost models, so the values the
  * cases compute from them (the units, the limits) are those parameters', but for `:49`, which runs
  * at the Imp's units (`impUnits`): at the testnet's the same block is refused for them. A
  * transaction the Imp's fixups fund from its root output is funded from an input of its own, but
  * for the last of `:122`, which spends the output its root spend leaves, as the Imp's root then
  * is.
  *
  * What the cases bind of the block's reference scripts: on the function, the accumulation of the
  * block's outputs from version 11, a valid transaction's outputs and an invalid one's collateral
  * return (`:122`, `:195`), which a mutant without it, or with the two kinds of outputs swapped,
  * fails; through BBODY, `:86` refuses only the set before LEDGERS without the accumulation, and a
  * crafted case, the rule's reading of the pinned source rather than a reference recording, binds
  * the set before LEDGERS (`Dijkstra/Rules/Bbody.hs:394`): a block that spends an output holding a
  * reference script and then reads it, which a mutant reading the set after LEDGERS refuses for
  * LEDGERS's failure alone, without the reference scripts'. Two things the function does that
  * Dijkstra cannot show:
  *   - the version boundary: Dijkstra's version is never below 12 (`ProtVerLow DijkstraEra = 12`,
  *     `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:137-141`, and a
  *     version only follows its own, `pvCanFollow`, `Shelley/PParams.hs:332-340`), so the branch to
  *     10 is unreachable in the era; it is to be bound by Conway's BBODY on the era types (#85),
  *     which runs at 10 and 11, and the function at those versions is checked here as `:122` and
  *     `:195` give it;
  *   - the union's bias, the set's own entry kept, but for the edge listed below: an output's key
  *     is its body's id with an index, below the outputs' count for `txouts` and at it for
  *     `collOuts` (`mkCollateralTxIn`, `Babbage/Collateral.hs:52-60`), so a key both in the set and
  *     among a transaction's outputs, a transaction the block repeats or the chain applied before,
  *     holds the output that body gives it either way.
  *
  * Incomplete (CNF-2):
  *   - the union's bias at the edge of `mkCollateralTxIn`: a body of 65,536 outputs or more puts
  *     its collateral return at `maxBound`, the key of its 65,536th output, so the same body
  *     applied valid and then invalid shows the bias. Its outputs' encoding alone is past the
  *     testnet's transactions (16,384 bytes) and block bodies (90,112: `chainChecks` bounds the
  *     size a header declares, `eras/shelley/impl/src/Cardano/Ledger/Chain.hs:65-80`, and BBODY
  *     holds the body to it), and no case builds one;
  *   - the draws, each fixed (#85,
  *     https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7064531535): of `:49`, the valid
  *     transactions at the top level, not nested in a sub-transaction, the other of V4's two
  *     (`dijkstraTopTxFromAnyLevel`, `Dijkstra/ImpTest.hs:118-131`), which is not run, and the
  *     minted token's name and one token; of the transactions without scripts that Shelley's `:28`
  *     and `:43` and Dijkstra's `:25` and `:38` submit (`submitAnyTx_`), the top level, where the
  *     pin, with no script context, also draws the transaction nested in a sub-transaction, which
  *     is not run; the payer's address where the Imp draws a fresh key's (`freshKeyAddr_`,
  *     `freshKeyAddrNoPtr_`); the fees and amounts, fixed and not the fixups' (`fixupTxOuts` draws
  *     an amount before `ensureMinCoinTxOut`, and `fixupFees` derives the change from the root's
  *     balance): what the cases show is that their fixed fees and outputs satisfy the ledger's
  *     minima where LEDGER applies them. The refusals flagged valid (`:49`, `:195`) are compared by
  *     each failing script's machine error and trace and the request it ran, as `EraUtxosSuite`
  *     compares them: the machine's failure holds terms, equal only as the same object.
  */
class DijkstraBbodyImpSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val tx = EraTx.dijkstra
  private val bodies = EraTxBody.dijkstra
  private val B = DijkstraImpBatch

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  // ---- the parameters, the globals and the state -----------------------------------------------------

  private lazy val stored: flat.ProtocolParams =
    def genesis(era: String) = json(s"fixtures/leios/testnet/config/$era-genesis.json")
    flat.dijkstra.DijkstraFixture.genesis.upgrade(
      flat.ProtocolParams
        .fromGenesis(genesis("shelley"), genesis("alonzo"), Some(genesis("conway")))
        .fold(e => fail(e.toString), identity)
    )

  private lazy val pp: PParams[Dijkstra] =
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    val models = json("fixtures/reference/leios-testnet-chain-contexts/cases.json").hcursor
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)
    PParams
      .lensHKD[Dijkstra]
      .andThen(EraPParams.dijkstra.hkdCostModelsL[Id])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(era(stored))

  private val protVer = EraPParams.dijkstra.ppProtocolVersionL

  private val globals: Globals =
    flat.TestLedger.globals.copy(ledger =
      flat.TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c)
    )

  private lazy val history: flat.Horizon =
    val params = flat.Horizon.Params(100, 1000, 30, 30)
    flat.Horizon
      .History(Vector.empty, flat.Horizon.Edge(0, 0, 0), params, Some(params))
      .at(flat.Horizon.Transition.Unknown(Some(BigInt(0))))

  private val context =
    DijkstraUtxoContext(Network.Testnet, 0, history, flat.TxEnv.Validation.All)

  private lazy val start: LedgerState[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(EpochNo.fromUnsigned(0L), stored).es.ls

  /** The ledger state holding exactly the outputs of `utxo`. */
  private def holding(utxo: UTxO[Dijkstra]): LedgerState[Dijkstra] =
    start.copy[Dijkstra](utxo =
      start.utxo.copy[Dijkstra](
        utxo = UtxoTables(
          flat.Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
        ),
        instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, start.utxo.instantStake))
      )
    )

  // ---- transactions as the fixups leave them ---------------------------------------------------------

  private lazy val payer = flat.TestLedger.key()
  private def paid(coin: BigInt): Node = B.out(B.keyAddress(payer), coin)

  /** A transaction as a block holds it: its bytes, and in the era's types. */
  private final case class Built(raw: Bytes, era: DijkstraTx[TxLevel.Top])

  private def built(body: Node, wits: Node, valid: Boolean = true): Built =
    val raw = B.encode(B.arr(body, wits, B.nil, B.leaf(Value.Bool(valid))))
    Built(raw, ok(DijkstraTx.inBlock(node(raw))))

  /** `mkBasicTx mkBasicTxBody` as the fixups leave it: the payer's input `in` of 10 ada spent, a
    * fee of 2 ada and the change to the payer, signed by the payer.
    */
  private def basic(in: B.In): Built =
    val body = B.body(0 -> B.set(in.node), 1 -> B.arr(paid(8000000)), 2 -> B.uint(2000000))
    built(body, B.witnesses(body, Vector(payer)))

  /** `sendCoinTo` of `makeCollateralInput` (`Alonzo/ImpTest.hs:148-153`, cardano-ledger 9df9f98c):
    * 30 ada to the payer, a transaction the Imp records, funded from `in` of 40 ada.
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

  // ---- blocks made from bytes ------------------------------------------------------------------------

  /** The devnet's first Dijkstra block, which has no transaction: its bytes. */
  private lazy val plain: Bytes =
    devnetBlocks
      .map(_._2)
      .find(raw =>
        flat.Block.decodeShelley(raw) match
          case Right(flat.Block.Dijkstra(b)) => b.transactions.isEmpty
          case _                             => false
      )
      .getOrElse(fail("none"))

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

  /** `t` with only its flag set to `valid` (`isPhase2ValidTxL .~`): every other item the bytes it
    * holds.
    */
  private def withFlag(t: Built, valid: Boolean): Built =
    val raw = array(items(node(t.raw)).map(_.original).updated(3, encoded(Value.Bool(valid))))
    Built(raw, ok(DijkstraTx.inBlock(node(raw))))

  /** The devnet block with its body's parts and its header body's fields as `body` and `header`
    * make them, every other item the bytes it was read as.
    */
  private def crafted(
      body: Vector[Bytes] => Vector[Bytes],
      header: (Vector[Bytes], Bytes) => Vector[Bytes]
  ): Bytes =
    val outer = items(node(plain))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val newBody = array(body(items(inner(1)).map(_.original)))
    val newHeader = array(
      Vector(array(header(items(signed(0)).map(_.original), newBody)), signed(1).original)
    )
    array(Vector(outer(0).original, array(Vector(newHeader, newBody))))

  /** The header the Imp makes for a block (`tryTxsInBlock`, `Shelley/ImpTest.hs:1751-1784`,
    * cardano-ledger 9df9f98c): its body's size and hash (fields 6 and 7), and the version info of
    * the parameters' version, 12 and 0 (field 9).
    */
  private def imp(fields: Vector[Bytes], body: Bytes): Vector[Bytes] =
    fields
      .updated(6, encoded(Value.UInt(BigInt(body.size))))
      .updated(7, encoded(Value.ByteString(Blake2b.hash256.hash(body))))
      .updated(9, array(Vector(encoded(Value.UInt(12)), encoded(Value.UInt(0)))))

  /** A block of `txs`, its header as the Imp makes it and then as `modify` changes it. */
  private def blockOf(
      txs: Vector[Built],
      body: Vector[Bytes] => Vector[Bytes] = identity,
      modify: Vector[Bytes] => Vector[Bytes] = identity
  ): Bytes =
    crafted(
      parts => body(parts.updated(0, array(txs.map(_.raw)))),
      (fields, newBody) => modify(imp(fields, newBody))
    )

  private def run(raw: Bytes, state: LedgerState[Dijkstra], params: PParams[Dijkstra] = pp) =
    DijkstraBbodyRun.bbody(
      globals,
      BbodyEnv[Dijkstra](params, ChainAccountState(Coin(0), Coin(0))),
      ok(DijkstraBlock.decode(raw)),
      context,
      ShelleyBbodyState[Dijkstra](state, BlocksMade.empty),
      EraGovRun.dijkstraEmpty,
      ScarabEvaluator.default,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slot => Right(slot * 1000)
    )

  private def refused(
      raw: Bytes,
      state: LedgerState[Dijkstra],
      params: PParams[Dijkstra] = pp
  ): Vector[BbodyPredFailure[Dijkstra]] =
    run(raw, state, params) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"not refused: $other")

  private def into[F](f: F)(using i: InjectBbodyFailure[F, Dijkstra]): BbodyPredFailure[Dijkstra] =
    i.injectFailure(f)

  private val (in1, in2) = (B.fresh(0x01), B.fresh(0x02))
  private lazy val funded = B.utxo(in1 -> paid(10000000), in2 -> paid(10000000))

  test("the control: BBODY admits a block of one transaction as the Imp makes it") {
    val raw = blockOf(Vector(basic(in1)))
    run(raw, holding(funded)) match
      case Right(Right(_)) => ()
      case other           => fail(s"not admitted: $other")
  }

  // ---- Shelley's: `Shelley/Imp/BbodySpec.hs` --------------------------------------------------------

  test("WrongBlockBodySizeBBODY (Shelley/Imp/BbodySpec.hs:28)") {
    // `blockBodySizeBlockHeaderL %~ (+ 1)`: supplied the body's size, expected the header's
    val block = ok(DijkstraBlock.decode(blockOf(Vector(basic(in1)))))
    val size = EraBlockBody.dijkstra.blockBodySize(protVer.get(pp), block.blockBody)
    val declared = blockOf(
      Vector(basic(in1)),
      modify = _.updated(6, encoded(Value.UInt(BigInt(size) + 1)))
    )
    assertEquals(
      refused(declared, holding(funded)),
      Vector(
        into(
          new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](Mismatch(size, size + 1))
        )
      )
    )
  }

  test("InvalidBodyHashBBODY (Shelley/Imp/BbodySpec.hs:43)") {
    // `blockBodyHashBlockHeaderL .~ invalidBodyHash`: supplied the body's hash, expected the header's
    val invalid = ok(Hash32.fromBytes(Bytes.fromArray(Array.fill(32)(9.toByte))))
    val raw =
      blockOf(Vector(basic(in1)), modify = _.updated(7, encoded(Value.ByteString(invalid.bytes))))
    val block = ok(DijkstraBlock.decode(raw))
    assertEquals(
      refused(raw, holding(funded)),
      Vector(
        into(
          new ShelleyBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](
            Mismatch(EraBlockBody.dijkstra.hashBlockBody(block.blockBody), invalid)
          )
        )
      )
    )
  }

  test("LedgersFailure (Shelley/Imp/BbodySpec.hs:58)") {
    // `modifyBlockBody protVer $ txSeqBlockBodyL %~ \txs -> txs <> txs`, the header committing to
    // the body: at least the second copy's inputs as bad (`withTxsInModifiedFailingSubsetBlockM`)
    val once = basic(in1)
    val raw = blockOf(Vector(once, once))
    val block = ok(DijkstraBlock.decode(raw))
    val badInputs = NonEmptySet
      .fromSet(
        SortedSet.from(
          EraBlockBody.dijkstra
            .txSeqBlockBody(block.blockBody)
            .flatMap(t => bodies.inputs[TxLevel.Top](tx.body(t)))
        )
      )
      .getOrElse(fail("no input"))
    val expected = Set(into(new ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra](badInputs)))
    val failures = refused(raw, holding(funded)).toSet
    assertEquals(failures.intersect(expected), expected)
  }

  // ---- Dijkstra's: `Dijkstra/Imp/BbodySpec.hs` ------------------------------------------------------

  test("PerasCertValidationFailed (Dijkstra/Imp/BbodySpec.hs:25)") {
    // `modifyBlockBody protVer $ perasCertBlockBodyL .~ SJust perasCert`, the header committing to
    // the body; the nonce is the header's previous nonce
    val cert = Bytes.fromArray(Array.fill(4)(1.toByte))
    val raw = blockOf(Vector(basic(in1)), body = _.updated(2, encoded(Value.ByteString(cert))))
    val block = ok(DijkstraBlock.decode(raw))
    val nonce = LeiosEraBlockHeader.dijkstra.prevNonceBlockHeader(block)
    assertEquals(nonce, Nonce.Neutral)
    assertEquals(
      refused(raw, holding(funded)),
      Vector(
        into(DijkstraBbodyPredFailure.PerasCertValidationFailed[Dijkstra](PerasCert(cert), nonce))
      )
    )
  }

  test("HeaderProtVerTooLow (Dijkstra/Imp/BbodySpec.hs:38)") {
    // `bhviHighestSupportedMajorVersion = pred`, the Imp's 12 at the parameters' 12: supplied the
    // header's 11, expected 12
    assertEquals(protVer.get(pp).pvMajor.value, 12)
    val raw = blockOf(
      Vector(basic(in1)),
      modify = _.updated(9, array(Vector(encoded(Value.UInt(11)), encoded(Value.UInt(0)))))
    )
    assertEquals(
      LeiosEraBlockHeader.dijkstra
        .versionInfoBlockHeader(ok(DijkstraBlock.decode(raw)))
        .bhviHighestSupportedMajorVersion,
      11L
    )
    assertEquals(
      refused(raw, holding(funded)),
      Vector(into(DijkstraBbodyPredFailure.HeaderProtVerTooLow[Dijkstra](Mismatch(11L, 12L))))
    )
  }

  // ---- Alonzo's: `Alonzo/Imp/BbodySpec.hs:96`, cardano-ledger 9df9f98c, for each language -----------

  /** `alwaysSucceedsWithDatum` of each language, its binary and the hash its comment lists
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs:109-181`,
    * cardano-ledger 9df9f98c), with its language's tag and its witness set's key.
    */
  private val alwaysSucceedsWithDatum: Vector[(PlutusLanguage, Int, Int, Bytes, Bytes)] = Vector(
    (
      PlutusLanguage.V1,
      1,
      3,
      hex(
        "583d010000333333222222222323253330093370e900118059baa3007300a00214984c8cc01c0040058c018004" +
          "c018dd5000aab9d5744ae6955cf2ba157361"
      ),
      hex("dd9849ea907bc9bd9fc372d5d3e4e252d7a35d3b461c04e25d3b93ef")
    ),
    (
      PlutusLanguage.V2,
      2,
      6,
      hex(
        "583d010000333333222222222323253330093370e900118059baa3007300a00214984c8cc01c0040058c018004" +
          "c018dd5000aab9d5744ae6955cf2ba157361"
      ),
      hex("4f71b097e95d0df74efe265883545be88cd13797b58bee5b0eba9601")
    ),
    (
      PlutusLanguage.V3,
      3,
      7,
      hex(
        "58ab0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026464b30013370e90011804800c4cc00888c8c" +
          "94cd4d6600266e1d2000001891909118010019805000c56600266e1d200200189091000c5900b2016300a00221" +
          "4a22940c030004dd518038009803000c528200e37546a66002446464253001005801c009001180398050011803" +
          "00098029baa0022220012233002300500130080018a4d1326330024901035054350049900501"
      ),
      hex("90a4869bd26787c6e649fbc84b03dfbd7ef4f46fcd619d5931fd0280")
    ),
    (
      PlutusLanguage.V4,
      4,
      8,
      hex(
        "58590102003322232993370e90014800c880094a19900191000a4532600c00328009803800a50024928d280993" +
          "19801a49035054350049a930dd54c9bac001914800c8a400645001006802400a6eb8c024004244440045742ae7" +
          "01"
      ),
      hex("0c764807708e1366d1c9d7f87ab18d57790a1e317abb147b24046c30")
    )
  )

  test("the reference's example scripts hash as their comments list") {
    alwaysSucceedsWithDatum.foreach((lang, tag, _, script, hash) =>
      assertEquals(B.scriptHash(tag, script), hash, s"$lang")
    )
    assertEquals(B.scriptHash(2, purposeIsWellformedNoDatumV2), purposeIsWellformedNoDatumV2Hash)
    assertEquals(purposeIsWellformedNoDatumV2.size, 443)
    assertEquals(B.scriptHash(3, alwaysFailsNoDatumV3), alwaysFailsNoDatumV3Hash)
  }

  alwaysSucceedsWithDatum.foreach { (lang, _, witnessKey, script, hash) =>
    test(s"$lang: enforces ppMaxBlockExUnits (Alonzo/Imp/BbodySpec.hs:96)") {
      // `txCount = 1 + max (bMem `div` tMem) (bSteps `div` tSteps)` transactions, each spending an
      // output of the script with the datum 0 (`PlutusArgs (P.I 0) (Just $ P.I 0)`,
      // `Alonzo/ImpTest.hs:404`, cardano-ledger 9df9f98c) and the redeemer `(Data (P.I 0), maxTxUnits)`
      val maxBlockUnits = AlonzoEraPParams.ppMaxBlockExUnitsL.get(pp).units
      val maxTxUnits = AlonzoEraPParams.ppMaxTxExUnitsL.get(pp).units
      val txCount =
        1 + (maxBlockUnits.mem / maxTxUnits.mem).max(maxBlockUnits.steps / maxTxUnits.steps)
      val mismatch = Mismatch[RelLTEQ, ExUnits](
        ExUnits(txCount * maxTxUnits.mem, txCount * maxTxUnits.steps),
        maxBlockUnits
      )
      val datum = B.uint(0)
      val redeemers = B.cmap(
        B.arr(B.uint(0), B.uint(0)) ->
          B.arr(B.uint(0), B.arr(B.uint(maxTxUnits.mem), B.uint(maxTxUnits.steps)))
      )
      // each spend as `submitTx_` leaves it, after the transaction its fixups submit while the block
      // records (`addCollateralInput`, `Alonzo/ImpTest.hs:148-168`, cardano-ledger 9df9f98c;
      // `withTxsInFailingBlock`, `Shelley/ImpTest.hs:1446, 1740-1746`): the collateral's producer
      // (`collateralOf`), then the spend, the script's output first among its inputs (the
      // redeemer's index 0, as `fixupRedeemerIndices` leaves it), the fee's input, the producer's
      // 30 ada as its collateral, the change, the datum, the script and the integrity hash. The
      // state before the block holds the scripts' outputs (`produceScript`, before it) and the
      // inputs that fund the pairs, not the collateral
      val pairs = (0 until txCount.toInt).toVector.map { i =>
        val (scriptIn, feeIn, fund) = (B.fresh(0x10 + i), B.fresh(0x40 + i), B.fresh(0x70 + i))
        val producer = collateralOf(fund)
        def bodyOf(integrity: Bytes) = B.body(
          0 -> B.set(scriptIn.node, feeIn.node),
          1 -> B.arr(paid(12000000)),
          2 -> B.uint(3000000),
          11 -> B.bytes(integrity),
          13 -> B.set(outOf(producer).node)
        )
        def txOf(integrity: Bytes) =
          val body = bodyOf(integrity)
          built(
            body,
            B.witnesses(
              body,
              Vector(payer),
              4 -> B.set(datum),
              5 -> redeemers,
              witnessKey -> B.set(B.bytes(script))
            )
          )
        val integrity = ScriptIntegrity
          .mkScriptIntegrity[Dijkstra, TxLevel.Top](
            pp,
            txOf(Bytes(Vector.fill(32)(0.toByte))).era,
            Set(lang)
          )
          .map(ScriptIntegrity.hashScriptIntegrity(_))
          .getOrElse(fail("no script integrity"))
        val external = B.utxo(
          scriptIn -> B.datumHashOut(B.scriptAddress(hash), 5000000, B.dataHash(datum)),
          feeIn -> paid(10000000),
          fund -> paid(40000000)
        )
        (Vector(producer, txOf(integrity)), external)
      }
      val txs = pairs.flatMap(_._1)
      val utxo = UTxO[Dijkstra](pairs.flatMap(_._2.unUTxO).toMap)
      assertEquals(txs.size, 2 * txCount.toInt)
      // each admitted by LEDGER in turn, as the Imp submits it (`submitTx_`)
      assertEquals(submitted(txs, utxo).size, txs.size)
      assertEquals(
        refused(blockOf(txs), holding(utxo)),
        Vector(into(new AlonzoBbodyPredFailure.TooManyExUnits[Dijkstra](mismatch)))
      )
    }
  }

  // ---- Alonzo's: `Alonzo/Imp/BbodySpec.hs:49`, cardano-ledger 9df9f98c, for each language -----------

  /** A script of `Examples.hs` (cardano-ledger 9df9f98c): its binary and the hash its comment
    * lists.
    */
  private final case class Example(script: Bytes, hash: Bytes)

  /** The scripts of `:42-45` for one language
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs`, cardano-ledger
    * 9df9f98c: `alwaysFailsWithDatum` `:264-347`, `alwaysSucceedsNoDatum` `:34-106`,
    * `alwaysFailsNoDatum` `:184-261` and `evenRedeemerNoDatum` `:501-577`), beside
    * `alwaysSucceedsWithDatum` above.
    */
  private final case class EightScripts(
      alwaysFailsWithDatum: Example,
      alwaysSucceedsNoDatum: Example,
      alwaysFailsNoDatum: Example,
      evenRedeemerNoDatum: Example
  )

  private val eightScripts: Map[PlutusLanguage, EightScripts] = Map(
    PlutusLanguage.V1 -> EightScripts(
      alwaysFailsWithDatum = Example(
        hex(
          "58340100003333322222222323253330083370e900118051baa30063009002161498c014004c014dd5000aab" +
            "9d5744ae6955cf2ba101"
        ),
        hex("fb96118db82531c7eec57c892af422fd002d48a5591dc99b9c321685")
      ),
      alwaysSucceedsNoDatum = Example(
        hex(
          "5833010000333332222222323253330073370e900118049baa30053008002161498c010004c010dd5000aab9" +
            "d5744ae6955cf2ba11"
        ),
        hex("38c32b1383b0024ad3c3a4b16996c2542051a5b1615973c252cd831d")
      ),
      alwaysFailsNoDatum = Example(
        hex(
          "5833010000333332222222323253330073370e900118049baa30053008002149858c010004c010dd5000aab9" +
            "d5744ae6955cf2ba11"
        ),
        hex("b92fb32a431f2739c4378eddcfd1372dbe0916d85a7585db109e4126")
      ),
      evenRedeemerNoDatum = Example(
        hex(
          "5845010000333332222222323253330073370e900118049baa3005300800216153330073370e900019b86375" +
            "a00890020a4c2c600800260086ea800555ceaba25734aae795d081"
        ),
        hex("640fd4a96649384fb9406fd251a57c1516200343fb2c3ef669011748")
      )
    ),
    PlutusLanguage.V2 -> EightScripts(
      alwaysFailsWithDatum = Example(
        hex(
          "58340100003333322222222323253330083370e900118051baa30063009002161498c014004c014dd5000aab" +
            "9d5744ae6955cf2ba101"
        ),
        hex("46b9f33a9b0a7a712af70cb8789ae84417ec379017a8c0143e82ded1")
      ),
      alwaysSucceedsNoDatum = Example(
        hex(
          "5833010000333332222222323253330073370e900118049baa30053008002161498c010004c010dd5000aab9" +
            "d5744ae6955cf2ba11"
        ),
        hex("f6f8a9dd4cabbc802d15de6f7f01b55cb6c09eef071671bce6011e8f")
      ),
      alwaysFailsNoDatum = Example(
        hex(
          "5833010000333332222222323253330073370e900118049baa30053008002149858c010004c010dd5000aab9" +
            "d5744ae6955cf2ba11"
        ),
        hex("aeb8c6fdbe38292dc5fe7e121fb655c86cc511dbb5099bc2b3b9a242")
      ),
      evenRedeemerNoDatum = Example(
        hex(
          "5845010000333332222222323253330073370e900118049baa3005300800216153330073370e900019b86375" +
            "a00890020a4c2c600800260086ea800555ceaba25734aae795d081"
        ),
        hex("ebd1eee5f1861bffc28b47255616124539bc483911420667afb7dd3c")
      )
    ),
    PlutusLanguage.V3 -> EightScripts(
      alwaysFailsWithDatum = Example(
        hex(
          "589c0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026466002446464b30013370e90011806000c" +
            "4cc01488c8c94cd4d6600266e1d2000001891909118010019806800c56600266e1d200200189091000c5900e" +
            "201c300d002214a02944c03c004dd518050009804800c528a0143754600e6014004600c002600a6ea800888c" +
            "c008c014004c02000629344c98cc009241035054350049900501"
        ),
        hex("fe6f5484631753d44c197639b874d34bd65b33a590ac7d8c1cd43ca5")
      ),
      alwaysSucceedsNoDatum = Example(
        hex(
          "58ab0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026464b30013370e90011804800c4cc00888c" +
            "8c94cd4d6600266e1d2000001891909118010019805000c56600266e1d200200189091000c5900b2016300a0" +
            "02214a02944c030004dd518038009803000c528a00e37546a66002446464253001005801c009001180398050" +
            "01180300098029baa0022220012233002300500130080018a4d1326330024901035054350049900501"
        ),
        hex("5eeda416cb7ee9cbafbf648b044c5f0cfd3e1f1f4b280f575676432a")
      ),
      alwaysFailsNoDatum = Example(
        hex(
          "589c0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026466002446464b30013370e90011806000c" +
            "4cc01488c8c94cd4d6600266e1d2000001891909118010019806800c56600266e1d200200189091000c5900e" +
            "201c300d002214a22940c03c004dd518050009804800c52820143754600e6014004600c002600a6ea800888c" +
            "c008c014004c02000629344c98cc009241035054350049900501"
        ),
        hex("845624bf0d1f65ac0f5a175bc85c44782dc83d2a9e625bc25e39d8fb")
      ),
      evenRedeemerNoDatum = Example(
        hex(
          "58ae0101009800aab9daba2ab9aaab9eaba1ab9c4888888966002646600244646464b30013370e9001180680" +
            "144cc01888c8c94cd4d6600266e1d2000001891909118010019807000c56600266e1d200200189091000c590" +
            "0f201e300e002214a02600a9318080009baa300b001300a00289800a4c80588cdc3a400066e18dd6801a4008" +
            "6ea8c01cc028008c018004c014dd5001111980118028009804000c5268993198012481035054350049900501"
        ),
        hex("56a2f832a1394e59bffb0ee9ad90fe589e662f11e7e72157e152381d")
      )
    ),
    PlutusLanguage.V4 -> EightScripts(
      alwaysFailsWithDatum = Example(
        hex(
          "584c0102009800aba1ab9cabd84888a64cc00920043758003223293370e90014800c880094a39900191000a4" +
            "532601200328009805000a500249285288375400499319801a49035054350049a931"
        ),
        hex("e990fe3385c5dca656a54ff1f6235038dd9ca748ae02867927c4f94f")
      ),
      alwaysSucceedsNoDatum = Example(
        hex(
          "58590102003322232993370e90014800c880094a39900191000a4532600c00328009803800a5002492852889" +
            "9319801a49035054350049a930dd54c9bac001914800c8a400645001006802400a6eb8c02400424444004574" +
            "2ae701"
        ),
        hex("df3c23786bd8473dcf099b4b34b9d04944701b65d369a53052a2819c")
      ),
      alwaysFailsNoDatum = Example(
        hex(
          "584c0102009800aba1ab9cabd84888a64cc00920043758003223293370e90014800c880094a19900191000a4" +
            "532601200328009805000a50024928d280375400499319801a49035054350049a931"
        ),
        hex("b9b514f0e07b6ab86a525ed4b9af93c812c400bff4d8f0d836ef2b8d")
      ),
      evenRedeemerNoDatum = Example(
        hex(
          "585c01020033222993758003229001914800c88c8ca4cdc3a40052005220024c00526cc8014880052299300d" +
            "00194004c038005280124942600693023370e900019b86375a00c90021baa002132633002491035054350049" +
            "a9315d0ab9c1"
        ),
        hex("ab36086498da558f2d0f7fff31309ced1356feb53a3cf79a8edbe8bc")
      )
    )
  )

  test("the reference's example scripts of `:49` hash as their comments list") {
    alwaysSucceedsWithDatum.foreach((lang, tag, _, _, _) =>
      val s = eightScripts(lang)
      Vector(
        s.alwaysFailsWithDatum,
        s.alwaysSucceedsNoDatum,
        s.alwaysFailsNoDatum,
        s.evenRedeemerNoDatum
      )
        .foreach(e => assertEquals(B.scriptHash(tag, e.script), e.hash, s"$lang"))
    )
  }

  /** The parameters with the Imp's units, 10,000,000 and 10,000,000,000 a transaction and
    * 200,000,000 and 200,000,000,000 a block (`agMaxTxExUnits` and `agMaxBlockExUnits`,
    * `Alonzo/ImpTest.hs:436-445`, cardano-ledger 9df9f98c, which Dijkstra's Imp keeps:
    * `exampleDijkstraGenesis`, `Dijkstra/Examples.hs:176-210`, sets none). `:49` declares the
    * transaction's units four times, which the testnet's block of 20,000,000,000 steps cannot hold.
    */
  private lazy val impUnits: PParams[Dijkstra] =
    def units(mem: BigInt, steps: BigInt) =
      xyz.kd5ujc.scarab.ledger.era.ExUnits.from(ExUnits(mem, steps)).getOrElse(fail("units"))
    AlonzoEraPParams
      .ppMaxBlockExUnitsL[Dijkstra]
      .replace(units(200000000, BigInt(200000000000L)))(
        AlonzoEraPParams
          .ppMaxTxExUnitsL[Dijkstra]
          .replace(units(10000000, BigInt(10000000000L)))(pp)
      )

  /** The scripts of `t` with their contexts on `utxo` (`plutusScriptsWithContextStAnnTx`), each run
    * with the units its redeemer declares: what `evalTxExUnits` and `evalPlutusScriptsWithLogs` run
    * (`fixupRedeemers` and `impScriptPredicateFailure`, `Alonzo/ImpTest.hs:207-244, 485-512`,
    * cardano-ledger 9df9f98c).
    */
  private def evaluated(
      params: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      t: Built
  ): Vector[(PlutusWithContext, EvalResult)] =
    val stAnnTx =
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(s => Right(s * 1000), params, utxo, Map.empty, t.era)
    EraUTxO.dijkstra
      .plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)
      .fold(e => fail(s"$e"), _.toVector)
      .map(pwc => pwc -> ScarabEvaluator.default.evaluate(PlutusWithContext.evalRequest(pwc)))

  /** The redeemer of a transaction's one Plutus purpose `key` and its integrity hash as the fixups
    * leave them (`txWithMaxRedeemers`, `fixupRedeemers`, `fixupPPHash`, `Alonzo/ImpTest.hs:207-260,
    * 322-330`, cardano-ledger 9df9f98c): the redeemer the case gives (`explicit`), kept; else the
    * script's redeemer data with the units the script spends when its redeemer declares the
    * parameters' most units a transaction may use, or those most where it fails; then the integrity
    * hash of `lang` over the parameters. `make` builds the transaction of a redeemer map and a
    * hash.
    */
  private def fixedUp(
      params: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
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
      .mkScriptIntegrity[Dijkstra, TxLevel.Top](params, make(redeemers(d, u), None).era, Set(lang))
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    make(redeemers(d, u), Some(integrity))

  /** `impScriptPredicateFailure` (`Alonzo/ImpTest.hs:498-512`, cardano-ledger 9df9f98c) injected
    * into LEDGER (`submitPhase2Invalid`, `:525-541`): the transaction flagged valid refused for the
    * scripts that fail on `utxo`, each described as `scriptFailureToFailureDescription` gives it,
    * through Conway's UTXOS failure (`alonzoToConwayUtxosPredFailure`) and Dijkstra's UTXO, UTXOW
    * and LEDGER.
    */
  private def scriptPredicateFailure(
      params: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      t: Built
  ): LedgerPredFailure[Dijkstra] =
    val failures = NonEmptyList
      .fromList(evaluated(params, utxo, t).toList.collect {
        case (pwc, EvalResult.Failure(reason, _, _)) =>
          FailureDescription.scriptFailureToFailureDescription(ScriptFailure(reason, pwc))
      })
      .getOrElse(fail("no script failed"))
    DijkstraLedgerPredFailure.DijkstraUtxowFailure(
      DijkstraUtxowPredFailure.UtxoFailure(
        DijkstraUtxoPredFailure.UtxosFailure(
          ConwayUtxosPredFailure.ValidationTagMismatch[Dijkstra](
            IsPhase2Valid.Phase2Valid,
            TagMismatchDescription.FailedUnexpectedly(failures)
          )
        )
      )
    )

  /** A refusal of UTXOS's for failing scripts, comparable: the flag, and each failure by its
    * machine's error and the script's trace and by the request it ran. The machine's failure holds
    * terms, equal only as the same object (`EraUtxosSuite`'s `Cause`).
    */
  private def comparable(f: LedgerPredFailure[Dijkstra]) = f match
    case DijkstraLedgerPredFailure.DijkstraUtxowFailure(
          DijkstraUtxowPredFailure.UtxoFailure(
            DijkstraUtxoPredFailure.UtxosFailure(
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

  private def outOf(t: Built, ix: Int = 0): B.In = B.In(bodies.id[TxLevel.Top](tx.body(t.era)), ix)

  /** The index of `target` among `inputs` as a body orders them, a spending redeemer's. */
  private def spendingIx(target: B.In, inputs: Vector[B.In]): Int =
    def key(i: B.In) = B.utxo(i -> paid(0)).unUTxO.keys.head
    SortedSet.from(inputs.map(key)).toVector.indexOf(key(target))

  alwaysSucceedsWithDatum.foreach { (lang, _, witnessKey, succeedsWithDatum, succeedsHash) =>
    test(s"$lang: succeeds with eight Plutus scripts (Alonzo/Imp/BbodySpec.hs:49)") {
      val s = eightScripts(lang)
      val params = impUnits
      val maxTx = AlonzoEraPParams.ppMaxTxExUnitsL.get(params).units
      val deposit = EraPParams.ppKeyDepositCompactL[Dijkstra].get(params).toBigInt
      val even = s.evenRedeemerNoDatum
      // `ScriptHashObj evenRedeemerNoDatumHash`, and its reward account on the testnet
      val credential = B.arr(B.uint(1), B.bytes(even.hash))
      val accountAddress = Bytes(0xf0.toByte +: even.hash.value)
      // `PlutusArgs`' redeemers (`plutusTestScripts`, `Alonzo/ImpTest.hs:395-420`, cardano-ledger
      // 9df9f98c): 0 for the always succeeding and failing scripts, 2 for `evenRedeemerNoDatum`
      val (zero, one, two) = (B.uint(0), B.uint(1), B.uint(2))
      val datum = zero

      // `produceScript` (`Shelley/ImpTest.hs:2543-2553`, cardano-ledger 9df9f98c): an output of the
      // script with the datum hash `fixupOutputDatums` adds, the change after it
      def produceScript(in: B.In, hash: Bytes): Built =
        val body = B.body(
          0 -> B.set(in.node),
          1 -> B
            .arr(B.datumHashOut(B.scriptAddress(hash), 5000000, B.dataHash(datum)), paid(3000000)),
          2 -> B.uint(2000000)
        )
        built(body, B.witnesses(body, Vector(payer)))
      // a transaction of the case's `fields` as the fixups leave it: its collateral input, the
      // script (`fixupScriptWits`), the datum (`fixupDatums`), the redeemer and integrity hash
      // (`fixedUp`), signed by the payer (`updateAddrTxWits`)
      def plutus(
          utxo: UTxO[Dijkstra],
          fields: Vector[(Int, Node)],
          collateral: B.In,
          script: Bytes,
          datums: Vector[Node],
          key: Node,
          data: Node,
          explicit: Option[(Node, ExUnits)],
          valid: Boolean
      ): Built =
        fixedUp(
          params,
          utxo,
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
            built(body, B.witnesses(body, Vector(payer), more*), valid)
        )
      // `mkTokenMintingTx` (`Alonzo/ImpTest.hs:622-637`, cardano-ledger 9df9f98c), its draws fixed:
      // the asset name, one token, and the payer's address
      val name = Bytes.fromArray("eight".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
      def minting(policy: Bytes): (Int, Node) =
        9 -> B.cmap(B.bytes(policy) -> B.cmap(B.bytes(name) -> B.uint(1)))
      def minted(policy: Bytes): Node = B.arr(
        B.bytes(B.keyAddress(payer)),
        B.arr(B.uint(2000000), B.cmap(B.bytes(policy) -> B.cmap(B.bytes(name) -> B.uint(1))))
      )

      // the funds of each transaction, the Imp's root output's part (`addRootTxIn`)
      val fund = (0 until 20).map(fundOf).toVector
      val funding = B.utxo(
        fund.zipWithIndex.map((in, i) =>
          in -> paid(if i >= 2 && i <= 10 then 40000000 else 10000000)
        )*
      )

      // before the block: `registerStakeCredential` of the script's credential, `RegDepositTxCert`
      // with the parameters' deposit (`dijkstraGenRegTxCert`, `Dijkstra/ImpTest.hs:366-375`,
      // cardano-ledger 9df9f98c), which the script witnesses (`getScriptWitnessDijkstraTxCert`,
      // `Dijkstra/TxCert.hs:323-329`)
      val registrationCollateral = collateralOf(fund(10))
      val (afterCollateral, utxo1) = step(holding(funding), funding, registrationCollateral, params)
      val registration = plutus(
        utxo1,
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
        None,
        valid = true
      )
      val (before, utxo2) = step(afterCollateral, utxo1, registration, params)

      // the block, as `withTxsInBlock_` records it: each transaction submitted in turn, the
      // producing and collateral ones among them. A phase-2-invalid one is first refused flagged
      // valid with exactly its scripts' failure, then submitted flagged invalid without fixups
      // (`submitPhase2Invalid`); a valid one is submitted at the top level, the draw
      // `dijkstraTopTxFromAnyLevel` makes for V4 among its two (`Dijkstra/ImpTest.hs:118-131`,
      // cardano-ledger 9df9f98c)
      val failingIn = outOf(produceScript(fund(0), s.alwaysFailsWithDatum.hash))
      val succeedingIn = outOf(produceScript(fund(1), succeedsHash))
      val collaterals = (2 to 9).map(i => collateralOf(fund(i))).toVector
      def spending(in: B.In, fee: B.In, script: Bytes, c: Int)(
          utxo: UTxO[Dijkstra],
          valid: Boolean
      ) =
        plutus(
          utxo,
          Vector(0 -> B.set(in.node, fee.node), 1 -> B.arr(paid(10000000)), 2 -> B.uint(5000000)),
          outOf(collaterals(c)),
          script,
          Vector(datum),
          B.arr(B.uint(0), B.uint(spendingIx(in, Vector(in, fee)))),
          zero,
          None,
          valid
        )
      def mint(policy: Bytes, script: Bytes, fee: B.In, c: Int)(
          utxo: UTxO[Dijkstra],
          valid: Boolean
      ) =
        plutus(
          utxo,
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
          None,
          valid
        )
      // `dex i = (Data $ P.I i, maxExUnits)`, at `mkWithdrawingPurpose (AsIx 0)` and
      // `mkCertifyingPurpose (AsIx 0)`
      def withdrawing(i: Node, fee: B.In, c: Int)(utxo: UTxO[Dijkstra], valid: Boolean) =
        plutus(
          utxo,
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
          Some(i -> maxTx),
          valid
        )
      // `genUnRegTxCert`: `UnRegDepositTxCert` with the account's deposit
      // (`dijkstraGenUnRegTxCert`, `Dijkstra/ImpTest.hs:377-389`, cardano-ledger 9df9f98c),
      // refunded to the change
      def certifying(i: Node, fee: B.In, c: Int)(utxo: UTxO[Dijkstra], valid: Boolean) =
        plutus(
          utxo,
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
          Some(i -> maxTx),
          valid
        )
      enum Submit:
        case Fixed(t: Built)
        case Phase2Invalid(make: (UTxO[Dijkstra], Boolean) => Built)
        case Valid(make: (UTxO[Dijkstra], Boolean) => Built)
      // "notValidatingTx" and "validatingTx" (`Alonzo/Imp/BbodySpec.hs:54-59`, cardano-ledger
      // 9df9f98c)
      val spends = Vector(
        Submit.Fixed(produceScript(fund(0), s.alwaysFailsWithDatum.hash)),
        Submit.Fixed(collaterals(0)),
        Submit.Phase2Invalid(spending(failingIn, fund(11), s.alwaysFailsWithDatum.script, 0)),
        Submit.Fixed(produceScript(fund(1), succeedsHash)),
        Submit.Fixed(collaterals(1)),
        Submit.Valid(spending(succeedingIn, fund(12), succeedsWithDatum, 1))
      )
      // "notValidatingTxWithMint" and "validatingTxWithMint" (`:61-66`)
      val mints = Vector(
        Submit.Fixed(collaterals(2)),
        Submit.Phase2Invalid(
          mint(s.alwaysFailsNoDatum.hash, s.alwaysFailsNoDatum.script, fund(13), 2)
        ),
        Submit.Fixed(collaterals(3)),
        Submit.Valid(
          mint(s.alwaysSucceedsNoDatum.hash, s.alwaysSucceedsNoDatum.script, fund(14), 3)
        )
      )
      // "notValidatingTxWithWithdrawal" and "validatingTxWithWithdrawal" (`:74-83`)
      val withdrawals = Vector(
        Submit.Fixed(collaterals(4)),
        Submit.Phase2Invalid(withdrawing(one, fund(15), 4)),
        Submit.Fixed(collaterals(5)),
        Submit.Valid(withdrawing(zero, fund(16), 5))
      )
      // "notValidatingTxWithCert" and "validatingTxWithCert" (`:85-94`)
      val certs = Vector(
        Submit.Fixed(collaterals(6)),
        Submit.Phase2Invalid(certifying(one, fund(17), 6)),
        Submit.Fixed(collaterals(7)),
        Submit.Valid(certifying(zero, fund(18), 7))
      )
      val submits = spends ++ mints ++ withdrawals ++ certs
      val (_, _, txs) = submits.foldLeft((before, utxo2, Vector.empty[Built])) {
        case ((state, utxo, done), submit) =>
          val t = submit match
            case Submit.Fixed(t)    => t
            case Submit.Valid(make) => make(utxo, true)
            case Submit.Phase2Invalid(make) =>
              val flagged = make(utxo, true)
              val refusal = ledgerOn(flagged, state, utxo, params) match
                case Right(Left(fs)) => fs.toChain.toVector
                case other           => fail(s"not refused: $other")
              assertEquals(refusal.size, 1, s"$refusal")
              assertEquals(
                refusal.map(comparable),
                Vector(comparable(scriptPredicateFailure(params, utxo, flagged)))
              )
              make(utxo, false)
          val (next, nextUtxo) = step(state, utxo, t, params)
          (next, nextUtxo, done :+ t)
      }
      assertEquals(txs.size, 18)
      // the block of the eighteen, on the state after the registration, admitted
      run(blockOf(txs), before, params) match
        case Right(Right(_)) => ()
        case other           => fail(s"not admitted: $other")
      // at the testnet's units the same block is refused for them alone, past its block's steps
      val total =
        txs.foldLeft(ExUnits.zero)((sum, t) => sum + tx.getTotalExUnits[TxLevel.Top](t.era))
      assertEquals(
        refused(blockOf(txs), before),
        Vector(
          into(
            new AlonzoBbodyPredFailure.TooManyExUnits[Dijkstra](
              Mismatch(total, AlonzoEraPParams.ppMaxBlockExUnitsL.get(pp).units)
            )
          )
        )
      )
    }
  }

  // ---- Conway's: `Conway/Imp/BbodySpec.hs` ----------------------------------------------------------

  /** `purposeIsWellformedNoDatum SPlutusV2`
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs:656-815`,
    * cardano-ledger 9df9f98c), and the hash its comment lists.
    */
  private val purposeIsWellformedNoDatumV2: Bytes = hex(
    "5901b8010000333333333222222222223233001223232323232533300f3370e90000008a9998079998013a9375c60" +
      "1a601c00a66600800e4444444444440104c2930b0a99980799b87480080045854ccc03ccdc3a40080022a66601e66" +
      "60044002601a601c00a66600800e44444444444400c4c2930b099299980819b874801800854ccc040c04cc004ccc0" +
      "1402088888888888801c98585261323300e0010016323001001223330054bd7011299980999baf301130120090021" +
      "3301500233004004001133004004001001301200422232330010010022330054a0466602466ebcc014010c058004" +
      "928919801801800911299980580088018998011806800980800091119803911919191919191919191999999999998" +
      "069bac00b3758602e0146eb0c05c024dd5980b8041bab30170073758602e00c6eacc05c014c05c010dd6180b8019b" +
      "ab30170023756602e002646a66603466e1d2000301e00112375c60320022c60320026ea8c05cc068004c064004c0" +
      "60004c05c004c058004c054004c050004c04c004c048004c044004c034dd50019baa3008001300737540044466004" +
      "600e0026014002aae755d1aba05744ae6955cf2ba15736aae7d"
  )
  private val purposeIsWellformedNoDatumV2Hash: Bytes =
    hex("331237807ff563e59b696b579604a9f02b6989be5dfdc7a77af409a7")

  /** `alwaysFailsNoDatum SPlutusV3` (`Examples.hs:184-262`, cardano-ledger 9df9f98c), and its hash.
    */
  private val alwaysFailsNoDatumV3: Bytes = hex(
    "589c0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026466002446464b30013370e90011806000c4cc01" +
      "488c8c94cd4d6600266e1d2000001891909118010019806800c56600266e1d200200189091000c5900e201c300d0" +
      "02214a22940c03c004dd518050009804800c52820143754600e6014004600c002600a6ea800888cc008c014004c02" +
      "000629344c98cc009241035054350049900501"
  )
  private val alwaysFailsNoDatumV3Hash: Bytes =
    hex("845624bf0d1f65ac0f5a175bc85c44782dc83d2a9e625bc25e39d8fb")

  /** `genNumAdditionsExceeding sc maxSingle toExceed` (`Conway/Imp/BbodySpec.hs:244-252`,
    * cardano-ledger 9df9f98c) with the largest value its generator allows drawn each time: counts
    * whose multiples of `sc` sum past `toExceed`, each multiple at most `maxSingle`.
    */
  private def genNumAdditionsExceeding(sc: Long, maxSingle: Long, toExceed: Long): Vector[Long] =
    val x = math.min(toExceed / sc, maxSingle / sc)
    Vector.unfold(0L)(tot => Option.when(tot <= toExceed)((x, tot + x * sc)))

  private val maxScriptsPerTx = 100L

  /** The counts of `:53-64`, `:92-101` for the V2 script, and the script's size. */
  private lazy val scriptCounts: (Long, Long, Vector[Long]) =
    val scriptSize = purposeIsWellformedNoDatumV2.size.toLong
    val p = ConwayEraPParams.dijkstra
    val maxRefScriptSizePerTx = p.ppMaxRefScriptSizePerTxG.get(pp).value.toLong
    val maxRefScriptSizePerBlock = p.ppMaxRefScriptSizePerBlockG.get(pp).value.toLong
    val maxSingle = math.min(maxRefScriptSizePerTx, maxScriptsPerTx * scriptSize)
    val counts = genNumAdditionsExceeding(scriptSize, maxSingle, maxRefScriptSizePerBlock)
    // the generator's bounds
    assert(counts.forall(n => n >= 1 && n * scriptSize <= maxSingle), s"$counts")
    assert(counts.sum * scriptSize > maxRefScriptSizePerBlock)
    assert(counts.init.sum * scriptSize <= maxRefScriptSizePerBlock)
    // at the testnet's limits (204,800 a transaction, 1,048,576 a block) and the script's 443 bytes:
    // 24 transactions of 100 references each, 1,063,200 bytes
    assertEquals(counts, Vector.fill(24)(100L))
    (scriptSize, maxRefScriptSizePerBlock, counts)

  /** `produceRefScript` 's transaction (`Babbage/ImpTest.hs:131-159`, cardano-ledger 9df9f98c): an
    * output holding the V2 script, funded from `in`, the change to the payer.
    */
  private def producing(in: B.In): Built =
    val body = B.body(
      0 -> B.set(in.node),
      1 -> B.arr(refOut(5000000, 2, B.bytes(purposeIsWellformedNoDatumV2)), paid(3000000)),
      2 -> B.uint(2000000)
    )
    built(body, B.witnesses(body, Vector(payer)))

  /** A transaction referencing `refIns` (`mkTxWithRefInputs`, `:161-170`) as the fixups leave it:
    * spending `ins`, of `coin` in all, a fee of 3 ada and the change to the payer.
    */
  private def referencing(ins: Vector[B.In], coin: BigInt, refIns: Vector[B.In]): Built =
    val body = B.body(
      0 -> B.set(ins.map(_.node)*),
      1 -> B.arr(paid(coin - 3000000)),
      2 -> B.uint(3000000),
      18 -> B.set(refIns.map(_.node)*)
    )
    built(body, B.witnesses(body, Vector(payer)))

  private def fundOf(i: Int): B.In = B.In(Blake2b.hash256.hash(B.encode(B.uint(i))))

  test("BodyRefScriptsSizeTooBig (Conway/Imp/BbodySpec.hs:43)") {
    // the reference scripts produced before the block, each transaction of the block spending the
    // 100 ada sent to it before the block (`sendCoinTo addr (Coin 100_000_000)`) and, for the root
    // input `addRootTxIn` adds, an input of its own, and referencing as many of the scripts as its
    // count
    val (scriptSize, maxRefScriptSizePerBlock, counts) = scriptCounts
    val refIns = counts.indices.toVector.map(i =>
      (0 until counts(i).toInt).toVector.map(j => fundOf(1000 * (i + 1) + j))
    )
    val txs = counts.indices.toVector.map(i =>
      referencing(Vector(fundOf(i), fundOf(100 + i)), 110000000, refIns(i))
    )
    val utxo = B.utxo(
      (counts.indices
        .flatMap(i => Vector(fundOf(i) -> paid(100000000), fundOf(100 + i) -> paid(10000000))) ++
        refIns.flatten.map(_ -> refOut(5000000, 2, B.bytes(purposeIsWellformedNoDatumV2))))*
    )
    assertEquals(
      refused(blockOf(txs), holding(utxo)),
      Vector(
        into(
          new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](
            Mismatch(scriptSize * counts.sum, maxRefScriptSizePerBlock)
          )
        )
      )
    )
  }

  test(
    "BodyRefScriptsSizeTooBig with reference scripts in the same block (Conway/Imp/BbodySpec.hs:86)"
  ) {
    // `whenMajorVersionAtLeast @11`: Dijkstra's one version, 12. Each count's reference scripts
    // produced in the block, each by a transaction of its own, then the transaction referencing
    // them (`mkBasicTx mkBasicTxBody & referenceInputsTxBodyL .~ refIns`), each funded from an
    // input of its own; the state before the block holds none of the scripts
    val (scriptSize, maxRefScriptSizePerBlock, counts) = scriptCounts
    val groups = counts.indices.toVector.map { i =>
      val producers =
        (0 until counts(i).toInt).toVector.map(j => producing(fundOf(1000 * (i + 1) + j)))
      val refIns = producers.map(p => B.In(bodies.id[TxLevel.Top](tx.body(p.era)), 0))
      (producers, referencing(Vector(fundOf(i)), 10000000, refIns))
    }
    val txs = groups.flatMap((producers, consumer) => producers :+ consumer)
    val utxo = B.utxo(
      (counts.indices.map(i => fundOf(i) -> paid(10000000)) ++
        groups.indices.flatMap(i =>
          (0 until counts(i).toInt).map(j => fundOf(1000 * (i + 1) + j) -> paid(10000000))
        ))*
    )
    assertEquals(
      refused(blockOf(txs), holding(utxo)),
      Vector(
        into(
          new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](
            Mismatch(scriptSize * counts.sum, maxRefScriptSizePerBlock)
          )
        )
      )
    )
  }

  test(
    "crafted: BodyRefScriptsSizeTooBig against the UTxO set before LEDGERS (Dijkstra/Rules/Bbody.hs:394)"
  ) {
    // a crafted case, not a reference recording: the rule as the pinned source reads it, the
    // block's reference scripts against `ls ^. utxoL`, the set before LEDGERS. Before the block the
    // output `held` holds the V2 script; the block's tx1 spends it and its later tx2 reads it as a
    // reference input. Against the set before LEDGERS both count the script
    // (`txNonDistinctRefScriptsSize` reads spent and referenced inputs alike, and the accumulation
    // only adds outputs); against the set after, from which tx1 spent it, neither does. The limit,
    // one byte below the two, admits either one alone
    val scriptSize = purposeIsWellformedNoDatumV2.size.toLong
    val limit = 2 * scriptSize - 1
    val params = PParams
      .lensHKD[Dijkstra]
      .andThen(EraPParams.dijkstra.hkdMaxRefScriptSizePerBlockL[Id])
      .replace(Unsigned.from[Unsigned.Width.W32](BigInt(limit)).getOrElse(fail(s"$limit")))(pp)
    val (held, fee2) = (B.fresh(0x51), B.fresh(0x52))
    val tx1 = basic(held)
    val tx2 = referencing(Vector(fee2), 10000000, Vector(held))
    val utxo = B.utxo(
      held -> refOut(10000000, 2, B.bytes(purposeIsWellformedNoDatumV2)),
      fee2 -> paid(10000000)
    )
    val heldIn = B.utxo(held -> paid(10000000)).unUTxO.keys.head
    // LEDGERS refuses tx2 alone: its reference input is not in the UTxO set tx1 leaves
    // (`SpendInputsOf txTop ∪ RefInputsOf txTop ∪ CollInputsOf txTop ⊆ dom(utxo₀)`,
    // `Dijkstra/Rules/Utxo.hs:350-351`, cardano-ledger 9df9f98c); the whole refusal in the order
    // the reference returns it, the last recorded first
    assertEquals(
      refused(blockOf(Vector(tx1, tx2)), holding(utxo), params),
      Vector(
        into(
          new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](
            Mismatch(2 * scriptSize, limit)
          )
        ),
        into(
          new ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra](
            NonEmptySet.one(heldIn)(using cats.Order.fromOrdering)
          )
        )
      )
    )
  }

  // ---- `totalRefScriptSizeInBlock` on transactions as they are applied ---------------------------------

  private lazy val slot: BigInt =
    EraBlockHeader.dijkstra.slotNoBlockHeader(ok(DijkstraBlock.decode(plain))).toBigInt

  /** The transactions applied in turn by LEDGER with every check (`submitTx`, `trySubmitTx`,
    * `Shelley/ImpTest.hs:1406-1446`, cardano-ledger 9df9f98c), each admitted: the UTxO set after
    * each, read back from the ledger state.
    */
  private def submitted(txs: Vector[Built], utxo: UTxO[Dijkstra]): Vector[UTxO[Dijkstra]] =
    txs
      .scanLeft((holding(utxo), utxo)) { case ((state, before), t) =>
        step(state, before, t, pp)
      }
      .drop(1)
      .map(_._2)

  /** LEDGER with every check on `t` from `state`, whose UTxO set is `utxo`, at the block's slot. */
  private def ledgerOn(
      t: Built,
      state: LedgerState[Dijkstra],
      utxo: UTxO[Dijkstra],
      params: PParams[Dijkstra]
  ) =
    DijkstraLedgerRun.ledger(
      globals,
      LedgerEnv[Dijkstra](
        slot,
        Some(ok(EpochNo.fromBigInt(globals.epochOf(slot)))),
        TxIx.minBound,
        params,
        ChainAccountState(Coin(0), Coin(0))
      ),
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(s => Right(s * 1000), params, utxo, Map.empty, t.era),
      context,
      state,
      EraGovRun.dijkstraEmpty,
      ScarabEvaluator.default,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  /** `t` submitted (`submitTx`, `Shelley/ImpTest.hs:1391-1468`, cardano-ledger 9df9f98c): admitted
    * by LEDGER, the state after it, and the UTxO set it leaves, the inputs or the collateral spent
    * and the outputs or the collateral return created, which the state holds.
    */
  private def step(
      state: LedgerState[Dijkstra],
      utxo: UTxO[Dijkstra],
      t: Built,
      params: PParams[Dijkstra]
  ): (LedgerState[Dijkstra], UTxO[Dijkstra]) =
    val after = ledgerOn(t, state, utxo, params) match
      case Right(Right((ls, _, _))) => ls
      case other                    => fail(s"not admitted: $other")
    val body = tx.body(t.era)
    val spent = tx.isValid(t.era) match
      case IsPhase2Valid.Phase2Valid   => bodies.inputs[TxLevel.Top](body)
      case IsPhase2Valid.Phase2Invalid => bodies.collateralInputs(body)
    val created = tx.isValid(t.era) match
      case IsPhase2Valid.Phase2Valid   => UTxOs.txouts[Dijkstra, TxLevel.Top](body)
      case IsPhase2Valid.Phase2Invalid => BabbageUtxo.collOuts[Dijkstra](body)
    val next = UTxO[Dijkstra](utxo.unUTxO.removedAll(spent) ++ created.unUTxO)
    // the set the state holds, read back: every output before or after as `next` has it
    // (`expectTxSuccess`)
    (utxo.unUTxO.keySet ++ created.unUTxO.keySet).foreach(in =>
      assertEquals(
        after.utxo.utxo.utxo.get(in),
        next.unUTxO.get(in).map(StoredTxOut.dijkstra.toStored),
        s"$in"
      )
    )
    (after, next)

  /** `RequireSignature` of a fresh key (`Shelley/Scripts.hs`), its bytes, hash and address. */
  private lazy val scriptKey = flat.TestLedger.key()
  private lazy val native: Node = B.arr(B.uint(0), B.bytes(scriptKey.hash))
  private lazy val nativeHash: Bytes = B.scriptHash(0, B.encode(native))

  private def total(major: Int, txs: Vector[Built], utxo: UTxO[Dijkstra]): Long =
    ConwayBbody.totalRefScriptSizeInBlock[Dijkstra](
      protVer.get(pp).copy(pvMajor = Version.mkVersion(major).getOrElse(fail(s"$major"))),
      txs.map(_.era),
      utxo
    )

  test("totalRefScriptSizeInBlock (Conway/Imp/BbodySpec.hs:122)") {
    // `originalBytesSize` of the native script: its bytes
    val scriptSize = B.encode(native).size.toLong
    assertEquals(scriptSize, 32L)
    val (scriptSpendIn, scriptSpendIn2, rootIn) = (B.fresh(0x21), B.fresh(0x22), B.fresh(0x23))
    val (fee1, fee2, fee3, fee4) = (B.fresh(0x24), B.fresh(0x25), B.fresh(0x26), B.fresh(0x27))
    def producingNative(in: B.In): Built =
      val body = B.body(
        0 -> B.set(in.node),
        1 -> B.arr(refOut(5000000, 0, native), paid(3000000)),
        2 -> B.uint(2000000)
      )
      built(body, B.witnesses(body, Vector(payer)))
    // `submitTxWithRefInputs`: the input and the reference inputs, with an input of its own for the
    // fee, signed by the payer and the script's key
    def spendingWithRefs(in: B.In, fee: B.In, refIns: Vector[B.In]): Built =
      val body = B.body(
        0 -> B.set(in.node, fee.node),
        1 -> B.arr(paid(13000000)),
        2 -> B.uint(2000000),
        18 -> B.set(refIns.map(_.node)*)
      )
      built(body, B.witnesses(body, Vector(payer, scriptKey)))
    val refScriptTx1 = producingNative(fee1)
    val refScriptTx1In = B.In(bodies.id[TxLevel.Top](tx.body(refScriptTx1.era)), 0)
    val refScriptTx2 = producingNative(fee2)
    val refScriptTx2In = B.In(bodies.id[TxLevel.Top](tx.body(refScriptTx2.era)), 0)
    val spendScriptWithRefScriptTx = spendingWithRefs(scriptSpendIn, fee3, Vector(refScriptTx1In))
    val spendScriptWithTwoRefScriptsTx =
      spendingWithRefs(scriptSpendIn2, fee4, Vector(refScriptTx1In, refScriptTx2In))
    val spendRootUtxoTx = referencing(Vector(rootIn), 10000000, Vector(refScriptTx1In))
    // not submitted: applying it would remove the reference script from the UTxO set. `fixupTx`
    // adds the root input, the output `spendRootUtxoTx` leaves (its change, its last output), and
    // balances it, the reference script's output both spent and referenced
    val spendRefScriptTx =
      referencing(Vector(refScriptTx1In, outOf(spendRootUtxoTx)), 12000000, Vector(refScriptTx1In))
    val before = B.utxo(
      scriptSpendIn -> B.out(B.scriptAddress(nativeHash), 5000000),
      scriptSpendIn2 -> B.out(B.scriptAddress(nativeHash), 5000000),
      rootIn -> paid(10000000),
      fee1 -> paid(10000000),
      fee2 -> paid(10000000),
      fee3 -> paid(10000000),
      fee4 -> paid(10000000)
    )
    val applied = Vector(
      refScriptTx1,
      refScriptTx2,
      spendScriptWithRefScriptTx,
      spendScriptWithTwoRefScriptsTx,
      spendRootUtxoTx
    )
    val afters = submitted(applied, before)
    // the sixth balanced on the set the five leave: what it consumes is what it produces
    // (`fixupFees`; `shelleyConsumed`, `produced`, `Shelley/UTxO.hs:111-129`, cardano-ledger
    // 9df9f98c), no deposit refunded and no pool registered
    val spendRefScriptBody = tx.body(spendRefScriptTx.era)
    assertEquals(
      EraUTxO.dijkstra.getConsumedValue[TxLevel.Top](pp, _ => None, afters(4), spendRefScriptBody),
      EraUTxO.dijkstra.getProducedValue(pp, _ => false, spendRefScriptBody)
    )
    // each transaction's size against the UTxO set after it, the last against the set after the five
    val txsWithSizes = Vector(
      (refScriptTx1, 0L, afters(0)),
      (refScriptTx2, 0L, afters(1)),
      (spendScriptWithRefScriptTx, scriptSize, afters(2)),
      (spendScriptWithTwoRefScriptsTx, 2 * scriptSize, afters(3)),
      (spendRootUtxoTx, scriptSize, afters(4)),
      (spendRefScriptTx, scriptSize, afters(4))
    )
    txsWithSizes.foreach((t, size, utxo) => assertEquals(total(12, Vector(t), utxo), size))
    assertEquals(total(12, txsWithSizes.map(_._1), afters(4)), txsWithSizes.map(_._2).sum)
    // on the state before them, restored: each prefix's sum (`isPostV10`, at Dijkstra's 12)
    txsWithSizes.indices.foreach { n =>
      val prefix = txsWithSizes.take(n + 1)
      assertEquals(total(12, prefix.map(_._1), before), prefix.map(_._2).sum, s"$n")
    }
    // the case's formula at the versions Dijkstra's driver does not run (10 and 11, Conway's): 0
    // through 10, the sum from 11
    txsWithSizes.indices.foreach { n =>
      val prefix = txsWithSizes.take(n + 1)
      assertEquals(total(10, prefix.map(_._1), before), 0L, s"$n")
      assertEquals(total(11, prefix.map(_._1), before), prefix.map(_._2).sum, s"$n")
    }
  }

  test("Use a reference script in a collateral output (Conway/Imp/BbodySpec.hs:195)") {
    val scriptSize = B.encode(native).size.toLong
    val (failingPlutusTxIn, feeIn, collateral, scriptTxIn, fee2) =
      (B.fresh(0x31), B.fresh(0x32), B.fresh(0x33), B.fresh(0x34), B.fresh(0x35))
    val before = B.utxo(
      failingPlutusTxIn -> B.out(B.scriptAddress(alwaysFailsNoDatumV3Hash), 5000000),
      feeIn -> paid(10000000),
      collateral -> paid(30000000),
      scriptTxIn -> B.out(B.scriptAddress(nativeHash), 5000000),
      fee2 -> paid(10000000)
    )
    // `submitPhase2Invalid` (`Alonzo/ImpTest.hs:525-542`, cardano-ledger 9df9f98c): spending the
    // output of `alwaysFailsNoDatum SPlutusV3` (no datum, the redeemer 0, its units as `fixedUp`
    // leaves them: the parameters' most a transaction may use, the estimate having failed), its
    // collateral return the output holding the native script. The fixed-up transaction is first
    // refused flagged valid with exactly its script's failure, then submitted with only its flag
    // set invalid
    val flaggedValid = fixedUp(
      pp,
      before,
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
          B.witnesses(body, Vector(payer), 5 -> rdmrs, 7 -> B.set(B.bytes(alwaysFailsNoDatumV3)))
        )
    )
    val maxTx = AlonzoEraPParams.ppMaxTxExUnitsL.get(pp).units
    assertEquals(tx.getTotalExUnits[TxLevel.Top](flaggedValid.era), maxTx)
    val refusal = ledgerOn(flaggedValid, holding(before), before, pp) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"not refused: $other")
    assertEquals(refusal.size, 1, s"$refusal")
    assertEquals(
      refusal.map(comparable),
      Vector(comparable(scriptPredicateFailure(pp, before, flaggedValid)))
    )
    val createCollateralTx = withFlag(flaggedValid, valid = false)
    assertEquals(tx.isValid(createCollateralTx.era), IsPhase2Valid.Phase2Invalid)
    assertEquals(
      createCollateralTx.raw.value.dropRight(1),
      flaggedValid.raw.value.dropRight(1)
    )
    // `txInAt 1`: the collateral return, after the one output
    val refScriptTxIn = B.In(bodies.id[TxLevel.Top](tx.body(createCollateralTx.era)), 1)
    val useBody = B.body(
      0 -> B.set(scriptTxIn.node, fee2.node),
      1 -> B.arr(paid(13000000)),
      2 -> B.uint(2000000),
      18 -> B.set(refScriptTxIn.node)
    )
    val useCollateralTx = built(useBody, B.witnesses(useBody, Vector(payer, scriptKey)))
    val afters = submitted(Vector(createCollateralTx, useCollateralTx), before)
    assertEquals(total(12, Vector(createCollateralTx), afters(0)), 0L)
    assertEquals(total(12, Vector(createCollateralTx, useCollateralTx), afters(1)), scriptSize)
    // on the state before them, restored (`isPostV10`, at Dijkstra's 12)
    assertEquals(total(12, Vector(createCollateralTx, useCollateralTx), before), scriptSize)
    // the case's formula at Conway's 10 and 11, which Dijkstra's driver does not run
    assertEquals(total(10, Vector(createCollateralTx, useCollateralTx), before), 0L)
    assertEquals(total(11, Vector(createCollateralTx, useCollateralTx), before), scriptSize)
  }
