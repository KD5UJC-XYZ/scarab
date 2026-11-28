// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.Id
import cats.data.{NonEmptyList, NonEmptyVector, State as StateT}
import java.nio.file.{Files, Path}
import munit.Assertions
import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.{Horizon, PosixClock, TranslationError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.TestLedger.Key
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.{hex, node}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraImpBatch
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Build,
  ChainAccountState,
  Coin,
  Conway,
  EpochNo,
  LedgerState,
  Network,
  ShelleyBasedEra,
  TxIx
}
import xyz.kd5ujc.scarab.plutus.{EvalResult, ExUnits, PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.protocol.{Era as FlatEra, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage as UplcLanguage, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.cost.DefaultCostModels

/** The reference's `ImpTest` for Conway, as far as the BBODY cases use it (cardano-ledger f649f975:
  * `Shelley/ImpTest.hs`, `Alonzo/ImpTest.hs`, `Babbage/ImpTest.hs`, `Conway/ImpTest.hs`): the Imp's
  * parameters, its state with its root output, transactions made by its fixups (`babbageFixupTx`,
  * `Babbage/ImpTest.hs:77-91`, cardano-ledger f649f975) and submitted by Conway's LEDGER on the era
  * types, each then checked as `impBabbageExpectTxSuccess` checks it, and blocks of the submitted
  * transactions made as `tryTxsInBlock'` makes them (`Shelley/ImpTest.hs:1494-1542`, cardano-ledger
  * f649f975), with real CBOR and real signatures. The Imp's draws are fixed: each fresh key is a
  * new Ed25519 key whose address has no stake reference (`freshKeyAddr_`, one of the three forms
  * `freshKeyAddrPtr` draws, `Shelley/ImpTest.hs:1795-1806`), and a zero output's amount before
  * `ensureMinCoinTxOut` is zero (`fixupTxOuts` draws it, `:1135-1150`), so the output holds the
  * least its parameters allow.
  */
object ConwayImp extends Assertions:
  val B: DijkstraImpBatch.type = DijkstraImpBatch
  given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  val layout: StateLayout = ok(StateLayout.of(ReferenceBuild.Release11_1))

  /** Conway's protocol versions, each of which every case runs at (`eraProtVersions`). */
  val versions: Vector[Int] = Vector(9, 10, 11)

  // ---- the scripts of `Examples.hs` and their `PlutusArgs` --------------------------------------

  /** The scripts of `libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs` the
    * cases use.
    */
  enum Example:
    case AlwaysSucceedsWithDatum, AlwaysFailsWithDatum, AlwaysSucceedsNoDatum, AlwaysFailsNoDatum,
      EvenRedeemerNoDatum, PurposeIsWellformedNoDatum

  /** A script of `Examples.hs` at f649f975: the line of its binary, the binary, and the hash its
    * comment lists.
    */
  final case class Binary(line: Int, script: Bytes, hash: Bytes)

  /** Conway's languages (`eraLanguages`), with each one's tag and witness set key. */
  val languages: Vector[(PlutusLanguage, Int, Int)] =
    Vector((PlutusLanguage.V1, 1, 3), (PlutusLanguage.V2, 2, 6), (PlutusLanguage.V3, 3, 7))

  def tagOf(lang: PlutusLanguage): Int =
    languages.find(_._1 == lang).map(_._2).getOrElse(fail(s"$lang"))
  def witnessKeyOf(lang: PlutusLanguage): Int =
    languages.find(_._1 == lang).map(_._3).getOrElse(fail(s"$lang"))

  val examples: Map[(Example, PlutusLanguage), Binary] = ConwayImpExamples.all

  def example(e: Example, lang: PlutusLanguage): Binary =
    examples.getOrElse((e, lang), fail(s"no $e for $lang"))

  /** `PlutusArgs` of a script of `plutusTestScripts` (`Alonzo/ImpTest.hs:391-417`, cardano-ledger
    * f649f975): its redeemer and, for a spending script, its datum.
    */
  def argsOf(e: Example): (BigInt, Option[BigInt]) = e match
    case Example.AlwaysSucceedsWithDatum | Example.AlwaysFailsWithDatum   => (0, Some(0))
    case Example.AlwaysSucceedsNoDatum | Example.AlwaysFailsNoDatum       => (0, None)
    case Example.EvenRedeemerNoDatum | Example.PurposeIsWellformedNoDatum => (2, None)

  /** A script of `scriptTestContexts` (`plutusTestScripts` of each of Conway's languages): its
    * language, binary, redeemer data and spending datum.
    */
  final case class TestScript(
      lang: PlutusLanguage,
      script: Bytes,
      redeemer: Node,
      datum: Option[Node]
  )

  val testScripts: Map[Bytes, TestScript] =
    examples.map { case ((e, lang), b) =>
      val (r, d) = argsOf(e)
      b.hash -> TestScript(lang, b.script, B.uint(r), d.map(B.uint))
    }

  // ---- the Imp's parameters ---------------------------------------------------------------------

  /** `costModelParamsForTesting` of each language, `testingCostModel`
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus.hs:101-117`, cardano-ledger
    * f649f975): the parameters of a model the language's names name, in their order
    * (`extractCostModelParamsLedgerOrder`): for PlutusV3, `defaultCekCostModelForTesting`, variant
    * E; for PlutusV2, `defaultCekCostModelForTestingB`, variant B, without the machine costs and
    * the builtins PlutusV3 introduced (`V3.clearMachineCostModel`, `V3.clearBuiltinCostModel`); for
    * PlutusV1, PlutusV2's without the builtins PlutusV2 introduced (`V2.clearBuiltinCostModel`)
    * (`plutus-ledger-api/testlib/PlutusLedgerApi/Test/V{1,2,3}/EvaluationContext.hs` and
    * `ExBudgetingDefaults.hs:304-308`, plutus 261766ad); the models and the names plutus
    * 1.70.0.0's, which cardano-node 11.1.3 links.
    */
  lazy val testingCostModels: Map[Int, Vector[BigInt]] =
    val models = DefaultCostModels.Plutus170
    // `V3.clearBuiltinCostModel` and `V3.clearMachineCostModel`
    // (`Test/V3/EvaluationContext.hs:47-105`, plutus 261766ad), by their names' prefixes
    val v3Introduced = Vector(
      "cekConstrCost",
      "cekCaseCost",
      "bls12_381_G1_add",
      "bls12_381_G1_neg",
      "bls12_381_G1_scalarMul",
      "bls12_381_G1_equal",
      "bls12_381_G1_hashToGroup",
      "bls12_381_G1_compress",
      "bls12_381_G1_uncompress",
      "bls12_381_G2_add",
      "bls12_381_G2_neg",
      "bls12_381_G2_scalarMul",
      "bls12_381_G2_equal",
      "bls12_381_G2_hashToGroup",
      "bls12_381_G2_compress",
      "bls12_381_G2_uncompress",
      "bls12_381_millerLoop",
      "bls12_381_mulMlResult",
      "bls12_381_finalVerify",
      "keccak_256",
      "blake2b_224",
      "integerToByteString",
      "byteStringToInteger",
      "andByteString",
      "orByteString",
      "xorByteString",
      "complementByteString",
      "readBit",
      "writeBits",
      "replicateByte",
      "shiftByteString",
      "rotateByteString",
      "countSetBits",
      "findFirstSetBit",
      "ripemd_160",
      "expModInteger",
      "dropList",
      "lengthOfArray",
      "listToArray",
      "indexArray",
      "bls12_381_G1_multiScalarMul",
      "bls12_381_G2_multiScalarMul"
    )
    // `V2.clearBuiltinCostModel` (`Test/V2/EvaluationContext.hs`, plutus 261766ad)
    val v2Introduced = Vector(
      "serialiseData",
      "verifyEcdsaSecp256k1Signature",
      "verifySchnorrSecp256k1Signature",
      "integerToByteString",
      "byteStringToInteger"
    )
    def extracted(
        language: UplcLanguage,
        variant: SemanticsVariant,
        cleared: Vector[String]
    ): Vector[BigInt] =
      val values = models.parameters(variant)
      models
        .names(language)
        .filterNot(n => cleared.exists(b => n.startsWith(s"$b-")))
        .flatMap(values.get)
        .map(BigInt(_))
    Map(
      UplcLanguage.V1.id -> extracted(
        UplcLanguage.V1,
        SemanticsVariant.B,
        v3Introduced ++ v2Introduced
      ),
      UplcLanguage.V2.id -> extracted(UplcLanguage.V2, SemanticsVariant.B, v3Introduced),
      UplcLanguage.V3.id -> extracted(UplcLanguage.V3, SemanticsVariant.E, Vector.empty)
    )

  /** The Imp's parameters at version `major`: Shelley's genesis (`Shelley/ImpTest.hs:832-847` over
    * `emptyShelleyPParams`, `Shelley/PParams.hs:202-221`), Alonzo's (`Alonzo/ImpTest.hs:422-446`,
    * its `CoinPerWord` 34,482 a `CoinPerByte` of 4,310 from Babbage,
    * `coinsPerUTxOWordToCoinsPerUTxOByte`, `Babbage/PParams.hs:390-391`), the PlutusV2 model
    * Babbage adds (`Babbage/ImpTest.hs:57-61`) and Conway's (`Conway/ImpTest.hs:256-301`), every
    * model `testingCostModel`, all cardano-ledger f649f975; the version `major` and 0
    * (`conwayModifyImpInitProtVer`, `Conway/ImpTest.hs:320-331`).
    */
  def stored(major: Int): flat.ProtocolParams =
    def r(n: BigInt, d: BigInt) = Rational(n, d)
    flat.ProtocolParams(
      minFeeA = 44,
      minFeeB = 155381,
      maxTxSize = 16384,
      maxBlockBodySize = 65536,
      maxValueSize = 5000,
      coinsPerUTxOByte = 4310,
      keyDeposit = 2000000,
      poolDeposit = 500000000,
      eMax = 18,
      nOpt = 150,
      a0 = r(3, 10),
      rho = r(3, 1000),
      tau = r(2, 10),
      minPoolCost = 340000000,
      protocolMajor = major,
      drepDeposit = 70000000,
      drepActivity = 100,
      govActionDeposit = 123,
      govActionLifetime = 30,
      minFeeRefScriptCostPerByte = r(15, 1),
      protocolMinor = 0,
      maxBlockHeaderSize = 0,
      d = Rational.zero,
      minUTxOValue = 2000000,
      priceMem = r(577, 10000),
      priceSteps = r(721, 10000000),
      maxTxExUnits = ExUnits(10000000, BigInt(10000000000L)),
      maxBlockExUnits = ExUnits(200000000, BigInt(200000000000L)),
      collateralPercentage = 150,
      maxCollateralInputs = 3,
      costModels = testingCostModels,
      poolVotingThresholds =
        flat.PoolVotingThresholds(r(51, 100), r(65, 100), r(65, 100), r(51, 100), r(51, 100)),
      drepVotingThresholds = flat.DRepVotingThresholds(
        r(51, 100),
        r(65, 100),
        r(65, 100),
        r(65, 100),
        r(51, 100),
        r(51, 100),
        r(51, 100),
        r(51, 100),
        r(75, 100),
        r(51, 100)
      ),
      committeeMinSize = 1,
      committeeMaxTermLength = 20,
      maxRefScriptSizePerBlock = 1024 * 1024,
      maxRefScriptSizePerTx = 200 * 1024,
      refScriptCostStride = 25600,
      refScriptCostMultiplier = r(6, 5)
    )

  def pp(major: Int): PParams[Conway] =
    PParams[Conway](ok(ConwayPParams.fromStored(stored(major))))

  val globals: flat.rules.Globals =
    flat.TestLedger.globals.copy(ledger =
      flat.TestLedger.globals.ledger
        .copy(network = Network.Testnet, referenceBuild = ReferenceBuild.Release11_1)
    )

  /** One era from the start at the globals' geometry, as `fixedEpochInfo` is. */
  val horizon: Horizon =
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

  val slotToPosix: BigInt => Either[TranslationError, BigInt] = s => Right(s * 1000)
  val clock: PosixClock = PosixClock.Total(_ * 1000)

  val context: UtxoContext[Conway] = UtxoContext[Conway](
    Network.Testnet,
    globals.systemStart,
    horizon,
    flat.TxEnv.Validation.All,
    ConwayUtxo.injectShelleyFailure(ReferenceBuild.Release11_1)
  )

  // ---- outputs and transactions -----------------------------------------------------------------

  /** A key's enterprise address on the testnet, as `freshKeyAddr_` draws it without a stake
    * reference.
    */
  def keyAddress(k: Key): Bytes = B.keyAddress(k)
  def scriptAddress(hash: Bytes): Bytes = B.scriptAddress(hash)

  /** An output (`mkBasicTxOut`): its address, coin, the tokens of its value, its datum hash and its
    * reference script (the tag of its language and the script).
    */
  final case class Out(
      address: Bytes,
      coin: BigInt,
      assets: Option[Node] = None,
      datumHash: Option[Bytes] = None,
      refScript: Option[(Int, Node)] = None
  ):
    private def value: Node = assets.fold(B.uint(coin))(a => B.arr(B.uint(coin), a))
    def node: Node = refScript match
      case None => B.arr((Vector(B.bytes(address), value) ++ datumHash.map(B.bytes).toVector)*)
      case Some((tag, script)) =>
        B.cmap(
          (Vector(B.uint(0) -> B.bytes(address), B.uint(1) -> value) ++
            datumHash.map(h => B.uint(2) -> B.arr(B.uint(0), B.bytes(h))).toVector ++
            Vector(B.uint(3) -> B.tagged(24, B.bytes(B.encode(B.arr(B.uint(tag), script))))))*
        )
    def size: Long = B.encode(node).size.toLong

  /** A purpose of a script the Imp knows (`PlutusPurpose AsItem`). */
  enum Purpose:
    case Spending(in: TxIn)
    case Minting(policy: Bytes)
    case Certifying(ix: Int)
    case Withdrawing(account: Bytes)

  /** A certificate, with the script hash of its credential if it needs a script's witness. */
  final case class Cert(node: Node, script: Option[Bytes])

  /** A transaction before or after its fixups (`mkBasicTx mkBasicTxBody` and its lenses). */
  final case class Spec(
      inputs: SortedSet[TxIn] = SortedSet.empty,
      outputs: Vector[Out] = Vector.empty,
      fee: BigInt = 0,
      certs: Vector[Cert] = Vector.empty,
      withdrawals: Vector[(Bytes, BigInt)] = Vector.empty,
      mint: Option[(Bytes, Node)] = None,
      collateral: SortedSet[TxIn] = SortedSet.empty,
      collateralReturn: Option[Out] = None,
      refInputs: SortedSet[TxIn] = SortedSet.empty,
      redeemers: Map[Purpose, (Node, ExUnits)] = Map.empty,
      nativeScripts: Map[Bytes, Node] = Map.empty,
      plutusScripts: Map[Bytes, TestScript] = Map.empty,
      datums: Vector[Node] = Vector.empty,
      integrity: Option[Bytes] = None,
      valid: Boolean = true,
      ttl: Option[BigInt] = None
  ):
    def redeemerKey(p: Purpose): Node = p match
      case Purpose.Spending(in)   => B.arr(B.uint(0), B.uint(inputs.toVector.indexOf(in)))
      case Purpose.Minting(_)     => B.arr(B.uint(1), B.uint(0))
      case Purpose.Certifying(ix) => B.arr(B.uint(2), B.uint(ix))
      case Purpose.Withdrawing(a) =>
        B.arr(B.uint(3), B.uint(withdrawals.map(_._1).sortBy(_.hex).indexOf(a)))

  def inNode(in: TxIn): Node = B.arr(B.bytes(in.id), B.uint(in.index))

  /** A transaction as a block holds it: its body's and its witnesses' bytes and its flag, and in
    * the era's types.
    */
  final case class Built(body: Bytes, wits: Bytes, valid: Boolean):
    def transaction: flat.Block.Transaction = flat.Block.Transaction(body, wits, None, valid)
    def id: Bytes = transaction.id
    def era: ConwayTx = ok(ConwayTx.decode(transaction, layout))
    def in(ix: Int): TxIn = TxIn(id, ix)

  /** `spec`'s bytes, signed by `signers`. */
  def encode(spec: Spec, signers: Vector[Key]): Built =
    def set(ins: SortedSet[TxIn]) = B.set(ins.toVector.map(inNode)*)
    val fields = Vector(
      Some(0 -> set(spec.inputs)),
      Some(1 -> B.arr(spec.outputs.map(_.node)*)),
      Some(2 -> B.uint(spec.fee)),
      spec.ttl.map(t => 3 -> B.uint(t)),
      Option.when(spec.certs.nonEmpty)(4 -> B.set(spec.certs.map(_.node)*)),
      Option.when(spec.withdrawals.nonEmpty)(
        5 -> B.cmap(spec.withdrawals.sortBy(_._1.hex).map((a, c) => B.bytes(a) -> B.uint(c))*)
      ),
      spec.mint.map((policy, assets) => 9 -> B.cmap(B.bytes(policy) -> assets)),
      spec.integrity.map(h => 11 -> B.bytes(h)),
      Option.when(spec.collateral.nonEmpty)(13 -> set(spec.collateral)),
      spec.collateralReturn.map(o => 16 -> o.node),
      Option.when(spec.refInputs.nonEmpty)(18 -> set(spec.refInputs))
    ).flatten
    val body = B.body(fields*)
    val plutus = spec.plutusScripts.values.toVector
      .groupBy(s => witnessKeyOf(s.lang))
      .toVector
      .map((key, ss) => key -> B.set(ss.sortBy(_.script.hex).map(s => B.bytes(s.script))*))
    val more = (Option
      .when(spec.nativeScripts.nonEmpty)(1 -> B.set(spec.nativeScripts.values.toVector*))
      .toVector ++ plutus ++
      Option.when(spec.datums.nonEmpty)(4 -> B.set(spec.datums*)).toVector ++
      Option
        .when(spec.redeemers.nonEmpty)(
          5 -> B.cmap(spec.redeemers.toVector.map { case (p, (d, u)) =>
            spec.redeemerKey(p) -> B.arr(d, B.arr(B.uint(u.mem), B.uint(u.steps)))
          }*)
        )
        .toVector).sortBy(_._1)
    Built(B.encode(body), B.encode(B.witnesses(body, signers, more*)), spec.valid)

  /** The outputs given, each at its input, read by the node's flat decoder from bodies holding
    * them, a hundred each.
    */
  def outputs(entries: (TxIn, Out)*): Map[TxIn, TxOut] =
    entries
      .grouped(100)
      .flatMap { group =>
        val body = B.body(
          0 -> B.set(B.fresh(0xff).node),
          1 -> B.arr(group.map(_._2.node)*),
          2 -> B.uint(0)
        )
        val read = ok(
          flat.Tx.fromBlock(
            flat.Block.Transaction(B.encode(body), B.encode(B.cmap()), None, true),
            FlatEra.Conway
          )
        )
        assertEquals(read.body.outputs.size, group.size)
        group.map(_._1).zip(read.body.outputs)
      }
      .toMap

  // ---- blocks made from bytes -------------------------------------------------------------------

  /** The first recorded Conway block of `fixtures/eras`, which has no transaction: its bytes. */
  lazy val plain: Bytes =
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

  def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  def array(parts: Vector[Bytes]): Bytes =
    val n = parts.size
    val head =
      if n < 24 then Bytes.fromArray(Array((0x80 + n).toByte))
      else if n < 256 then Bytes.fromArray(Array(0x98.toByte, n.toByte))
      else Bytes.fromArray(Array(0x99.toByte, (n >> 8).toByte, n.toByte))
    parts.foldLeft(head)(_ ++ _)

  def encoded(v: Value): Bytes = B.encode(B.leaf(v))

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

  /** The header the Imp makes for a block (`tryTxsInBlock'`, `Shelley/ImpTest.hs:1506-1520`,
    * cardano-ledger f649f975): its body's size and hash (fields 6 and 7: the segments' length and
    * the digest of their digests, `hashAlonzoSegWits`), and the parameters' version, `major` and 0
    * (field 9).
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
    * changes it, and its body's transactions as `body` changes them.
    */
  def blockOf(
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

  def decoded(raw: Bytes): Block[ConwayBlockHeader, Conway] = ok(ConwayBlock.decode(raw, layout))

  lazy val slot: BigInt = EraBlockHeader.conway.slotNoBlockHeader(decoded(plain)).toBigInt

  /** The node's era run of the block `raw` (`ConwayBlockRun.program`) on `state` with `params`, as
    * a body program of an evaluator and the node's phase-2 observer.
    */
  def program(raw: Bytes, state: LedgerState[Conway], params: PParams[Conway])(
      evaluator: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[Id],
      observing: Option[ConwayBlockRun.Observing]
  ): ConwayBlockRun.Body =
    programAhead(raw, state, params)(
      evaluator,
      observing,
      xyz.kd5ujc.scarab.ledger.era.ContextsAhead.none[Conway]
    )

  /** [[program]] whose LEDGERS takes the contexts made `ahead` where they are its own (#1499). */
  def programAhead(raw: Bytes, state: LedgerState[Conway], params: PParams[Conway])(
      evaluator: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[Id],
      observing: Option[ConwayBlockRun.Observing],
      ahead: xyz.kd5ujc.scarab.ledger.era.ContextsAhead[Conway]
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
      observing,
      ahead
    )

  // ---- the Imp's state --------------------------------------------------------------------------

  /** The Imp's state (`ImpTestState`): the ledger state, its root output (`impRootTxIn`), the keys
    * of its addresses by their hashes (`impKeyPairs`), its native scripts with the key each
    * requires (`impNativeScripts`), and the transactions submitted while recording
    * (`impRecordSubmittedTxs`).
    */
  final case class ImpState(
      ls: LedgerState[Conway],
      root: TxIn,
      keys: Map[Bytes, Key],
      natives: Map[Bytes, (Node, Key)],
      recorded: Option[Vector[Built]]
  ):
    def utxo: Map[TxIn, TxOut] = ls.utxo.utxo.utxo.toMap

  type Imp[A] = StateT[ImpState, A]

  /** The Imp's start at version `major` (`defaultInitImpTestState`, `Shelley/ImpTest.hs:648-688`,
    * cardano-ledger f649f975): one output, the root, of `sgMaxLovelaceSupply` to a fresh key's
    * address, at the input of a transaction id of zeros.
    */
  def start(major: Int): ImpState =
    val rootKey = flat.TestLedger.key()
    val root = TxIn(Bytes(Vector.fill(32)(0.toByte)), 0)
    val nes = Build.nes[Conway](0L, stored(major))
    val funded = Build.funded[Conway](
      nes,
      outputs(root -> Out(keyAddress(rootKey), BigInt("45000000000000000")))
    )
    ImpState(funded.es.ls, root, Map(rootKey.hash -> rootKey), Map.empty, None)

  /** The Imp at version `major`: its parameters, its fixups and how it submits transactions and
    * makes blocks.
    */
  final class At(val major: Int):
    val params: PParams[Conway] = pp(major)
    private val maxTx: ExUnits = AlonzoEraPParams.ppMaxTxExUnitsL.get(params).units
    private val tx = EraTx.conway
    private val bodies = EraTxBody.conway
    private given bodies.Level[TxLevel.Top] = bodies.top
    private val vals = summon[Val[MultiAssetValue]]

    def init: ImpState = start(major)

    // ---- keys and addresses ---------------------------------------------------------------------

    /** `freshKeyHash`: a new key the Imp knows. */
    val freshKey: Imp[Key] = StateT { s =>
      val k = flat.TestLedger.key()
      (s.copy(keys = s.keys.updated(k.hash, k)), k)
    }

    /** `freshKeyAddr_`, drawn without a stake reference. */
    val freshKeyAddr: Imp[Bytes] = freshKey.map(keyAddress)

    /** `impAddNativeScript` of `RequireSignature` of a fresh key: the script and its hash. */
    val freshRequireSignature: Imp[(Node, Bytes)] = freshKey.flatMap { k =>
      val script = B.arr(B.uint(0), B.bytes(k.hash))
      val hash = B.scriptHash(0, B.encode(script))
      StateT(s => (s.copy(natives = s.natives.updated(hash, (script, k))), (script, hash)))
    }

    val getRoot: Imp[TxIn] = StateT.inspect(_.root)

    // ---- what a transaction needs ---------------------------------------------------------------

    private def addressOf(s: ImpState, in: TxIn): Option[Bytes] = s.utxo.get(in).map(_.address.raw)

    /** The hash of the script that `address` pays to, if it is a script's enterprise address. */
    private def scriptHashOf(address: Bytes): Option[Bytes] =
      Option
        .when(address.size == 29 && (address.value.head & 0xf0) == 0x70)(Bytes(address.value.tail))

    private def keyHashOf(address: Bytes): Option[Bytes] =
      Option
        .when(address.size == 29 && (address.value.head & 0xf0) == 0x60)(Bytes(address.value.tail))

    /** The scripts the inputs' outputs and the reference inputs' outputs provide. */
    private def referenced(s: ImpState, spec: Spec): Set[Bytes] =
      (spec.inputs ++ spec.refInputs).toVector
        .flatMap(s.utxo.get)
        .flatMap(_.scriptRef)
        .map(_.hash)
        .toSet

    /** `impNativeScriptsRequired`: the Imp's native scripts the spent inputs' addresses name. */
    private def nativesRequired(s: ImpState, spec: Spec): Map[Bytes, (Node, Key)] =
      spec.inputs.toVector
        .flatMap(addressOf(s, _))
        .flatMap(scriptHashOf)
        .flatMap(h => s.natives.get(h).map(h -> _))
        .toMap

    /** `impGetPlutusContexts` (`Alonzo/ImpTest.hs:175-186`, cardano-ledger f649f975): each purpose
      * of the transaction whose script is one of `scriptTestContexts`, with the script.
      */
    def contexts(s: ImpState, spec: Spec): Vector[(Purpose, TestScript)] =
      val spending = spec.inputs.toVector.flatMap(in =>
        addressOf(s, in)
          .flatMap(scriptHashOf)
          .flatMap(testScripts.get)
          .map(Purpose.Spending(in) -> _)
      )
      val minting =
        spec.mint.toVector.flatMap((p, _) => testScripts.get(p).map(Purpose.Minting(p) -> _))
      val certifying = spec.certs.zipWithIndex.flatMap((c, ix) =>
        c.script.flatMap(testScripts.get).map(Purpose.Certifying(ix) -> _)
      )
      val withdrawing = spec.withdrawals.flatMap((a, _) =>
        testScripts.get(Bytes(a.value.tail)).map(Purpose.Withdrawing(a) -> _)
      )
      spending ++ withdrawing ++ certifying ++ minting

    /** The keys whose signatures `spec` needs (`impWitsVKeyNeeded`, and `impNativeScriptKeyPairs`
      * for its native scripts).
      */
    private def signers(s: ImpState, spec: Spec): Vector[Key] =
      val payment = (spec.inputs ++ spec.collateral).toVector
        .flatMap(addressOf(s, _))
        .flatMap(keyHashOf)
        .flatMap(s.keys.get)
      (payment ++ nativesRequired(s, spec).values.map(_._2)).distinctBy(_.hash)

    private def utxoFor(s: ImpState, spec: Spec): UTxO[Conway] =
      val read = spec.inputs ++ spec.collateral ++ spec.refInputs
      ok(ConwayUTxO.fromStored(s.utxo.view.filterKeys(read).toMap))

    /** `ensureMinCoinTxOut` (`Tools.hs:87-88`, cardano-ledger f649f975),
      * `setMinCoinTxOutWith (>=)`: the coin raised to the least its size allows,
      * `(160 + size) * coinsPerUTxOByte`, until it holds (`babbageMinUTxOValue`).
      */
    def ensureMinCoin(o: Out): Out =
      val least = (160 + o.size) * stored(major).coinsPerUTxOByte
      if o.coin >= least then o else ensureMinCoin(o.copy(coin = least))

    /** `setMinCoinTxOut`, `setMinCoinTxOutWith (==)`: the coin set to exactly that least. */
    def setMinCoin(o: Out): Out =
      val least = (160 + o.size) * stored(major).coinsPerUTxOByte
      if o.coin == least then o else setMinCoin(o.copy(coin = least))

    // ---- the fixups -----------------------------------------------------------------------------

    /** The scripts of `spec`'s contexts each evaluated with the units its redeemer declares. */
    def evaluated(s: ImpState, t: Built): Vector[(PlutusWithContext, EvalResult)] =
      val read = bodies.allInputs(tx.body[TxLevel.Top](t.era))
      val utxo = ok(ConwayUTxO.fromStored(s.utxo.view.filterKeys(read).toMap))
      val stAnnTx =
        AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](slotToPosix, params, utxo, Map.empty, t.era)
      EraUTxO.conway
        .plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)(using EraUTxO.conway.txOf.bodyOf.top)
        .fold(e => fail(s"$e"), _.toVector)
        .map(pwc => pwc -> ScarabEvaluator.default.evaluate(PlutusWithContext.evalRequest(pwc)))

    /** `txWithMaxRedeemers` (`Alonzo/ImpTest.hs:242-255`, cardano-ledger f649f975): every context's
      * redeemer, its data with the parameters' most units a transaction may use, in place of the
      * redeemers it has.
      */
    private def withMaxRedeemers(s: ImpState, spec: Spec): Spec =
      spec.copy(redeemers = contexts(s, spec).map((p, t) => p -> (t.redeemer, maxTx)).toMap)

    /** `fixupFees` (`Shelley/ImpTest.hs:1152-1201`, cardano-ledger f649f975) of `spec` with its fee
      * at zero: the change, what it consumes less what it produces, to a fresh address, the least
      * fee of the transaction with it (`calcMinFeeTxNativeScriptWits`, its key witnesses those it
      * needs: the signatures of the same keys here, of the same size) raised by a tenth, rounded
      * up; the change less the fee kept if it is at least its output's least coin, else given to
      * the fee.
      */
    private def fixupFees(spec0: Spec): Imp[Spec] = freshKeyAddr.flatMap { addr =>
      StateT.inspect { s =>
        val spec = spec0.copy(fee = 0)
        val utxo = utxoFor(s, spec)
        val t0 = encode(spec, Vector.empty).era
        val body = tx.body[TxLevel.Top](t0)
        val consumed = vals
          .coin(
            EraUTxO.conway.getConsumedValue[TxLevel.Top](
              params,
              _ => Some(Coin(stored(major).keyDeposit)),
              utxo,
              body
            )(using
              EraUTxO.conway.txOf.bodyOf.top
            )
          )
          .value
        val produced =
          vals.coin(EraUTxO.conway.getProducedValue(params, _ => false, body)).value
        val changeBeforeFee = (consumed - produced).max(0)
        val withChange = spec.copy(outputs = spec.outputs :+ Out(addr, changeBeforeFee))
        val keys = signers(s, withChange)
        def minFee(sp: Spec): BigInt =
          EraUTxO.conway
            .getMinFeeTxUtxo[TxLevel.Top](params, encode(sp, keys).era, utxo)(using
              EraUTxO.conway.txOf.bodyOf.top
            )
            .value
        // `setMinFeeTxUtxo`: the fee set to the least until it holds
        def settled(sp: Spec): BigInt =
          val least = minFee(sp)
          if least == sp.fee then least else settled(sp.copy(fee = least))
        val fee0 = settled(withChange)
        val fee = (fee0 * 11 + 9) / 10
        val change = (changeBeforeFee - fee).max(0)
        val changeOut = Out(addr, change)
        if change >= ensureMinCoin(changeOut.copy(coin = 0)).coin then
          spec.copy(outputs = spec.outputs :+ changeOut, fee = fee)
        else spec.copy(fee = fee + change)
      }
    }

    /** `fixupRedeemers` (`Alonzo/ImpTest.hs:203-240`, cardano-ledger f649f975): each context's
      * redeemer the transaction has, kept; else its data with the units its script spends when the
      * redeemer declares the most units, or those most where it fails (`txWithMaxRedeemers`).
      */
    private def fixupRedeemers(s: ImpState, spec: Spec): Spec =
      val cs = contexts(s, spec)
      assert(cs.size <= 1, s"more than one Plutus purpose: $cs")
      val max = withMaxRedeemers(s, spec)
      val estimated = cs.headOption.flatMap { (p, t) =>
        evaluated(s, encode(max, Vector.empty)) match
          case Vector((_, EvalResult.Success(used, _))) => Some(p -> (t.redeemer, used))
          case Vector((_, _: EvalResult.Failure))       => None
          case other                                    => fail(s"not one script: $other")
      }.toMap
      spec.copy(redeemers = max.redeemers ++ estimated ++ spec.redeemers)

    /** `fixupPPHash` (`Alonzo/ImpTest.hs:318-327`, cardano-ledger f649f975): the integrity hash of
      * the languages of the transaction's Plutus scripts.
      */
    private def fixupPPHash(s: ImpState, spec: Spec): Spec =
      val langs = contexts(s, spec).map(_._2.lang).toSet
      val integrity = ScriptIntegrity
        .mkScriptIntegrity[Conway, TxLevel.Top](params, encode(spec, Vector.empty).era, langs)
        .map(ScriptIntegrity.hashScriptIntegrity(_))
      spec.copy(integrity = integrity)

    /** `makeCollateralInput` (`Alonzo/ImpTest.hs:144-149`, cardano-ledger f649f975): 30 ada sent to
      * a fresh address by a transaction the Imp submits with its fixups, its first output.
      */
    private val makeCollateralInput: Imp[TxIn] =
      freshKeyAddr.flatMap(a => sendCoinTo(a, 30000000))

    /** `babbageFixupTx` (`Babbage/ImpTest.hs:77-91`, cardano-ledger f649f975): the native scripts
      * the inputs need and the outputs do not provide, a collateral input where it has a Plutus
      * script and none, the root input, the Plutus scripts not provided, the outputs' and inputs'
      * datums, the outputs' least coins, the collateral return's, the fee and change, the
      * redeemers, the integrity hash; then the signatures (`updateAddrTxWits`).
      */
    def fixupTx(spec0: Spec): Imp[Built] =
      for
        s0 <- StateT.get[ImpState]
        withNatives = spec0.copy(nativeScripts =
          spec0.nativeScripts ++ nativesRequired(s0, spec0).view
            .filterKeys(h => !referenced(s0, spec0).contains(h))
            .mapValues(_._1)
        )
        collateral <-
          if withNatives.collateral.isEmpty && contexts(s0, withNatives).nonEmpty then
            makeCollateralInput.map(in => SortedSet(in))
          else StateT.pure[ImpState, SortedSet[TxIn]](withNatives.collateral)
        s <- StateT.get[ImpState]
        withRoot = withNatives.copy(collateral = collateral, inputs = withNatives.inputs + s.root)
        provided = referenced(s, withRoot) ++ withRoot.plutusScripts.keySet
        cs = contexts(s, withRoot)
        withScripts = withRoot.copy(plutusScripts = withRoot.plutusScripts ++ cs.collect {
          case (_, t) if !provided.contains(B.scriptHash(tagOf(t.lang), t.script)) =>
            B.scriptHash(tagOf(t.lang), t.script) -> t
        })
        withOutDatums = withScripts.copy(outputs = withScripts.outputs.map { o =>
          scriptHashOf(o.address).flatMap(testScripts.get).flatMap(_.datum) match
            case Some(d) if o.datumHash.isEmpty => o.copy(datumHash = Some(B.dataHash(d)))
            case _                              => o
        })
        withDatums = withOutDatums.copy(datums = withOutDatums.datums ++ cs.collect {
          case (Purpose.Spending(in), t)
              if s.utxo
                .get(in)
                .exists(_.datum match
                  case flat.Datum.Hash(h) => t.datum.exists(d => B.dataHash(d) == h)
                  case _ => false) =>
            t.datum.getOrElse(fail("no datum"))
        })
        withCoins = withDatums.copy(
          outputs = withDatums.outputs.map(o => if o.coin == 0 then ensureMinCoin(o) else o),
          collateralReturn = withDatums.collateralReturn.map(ensureMinCoin)
        )
        feed <- fixupFees(withMaxRedeemers(s, withCoins))
        s2 <- StateT.get[ImpState]
        fixed = fixupPPHash(s2, fixupRedeemers(s2, feed.copy(redeemers = withCoins.redeemers)))
      yield encode(fixed, signers(s2, fixed))

    // ---- LEDGER and submission ------------------------------------------------------------------

    /** LEDGER with every check on `t` from `ls`, at the block's slot (`trySubmitTx`,
      * `Shelley/ImpTest.hs:1254-1300`, cardano-ledger f649f975).
      */
    def ledgerOn(t: Built, ls: LedgerState[Conway]) =
      val era = t.era
      val read = bodies.allInputs(tx.body[TxLevel.Top](era))
      val utxo = ok(ConwayUTxO.fromStored(ls.utxo.utxo.utxo.toMap.view.filterKeys(read).toMap))
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
        ls,
        ScarabEvaluator.default,
        flat.Verdicts.Direct,
        ScarabEvaluator.defaultDecoder
      )

    /** `t` submitted without fixups (`submitTx` under `withNoFixup`): admitted by LEDGER, the UTxO
      * set after it as Conway's `expectTxSuccess` requires (`impBabbageExpectTxSuccess`,
      * `Babbage/ImpTest.hs:103-121`, after `impAlonzoExpectTxSuccess`, cardano-ledger f649f975),
      * the root moved to its last output where that is in the UTxO set
      * (`Shelley/ImpTest.hs:1296-1312`), and `t` recorded where the Imp records.
      */
    def submitBuilt(t: Built): Imp[Built] = StateT { s =>
      val after = ledgerOn(t, s.ls) match
        case Right(Right((ls, _))) => ls
        case other                 => fail(s"not admitted: $other")
      val body = tx.body[TxLevel.Top](t.era)
      val (inputs, collateral) = (bodies.inputs[TxLevel.Top](body), bodies.collateralInputs(body))
      val now = after.utxo.utxo.utxo.toMap
      def storedOf(utxo: UTxO[Conway]) =
        utxo.unUTxO.view.mapValues(StoredTxOut.conway.toStored).toMap
      val txouts = UTxOs.txouts[Conway, TxLevel.Top](body)
      val returns = BabbageUtxo.collOuts[Conway](body)
      tx.isValid(t.era) match
        case IsPhase2Valid.Phase2Valid =>
          inputs.foreach(in => assert(!now.contains(in), s"$in spent"))
          (collateral -- inputs).foreach(in => assert(now.contains(in), s"$in kept"))
          storedOf(txouts).foreach((in, o) => assertEquals(now.get(in), Some(o), s"$in"))
          returns.unUTxO.keys.foreach(in => assert(!now.contains(in), s"$in not created"))
        case IsPhase2Valid.Phase2Invalid =>
          (inputs -- collateral).foreach(in => assert(now.contains(in), s"$in kept"))
          collateral.foreach(in => assert(!now.contains(in), s"$in spent"))
          txouts.unUTxO.keys.foreach(in => assert(!now.contains(in), s"$in not created"))
          storedOf(returns).foreach((in, o) => assertEquals(now.get(in), Some(o), s"$in"))
      val outs = bodies.outputs[TxLevel.Top](body).size
      val assumed = t.in(outs - 1)
      val root =
        if outs > 0 && now.contains(assumed) then assumed
        else if now.contains(s.root) then s.root
        else fail("the root is not in the UTxO set")
      (s.copy(ls = after, root = root, recorded = s.recorded.map(_ :+ t)), t)
    }

    /** `submitTx`: `spec` fixed up and submitted. */
    def submitTx(spec: Spec): Imp[Built] = fixupTx(spec).flatMap(submitBuilt)

    /** `trySubmitTx` of `spec` LEDGER refuses: its failures and the fixed-up transaction, the state
      * as the fixups leave it.
      */
    def tryFailing(spec: Spec): Imp[(Vector[LedgerPredFailure[Conway]], Built)] =
      fixupTx(spec).flatMap(t =>
        StateT.inspect(s =>
          ledgerOn(t, s.ls) match
            case Right(Left(fs)) => (fs.toChain.toVector, t)
            case other           => fail(s"not refused: $other")
        )
      )

    /** `sendCoinTo`: `coin` to `address`, the first output of a transaction the Imp submits. */
    def sendCoinTo(address: Bytes, coin: BigInt): Imp[TxIn] =
      submitTx(Spec(outputs = Vector(Out(address, coin)))).map(_.in(0))

    /** `produceScript` (`Shelley/ImpTest.hs:2166-2176`, cardano-ledger f649f975): an output of
      * nothing to `hash` 's script address, its first.
      */
    def produceScript(hash: Bytes): Imp[TxIn] =
      submitTx(Spec(outputs = Vector(Out(scriptAddress(hash), 0)))).map(_.in(0))

    /** `produceRefScriptsTx` (`Babbage/ImpTest.hs:143-155`, cardano-ledger f649f975): an output to
      * a fresh address holding `script` (its language's tag and itself) as its reference script, of
      * exactly its least coin.
      */
    def produceRefScriptsTx(tag: Int, script: Node): Imp[Built] =
      freshKeyAddr.flatMap(a =>
        submitTx(Spec(outputs = Vector(setMinCoin(Out(a, 0, refScript = Some((tag, script)))))))
      )

    /** `mkTxWithRefInputs` (`Babbage/ImpTest.hs:157-166`, cardano-ledger f649f975). */
    def withRefInputs(in: TxIn, refIns: Vector[TxIn]): Spec =
      Spec(inputs = SortedSet(in), refInputs = SortedSet.from(refIns))

    /** `impScriptPredicateFailure` (`Alonzo/ImpTest.hs:490-507`, cardano-ledger f649f975) injected
      * into LEDGER: the fixed-up transaction refused for the scripts that fail, each described as
      * `scriptFailureToFailureDescription` gives it, through Conway's UTXOS, UTXO, UTXOW and
      * LEDGER.
      */
    def scriptPredicateFailure(s: ImpState, t: Built): LedgerPredFailure[Conway] =
      val failures = NonEmptyList
        .fromList(evaluated(s, t).toList.collect { case (pwc, EvalResult.Failure(reason, _, _)) =>
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
