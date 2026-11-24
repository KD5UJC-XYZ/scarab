// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.{Id, Monad}
import cats.data.{NonEmptySet, State as StateT}
import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.state.{Conway, Hash32, LedgerState}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalResult, ExUnits, PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** The reference's BBODY cases that run for Conway, each with its exact expectation, at each of
  * Conway's protocol versions, made as the Imp makes them ([[ConwayImp]]: every transaction through
  * its fixups, the root output threaded through every submission, each submission admitted by
  * LEDGER and its effect on the UTxO set checked, then the state before the block restored and the
  * block applied), on blocks made only by `ConwayBlock.decode` of bytes whose header commits to its
  * body, judged by the node's own era run (`ConwayBlockRun`), serially and prepared for its bounded
  * parallel evaluation ([[ConwayPlanned.check]]: the plan's jobs run in reverse and with none run,
  * and at one, two, four and eight workers by `runner`). The effect `F` is the runner's: the
  * ledger's suite runs the cases on threads (`ConwayBbodyImpSuite`, `Id`), the node's through the
  * node's own scheduler (`ConwayBbodyImpWorkersSuite`, `IO`).
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
  *     execution units past `maxBlockExUnits` (`:91`);
  *   - Conway's (`Conway/Imp/BbodySpec.hs`): the block's reference scripts past
  *     `maxRefScriptSizePerBlock`, held before the block (`:43`) and, from version 11
  *     (`whenMajorVersionAtLeast @11`), created in it (`:86`, which at 9 and 10 runs nothing, and
  *     so runs here at 11 alone); the function `totalRefScriptSizeInBlock` on a block's
  *     transactions as they are applied, and on every prefix of them before (`:122`), and with a
  *     collateral return holding the script, its transaction first refused flagged valid with
  *     exactly its script's failure (`:195`, a `disableInConformanceIt`, an `it` outside the
  *     conformance run, `Shelley/ImpTest.hs:773-781`). The function's cases make no block upstream;
  *     here the transactions they submit are also applied as a block by the node's run, which
  *     admits it.
  *
  * Shelley's (`Shelley/Imp/BbodySpec.hs`) are not in f649f975's tree: Shelley's `Imp.hs` runs no
  * BBODY spec there, and no test of that tree names `WrongBlockBodySizeBBODY` or
  * `InvalidBodyHashBBODY`. They are ported from cardano-ledger 9df9f98c, its first pin that has
  * them, as Conway's driver runs them there (`Shelley/Imp/BbodySpec.hs:28`, `:43`, `:58`), against
  * f649f975's rule: `Shelley/Rules/Bbody.hs` differs between the two pins only by the signal's name
  * (`BbodySignal`, `TPraosBbodySignal`) and an `Ord` instance of the failure. Neither pin has a
  * case of `HeaderProtVerTooHigh`, which Conway's BBODY checks only on mainnet below version 12
  * (`Conway/Rules/Bbody.hs:294-312`, f649f975), and the Imp's network is a testnet (`sgNetworkId =
  * Testnet`, `Shelley/ImpTest.hs:823`): it has no oracle case here.
  *
  * Incomplete (CNF-2):
  *   - the draws, each fixed: as [[ConwayImp]] fixes its own; of `:46`, the minted token's name and
  *     one token (`mkTokenMintingTx`, `Mary/ImpTest.hs:44-54`), and the certificates' forms, the
  *     registration with its deposit and the unregistration with its refund (`conwayGenRegTxCert`,
  *     `conwayGenUnRegTxCert`, `Conway/ImpTest.hs:423-452`, which draw them or the legacy forms);
  *     of Shelley's `:43`, the header's hash, nine bytes of 9; of `:43` and `:86`, the counts
  *     (`genNumAdditionsExceeding`), the largest its generator allows;
  *   - the Imp's slot: the block's and LEDGER's slot is the recorded block's, where the Imp's is
  *     the first of epoch 900 (`impEraStartEpochNo`); no case reads it;
  *   - `tryTxsInBlock'` and `trySubmitTx` round-trip each failure through its CBOR at the era's
  *     versions (`roundTripEraExpectation`, `Shelley/ImpTest.hs:1288, 1530`): the node has no
  *     decoder of Conway's failures and no encoder of its BBODY's, and no reference bytes of these
  *     failures are recorded, so no case does.
  */
trait ConwayBbodyImpCases[F[_]](using F: Monad[F]) extends munit.Assertions:
  import ConwayImp.*

  /** How a prepared block is run with a number of workers ([[ConwayPlanned.check]]). */
  protected def runner: ConwayPlanned.Runner[F]

  /** A case, run by the suite as its test of `name`. */
  protected def register(name: String)(body: => F[Unit]): Unit

  private val tx = EraTx.conway
  private val bodies = EraTxBody.conway
  private given bodies.Level[TxLevel.Top] = bodies.top

  private def checked(
      name: String,
      raw: Bytes,
      ls: LedgerState[Conway],
      params: PParams[Conway],
      evaluator: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[Id] = ScarabEvaluator.default
  ): F[ConwayPlanned.Checked] =
    ConwayPlanned.check[F](name, evaluator, clock, runner)(program(raw, ls, params))

  /** The failures the node's run refuses the block `raw` for, the rules having run to its end
    * (`Refusal.Failed`): a defect, a stopped run or an admitted block fails the test.
    */
  private def refused(
      name: String,
      raw: Bytes,
      ls: LedgerState[Conway],
      params: PParams[Conway]
  ): F[Vector[BbodyPredFailure[Conway]]] =
    checked(name, raw, ls, params).map(_.serial match
      case Right(Left(ConwayBlockRun.Refusal.Failed(fs))) => fs.toChain.toVector
      case other => fail(s"$name: not refused by the rules alone: $other"))

  /** The node's run admits the block `raw`. */
  private def admitted(
      name: String,
      raw: Bytes,
      ls: LedgerState[Conway],
      params: PParams[Conway]
  ): F[ConwayPlanned.Checked] =
    checked(name, raw, ls, params).map { c =>
      c.serial match
        case Right(Right(_)) => c
        case other           => fail(s"$name: not admitted: $other")
    }

  private def into[A](f: A)(using i: InjectBbodyFailure[A, Conway]): BbodyPredFailure[Conway] =
    i.injectFailure(f)

  /** `impRecordSubmittedTxs` of `act` in `withTxsInBlockEither` (`Shelley/ImpTest.hs:1455-1477`,
    * cardano-ledger f649f975): the state before `act`, the transactions it submitted, in order, and
    * what it gave; the state then restored to the one before.
    */
  private def recorded[A](act: Imp[A]): Imp[(ImpState, Vector[Built], A)] =
    for
      before <- StateT.get[ImpState]
      _ <- StateT.set(before.copy(recorded = Some(Vector.empty)))
      a <- act
      after <- StateT.get[ImpState]
      _ <- StateT.set(before)
    yield (before, after.recorded.getOrElse(Vector.empty), a)

  private def run[A](at: At)(act: Imp[A]): A = act.runA(at.init).value

  /** A refusal of UTXOS's for failing scripts, comparable: the flag, and each failure's context
    * whole (the script and its hash, its arguments, units and model) with its machine's error, the
    * term it is about (`ErrorWithCause`) and its trace, or the failure as it is.
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
            Right((cek.error, cek.cause.map(_.term), logs))
          case other => Left(other)
        cause -> pwc
      }
    case other => fail(s"not a refusal of failing scripts: $other")

  /** `submitPhase2Invalid` (`Alonzo/ImpTest.hs:517-532`, cardano-ledger f649f975) of `spec`: fixed
    * up and refused flagged valid with exactly its scripts' failure (`impScriptPredicateFailure`),
    * then submitted with only its flag set invalid, without fixups.
    */
  private def submitPhase2Invalid(at: At)(spec: Spec): Imp[Built] =
    assert(spec.valid)
    for
      (refusal, fixed) <- at.tryFailing(spec)
      s <- StateT.get[ImpState]
      _ = assertEquals(
        refusal.map(comparable),
        Vector(comparable(at.scriptPredicateFailure(s, fixed)))
      )
      t <- at.submitBuilt(fixed.copy(valid = false))
    yield t

  // ---- the control ------------------------------------------------------------------------------

  versions.foreach { v =>
    register(
      s"version $v: the control: BBODY admits a block of one transaction as the Imp makes it"
    ) {
      val at = At(v)
      val (before, txs, _) = run(at)(recorded(at.submitTx(Spec())))
      admitted(s"control at $v", blockOf(v, txs), before.ls, at.params).void
    }
  }

  // ---- Shelley's: `Shelley/Imp/BbodySpec.hs`, cardano-ledger 9df9f98c ---------------------------

  versions.foreach { v =>
    register(s"version $v: WrongBlockBodySizeBBODY (Shelley/Imp/BbodySpec.hs:28, 9df9f98c)") {
      // `blockBodySizeBlockHeaderL %~ (+ 1)`: supplied the body's size, expected the header's; the
      // transaction `submitAnyTx_ $ mkBasicTx mkBasicTxBody`, at the top level, the one Conway has
      val at = At(v)
      val (before, txs, _) = run(at)(recorded(at.submitTx(Spec())))
      val size = EraBlockBody.conway.blockBodySize(
        EraPParams.conway.ppProtocolVersionL.get(at.params),
        decoded(blockOf(v, txs)).blockBody
      )
      val raw = blockOf(v, txs, _.updated(6, encoded(Value.UInt(BigInt(size) + 1))))
      refused(s"WrongBlockBodySizeBBODY at $v", raw, before.ls, at.params).map(fs =>
        assertEquals(
          fs,
          Vector(
            into(
              new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](Mismatch(size, size + 1))
            )
          )
        )
      )
    }

    register(s"version $v: InvalidBodyHashBBODY (Shelley/Imp/BbodySpec.hs:43, 9df9f98c)") {
      // `blockBodyHashBlockHeaderL .~ invalidBodyHash` of an `arbitrary` hash: supplied the body's
      // hash, expected the header's
      val at = At(v)
      val (before, txs, _) = run(at)(recorded(at.submitTx(Spec())))
      val invalid = Hash32
        .fromBytes(Bytes.fromArray(Array.fill(32)(9.toByte)))
        .fold(e => fail(s"$e"), identity)
      val raw = blockOf(v, txs, _.updated(7, encoded(Value.ByteString(invalid.bytes))))
      refused(s"InvalidBodyHashBBODY at $v", raw, before.ls, at.params).map(fs =>
        assertEquals(
          fs,
          Vector(
            into(
              new ShelleyBbodyPredFailure.InvalidBodyHashBBODY[Conway](
                Mismatch(EraBlockBody.conway.hashBlockBody(decoded(raw).blockBody), invalid)
              )
            )
          )
        )
      )
    }

    register(s"version $v: LedgersFailure (Shelley/Imp/BbodySpec.hs:58, 9df9f98c)") {
      // `modifyBlockBody protVer $ txSeqBlockBodyL %~ \txs -> txs <> txs`, the header committing to
      // the body: among the failures (`withTxsInModifiedFailingSubsetBlockM`), the block's inputs
      // as bad
      val at = At(v)
      val (before, txs, _) = run(at)(recorded(at.submitTx(Spec())))
      val raw = blockOf(v, txs ++ txs)
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
      refused(s"LedgersFailure at $v", raw, before.ls, at.params).map(fs =>
        assertEquals(fs.toSet.intersect(expected), expected)
      )
    }
  }

  // ---- Alonzo's: `Alonzo/Imp/BbodySpec.hs`, for each language -----------------------------------

  register("the reference's example scripts hash as their comments list") {
    examples.foreach { case ((e, lang), b) =>
      assertEquals(B.scriptHash(tagOf(lang), b.script), b.hash, s"$e $lang (Examples.hs:${b.line})")
    }
    assertEquals(example(Example.PurposeIsWellformedNoDatum, PlutusLanguage.V2).script.size, 440)
    assertEquals(examples.size, 3 * Example.values.size)
    F.unit
  }

  register("the Imp's parameters: every one the cases read, as the Imp's genesis sets it") {
    // what each case reads of the parameters (`Shelley/ImpTest.hs:832-847`,
    // `Alonzo/ImpTest.hs:422-446`, `Conway/ImpTest.hs:270-301`, cardano-ledger f649f975)
    versions.foreach { v =>
      val p = pp(v)
      assertEquals(EraPParams.conway.ppProtocolVersionL.get(p).pvMajor.value, v)
      assertEquals(
        AlonzoEraPParams.ppMaxTxExUnitsL.get(p).units,
        ExUnits(10000000, BigInt(10000000000L))
      )
      assertEquals(
        AlonzoEraPParams.ppMaxBlockExUnitsL.get(p).units,
        ExUnits(200000000, BigInt(200000000000L))
      )
      assertEquals(EraPParams.ppKeyDepositCompactL[Conway].get(p).toBigInt, BigInt(2000000))
      assertEquals(
        ConwayEraPParams.conway.ppMaxRefScriptSizePerBlockG.get(p).value.toLong,
        1048576L
      )
      assertEquals(ConwayEraPParams.conway.ppMaxRefScriptSizePerTxG.get(p).value.toLong, 204800L)
    }
    // the extraction's sizes, of the parameters of each language plutus 1.70.0.0 names
    assertEquals(testingCostModels.view.mapValues(_.size).toMap, Map(0 -> 208, 1 -> 217, 2 -> 361))
    F.unit
  }

  languages.foreach { (lang, _, _) =>
    val succeeds = example(Example.AlwaysSucceedsWithDatum, lang)
    versions.foreach { v =>
      register(s"version $v, $lang: enforces ppMaxBlockExUnits (Alonzo/Imp/BbodySpec.hs:91)") {
        val at = At(v)
        val maxBlockUnits = AlonzoEraPParams.ppMaxBlockExUnitsL.get(at.params).units
        val maxTxUnits = AlonzoEraPParams.ppMaxTxExUnitsL.get(at.params).units
        val txCount =
          1 + (maxBlockUnits.mem / maxTxUnits.mem).max(maxBlockUnits.steps / maxTxUnits.steps)
        assertEquals(txCount, BigInt(21))
        val mismatch = Mismatch[Relation.RelLTEQ, ExUnits](
          ExUnits(txCount * maxTxUnits.mem, txCount * maxTxUnits.steps),
          maxBlockUnits
        )
        // `txIns <- replicateM txCount $ produceScript alwaysSucceedsWithDatumHash`, then the block
        // of the spends, `(Data (P.I 0), maxTxUnits)` at `mkSpendingPurpose (AsIx 0)`, each after
        // the collateral its fixups submit (`addCollateralInput`, `Alonzo/ImpTest.hs:151-164`,
        // cardano-ledger f649f975)
        val (before, txs, _) = run(at)(
          (0 until txCount.toInt).toVector
            .traverse(_ => at.produceScript(succeeds.hash))
            .flatMap(txIns =>
              recorded(txIns.traverse { in =>
                at.submitTx(
                  Spec(
                    inputs = SortedSet(in),
                    redeemers = Map(Purpose.Spending(in) -> (B.uint(0), maxTxUnits))
                  )
                )
              })
            )
        )
        assertEquals(txs.size, 2 * txCount.toInt)
        val raw = blockOf(v, txs)
        // past the block's units the node's run is never prepared (#85): not worthwhile, and its
        // plan has no job; at every worker count it evaluates exactly the scripts the serial run
        // evaluates
        val body = program(raw, before.ls, at.params)(ScarabEvaluator.default, None)
        assert(!body.worthwhile)
        assertEquals(body.prepare.jobs, Vector.empty)
        val serialRuns = CountingEvaluator()
        val _ = program(raw, before.ls, at.params)(serialRuns, None).serial
        assertEquals(serialRuns.count, txCount.toInt)
        for
          fs <- refused(s"ppMaxBlockExUnits at $v, $lang", raw, before.ls, at.params)
          _ = assertEquals(
            fs,
            Vector(into(new AlonzoBbodyPredFailure.TooManyExUnits[Conway](mismatch)))
          )
          _ <- ConwayPlanned.workers.traverse_ { n =>
            val runs = CountingEvaluator()
            runner(program(raw, before.ls, at.params)(runs, None), n)
              .map(_ => assertEquals(runs.count, serialRuns.count, s"$n workers"))
          }
        yield ()
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

  languages.foreach { (lang, _, _) =>
    versions.foreach { v =>
      register(
        s"version $v, $lang: succeeds with eight Plutus scripts (Alonzo/Imp/BbodySpec.hs:46)"
      ) {
        val at = At(v)
        val maxExUnits = AlonzoEraPParams.ppMaxTxExUnitsL.get(at.params).units
        val deposit = EraPParams.ppKeyDepositCompactL[Conway].get(at.params).toBigInt
        val even = example(Example.EvenRedeemerNoDatum, lang)
        val failsWithDatum = example(Example.AlwaysFailsWithDatum, lang)
        val succeedsWithDatum = example(Example.AlwaysSucceedsWithDatum, lang)
        val failsNoDatum = example(Example.AlwaysFailsNoDatum, lang)
        val succeedsNoDatum = example(Example.AlwaysSucceedsNoDatum, lang)
        // `ScriptHashObj evenRedeemerNoDatumHash`, and its reward account on the testnet
        val credential = B.arr(B.uint(1), B.bytes(even.hash))
        val accountAddress = Bytes(0xf0.toByte +: even.hash.value)
        // `registerStakeCredential` (`Shelley/ImpTest.hs:1915-1929`, cardano-ledger f649f975):
        // `RegDepositTxCert` with the parameters' deposit, one of the two forms
        // `conwayGenRegTxCert` draws, which the script witnesses (`getScriptWitnessConwayTxCert`,
        // `Conway/TxCert.hs:763-771`, cardano-ledger f649f975); `genUnRegTxCert`:
        // `UnRegDepositTxCert` with the account's deposit, one of the two forms
        // `conwayGenUnRegTxCert` draws
        val regCert = Cert(B.arr(B.uint(7), credential, B.uint(deposit)), Some(even.hash))
        val txCert = Cert(B.arr(B.uint(8), credential, B.uint(deposit)), Some(even.hash))
        // `mkTokenMintingTx` (`Mary/ImpTest.hs:44-54`, cardano-ledger f649f975): its token to a
        // fresh address, of no coin before the fixups
        val name = Bytes.fromArray("eight".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        def mintingTx(policy: Bytes): Imp[Spec] = at.freshKeyAddr.map { addr =>
          val assets = B.cmap(B.bytes(name) -> B.uint(1))
          Spec(
            mint = Some(policy -> assets),
            outputs = Vector(Out(addr, 0, assets = Some(B.cmap(B.bytes(policy) -> assets))))
          )
        }
        // `dex i = (Data $ P.I i, maxExUnits)`
        def dex(i: Int) = (B.uint(i), maxExUnits)
        val withdrawal = Vector(accountAddress -> BigInt(0))
        val block: Imp[Unit] =
          for
            // "notValidatingTx" and "validatingTx" (`Alonzo/Imp/BbodySpec.hs:51-56`)
            failingIn <- at.produceScript(failsWithDatum.hash)
            _ <- submitPhase2Invalid(at)(Spec(inputs = SortedSet(failingIn)))
            succeedingIn <- at.produceScript(succeedsWithDatum.hash)
            _ <- at.submitTx(Spec(inputs = SortedSet(succeedingIn)))
            // "notValidatingTxWithMint" and "validatingTxWithMint" (`:58-61`)
            failingMint <- mintingTx(failsNoDatum.hash)
            _ <- submitPhase2Invalid(at)(failingMint)
            succeedingMint <- mintingTx(succeedsNoDatum.hash)
            _ <- at.submitTx(succeedingMint)
            // "notValidatingTxWithWithdrawal" and "validatingTxWithWithdrawal" (`:69-78`)
            _ <- submitPhase2Invalid(at)(
              Spec(
                withdrawals = withdrawal,
                redeemers = Map(Purpose.Withdrawing(accountAddress) -> dex(1))
              )
            )
            _ <- at.submitTx(
              Spec(
                withdrawals = withdrawal,
                redeemers = Map(Purpose.Withdrawing(accountAddress) -> dex(0))
              )
            )
            // "notValidatingTxWithCert" and "validatingTxWithCert" (`:80-89`)
            _ <- submitPhase2Invalid(at)(
              Spec(certs = Vector(txCert), redeemers = Map(Purpose.Certifying(0) -> dex(1)))
            )
            _ <- at.submitTx(
              Spec(certs = Vector(txCert), redeemers = Map(Purpose.Certifying(0) -> dex(0)))
            )
          yield ()
        val (before, txs, _) =
          run(at)(at.submitTx(Spec(certs = Vector(regCert))) *> recorded(block))
        // the four script transactions flagged invalid, each after its collateral's producer, and
        // the four valid ones, each after its own
        assertEquals(txs.count(!_.valid), 4)
        val scripted =
          txs.filter(t => tx.witsOf.rdmrsTxWits(tx.wits[TxLevel.Top](t.era)).entries.nonEmpty)
        assertEquals(scripted.size, 8)
        admitted(s"eight Plutus scripts at $v, $lang", blockOf(v, txs), before.ls, at.params).map {
          c =>
            // prepared, its eight script transactions are the plan's eight jobs, each landed with
            // the verdict its flag announces, to the serial run's result
            assert(c.worthwhile)
            assertEquals(c.jobs, 8)
            // observed for the node's phase-2 observer, each script transaction in the block's
            // order, its one script passed where it is flagged valid and failed where flagged
            // invalid
            assertEquals(c.observations.map(_.tx.body.id), scripted.map(_.id))
            c.observations.foreach(o =>
              o.outcome match
                case Right(xyz.kd5ujc.scarab.ledger.Phase2.Outcome.Passed(report)) =>
                  assert(o.tx.isValid, s"$o")
                  assertEquals(report.scripts, 1)
                case Right(xyz.kd5ujc.scarab.ledger.Phase2.Outcome.Failed(report, _)) =>
                  assert(!o.tx.isValid, s"$o")
                  assertEquals(report.scripts, 1)
                case other => fail(s"not one script ran: $other")
            )
        }
      }
    }
  }

  // ---- Conway's: `Conway/Imp/BbodySpec.hs` ------------------------------------------------------

  private lazy val purposeIsWellformedNoDatumV2: Binary =
    example(Example.PurposeIsWellformedNoDatum, PlutusLanguage.V2)

  /** `genNumAdditionsExceeding sc maxSingle toExceed` (`Conway/Imp/BbodySpec.hs:244-252`,
    * cardano-ledger f649f975) with the largest value its generator allows drawn each time: counts
    * whose multiples of `sc` sum past `toExceed`, each multiple at most `maxSingle`.
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

  private def refScript: Node = B.bytes(purposeIsWellformedNoDatumV2.script)

  private def tooBig(scriptSize: Long, counts: Vector[Long], limit: Long) =
    Vector(
      into(
        new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Conway](
          Mismatch(scriptSize * counts.sum, limit)
        )
      )
    )

  versions.foreach { v =>
    register(s"version $v: BodyRefScriptsSizeTooBig (Conway/Imp/BbodySpec.hs:43)") {
      // for each count, 100 ada sent to a fresh address and as many reference scripts produced
      // (`produceRefScript`), before the block; the block of the transactions spending each sent
      // output and referencing its scripts (`mkTxWithRefInputs`), each submitted with its fixups
      // (`submitFailingBlock`)
      val at = At(v)
      val (scriptSize, limit, counts) = scriptCounts(at.params)
      val prepare: Imp[Vector[Spec]] = counts.traverse { n =>
        for
          txIn <- at.freshKeyAddr.flatMap(a => at.sendCoinTo(a, 100000000))
          refIns <- (0 until n.toInt).toVector.traverse(_ =>
            at.produceRefScriptsTx(2, refScript).map(_.in(0))
          )
        yield at.withRefInputs(txIn, refIns)
      }
      val (before, txs, _) =
        run(at)(prepare.flatMap(specs => recorded(specs.traverse(at.submitTx))))
      assertEquals(txs.size, counts.size)
      refused(s"BodyRefScriptsSizeTooBig at $v", blockOf(v, txs), before.ls, at.params).map(fs =>
        assertEquals(fs, tooBig(scriptSize, counts, limit))
      )
    }
  }

  register(
    "version 11: BodyRefScriptsSizeTooBig with reference scripts in the same block (Conway/Imp/BbodySpec.hs:86)"
  ) {
    // `whenMajorVersionAtLeast @11`: at 9 and 10 the case runs nothing. In the block, for each
    // count, as many reference scripts produced (`produceRefScript`), then the transaction
    // referencing them (`mkBasicTx mkBasicTxBody & referenceInputsTxBodyL .~ refIns`)
    val v = 11
    val at = At(v)
    val (scriptSize, limit, counts) = scriptCounts(at.params)
    val (before, txs, _) = run(at)(recorded(counts.traverse_ { n =>
      (0 until n.toInt).toVector
        .traverse(_ => at.produceRefScriptsTx(2, refScript).map(_.in(0)))
        .flatMap(refIns => at.submitTx(Spec(refInputs = SortedSet.from(refIns))))
    }))
    assertEquals(txs.size, counts.size + counts.sum.toInt)
    refused(
      "BodyRefScriptsSizeTooBig in the same block at 11",
      blockOf(v, txs),
      before.ls,
      at.params
    )
      .map(fs => assertEquals(fs, tooBig(scriptSize, counts, limit)))
  }

  // ---- `totalRefScriptSizeInBlock` on transactions as they are applied --------------------------

  private def total(at: At, txs: Vector[Built], s: ImpState): Long =
    ConwayBbody.totalRefScriptSizeInBlock[Conway](
      EraPParams.conway.ppProtocolVersionL.get(at.params),
      txs.map(_.era),
      ConwayUTxO.fromStored(s.utxo).fold(e => fail(s"$e"), identity)
    )

  /** `totalRefScriptSizeInBlock protVer txs` on the Imp's UTxO set now. */
  private def totalNow(at: At, txs: Vector[Built]): Imp[Long] = StateT.inspect(total(at, txs, _))

  versions.foreach { v =>
    register(s"version $v: totalRefScriptSizeInBlock (Conway/Imp/BbodySpec.hs:122)") {
      val at = At(v)
      val sizes: Imp[(Long, ImpState, Vector[Built], Vector[(Built, Long)])] =
        for
          (script, hash) <- at.freshRequireSignature
          scriptSize = B.encode(script).size.toLong
          scriptSpendIn <- at.produceScript(hash)
          scriptSpendIn2 <- at.produceScript(hash)
          // `simulateThenRestore`; `mkTxWithExpectedSize`: each transaction's size against the UTxO
          // set it leaves
          (before, submitted, withSizes) <- recorded(
            for
              refScriptTx1 <- at.produceRefScriptsTx(0, script)
              s1 <- totalNow(at, Vector(refScriptTx1))
              refScriptTx1In = refScriptTx1.in(0)
              refScriptTx2 <- at.produceRefScriptsTx(0, script)
              s2 <- totalNow(at, Vector(refScriptTx2))
              spendScriptWithRefScriptTx <-
                at.submitTx(at.withRefInputs(scriptSpendIn, Vector(refScriptTx1In)))
              s3 <- totalNow(at, Vector(spendScriptWithRefScriptTx))
              spendScriptWithTwoRefScriptsTx <- at.submitTx(
                at.withRefInputs(scriptSpendIn2, Vector(refScriptTx1In, refScriptTx2.in(0)))
              )
              s4 <- totalNow(at, Vector(spendScriptWithTwoRefScriptsTx))
              rootIn <- at.getRoot
              spendRootUtxoTx <- at.submitTx(at.withRefInputs(rootIn, Vector(refScriptTx1In)))
              s5 <- totalNow(at, Vector(spendRootUtxoTx))
              // not submitted: applying it removes the reference script from the UTxO set
              spendRefScriptTx <-
                at.fixupTx(at.withRefInputs(refScriptTx1In, Vector(refScriptTx1In)))
              s6 <- totalNow(at, Vector(spendRefScriptTx))
              withSizes = Vector(
                (refScriptTx1, s1, 0L),
                (refScriptTx2, s2, 0L),
                (spendScriptWithRefScriptTx, s3, scriptSize),
                (spendScriptWithTwoRefScriptsTx, s4, 2 * scriptSize),
                (spendRootUtxoTx, s5, scriptSize),
                (spendRefScriptTx, s6, scriptSize)
              )
              _ = withSizes.foreach((t, got, expected) => assertEquals(got, expected, s"${t.id}"))
              all <- totalNow(at, withSizes.map(_._1))
              _ = assertEquals(all, withSizes.map(_._3).sum)
            yield withSizes.map((t, _, size) => (t, size))
          )
        yield (scriptSize, before, submitted, withSizes)
      val (_, before, submitted, withSizes) = run(at)(sizes)
      // for each prefix of the list, on the state restored: the sum of its sizes from version 11, 0
      // below it (`isPostV10`, `:187-190`)
      withSizes.indices.foreach { n =>
        val prefix = withSizes.take(n + 1)
        assertEquals(
          total(at, prefix.map(_._1), before),
          if v >= 11 then prefix.map(_._2).sum else 0L,
          s"$n"
        )
      }
      // the five submitted, as a block on the state before them: admitted by the node's run
      assertEquals(submitted.size, 5)
      admitted(
        s"totalRefScriptSizeInBlock's five at $v",
        blockOf(v, submitted),
        before.ls,
        at.params
      ).void
    }

    register(
      s"version $v: Use a reference script in a collateral output (Conway/Imp/BbodySpec.hs:195)"
    ) {
      val at = At(v)
      val failing = example(Example.AlwaysFailsNoDatum, PlutusLanguage.V3)
      val sizes: Imp[(Long, ImpState, Vector[Built], Vector[Built])] =
        for
          failingPlutusTxIn <- at.produceScript(failing.hash)
          (script, hash) <- at.freshRequireSignature
          scriptTxIn <- at.produceScript(hash)
          scriptSize = B.encode(script).size.toLong
          // `freshKeyAddr_` below version 12 (`:210-215`)
          addr <- at.freshKeyAddr
          collRefScriptTxOut = Out(addr, 0, refScript = Some((0, script)))
          (before, submitted, txs) <- recorded(
            for
              createCollateralTx <- submitPhase2Invalid(at)(
                Spec(
                  inputs = SortedSet(failingPlutusTxIn),
                  collateralReturn = Some(collRefScriptTxOut)
                )
              )
              s1 <- totalNow(at, Vector(createCollateralTx))
              _ = assertEquals(s1, 0L)
              // `txInAt 1`: the collateral return, after the one output
              _ = assertEquals(
                bodies.outputs[TxLevel.Top](tx.body[TxLevel.Top](createCollateralTx.era)).size,
                1
              )
              useCollateralTx <-
                at.submitTx(at.withRefInputs(scriptTxIn, Vector(createCollateralTx.in(1))))
              s2 <- totalNow(at, Vector(createCollateralTx, useCollateralTx))
              _ = assertEquals(s2, scriptSize)
            yield Vector(createCollateralTx, useCollateralTx)
          )
        yield (scriptSize, before, submitted, txs)
      val (scriptSize, before, submitted, txs) = run(at)(sizes)
      // on the state restored (`isPostV10`, `:236-238`)
      assertEquals(total(at, txs, before), if v >= 11 then scriptSize else 0L)
      // the transactions submitted, the collateral's producer, the invalid one and the one that
      // reads its collateral return, as a block on the state before them: admitted by the node's
      // run
      assertEquals(submitted.size, 3)
      assertEquals(submitted.drop(1), txs)
      admitted(
        s"the collateral output's three at $v",
        blockOf(v, submitted),
        before.ls,
        at.params
      ).void
    }
  }
