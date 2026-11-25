// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.Id
import java.util.concurrent.atomic.AtomicReference
import munit.Assertions
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.{EraTx, PlutusLoop, PParams, ScriptIntegrity, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayImp.*
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayBlockRun
import xyz.kd5ujc.scarab.ledger.state.{Build, Conway, LedgerState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.{
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusLanguage,
  ScarabEvaluator,
  ScriptEvaluator
}

/** Script-heavy Conway blocks (#85, ADR 0018), Alonzo's `BbodySpec.hs:49` (cardano-ledger f649f975)
  * block of script transactions scaled up: `n` independent transactions, each minting with
  * [[PlutusLoop]]'s script of `iterations` (Plutus V3) from an input of its own, with a collateral
  * input of its own, so that their jobs are one dependency batch. Each redeemer declares the units
  * its script spends, or the units given. The block is the Imp's ([[ConwayImp]]) at version 11, on
  * the Imp's parameters and a state holding exactly the inputs the transactions spend.
  */
object ConwayScriptedBlocks extends Assertions:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private given EraTx.conway.bodyOf.Level[TxLevel.Top] = EraTx.conway.bodyOf.top
  private val major = 11
  private lazy val payer = flat.TestLedger.key()
  private val name = Bytes.fromArray("loop".getBytes(java.nio.charset.StandardCharsets.US_ASCII))

  val params: PParams[Conway] = pp(major)

  /** A block, its state, its transactions' ids in its order, and the era run of it with an
    * evaluator.
    */
  final case class Scripted(raw: Bytes, state: LedgerState[Conway], ids: Vector[Bytes]):
    def program(evaluator: ScriptEvaluator[Id]): ConwayBlockRun.Body =
      ConwayImp.program(raw, state, params)(evaluator, None)

  /** The `i` th transaction's own inputs: its funding (20 ada) at 0, its collateral (30 ada) at 1.
    */
  private def funding(i: Int): TxIn = TxIn(Bytes(Vector.fill(31)(0x5a.toByte) :+ i.toByte), 0)
  private def collateral(i: Int): TxIn = funding(i).copy(index = 1)

  /** The `i` th transaction, minting one token of `script` 's policy, its redeemer declaring
    * `units`.
    */
  private def transaction(i: Int, script: Bytes, units: ExUnits): Built =
    val policy = B.scriptHash(tagOf(PlutusLanguage.V3), script)
    val assets = B.cmap(B.bytes(name) -> B.uint(1))
    val spec = Spec(
      inputs = SortedSet(funding(i)),
      outputs = Vector(
        Out(keyAddress(payer), 17000000, assets = Some(B.cmap(B.bytes(policy) -> assets)))
      ),
      fee = 3000000,
      mint = Some(policy -> assets),
      collateral = SortedSet(collateral(i)),
      redeemers = Map(Purpose.Minting(policy) -> (B.uint(0), units)),
      plutusScripts = Map(policy -> TestScript(PlutusLanguage.V3, script, B.uint(0), None))
    )
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Conway, TxLevel.Top](
        params,
        encode(spec, Vector.empty).era,
        Set(PlutusLanguage.V3)
      )
      .map(ScriptIntegrity.hashScriptIntegrity(_))
    encode(spec.copy(integrity = integrity), Vector(payer))

  /** The block of `n` transactions whose redeemers each declare `units`, on the state of their
    * inputs.
    */
  def declaring(n: Int, script: Bytes, units: ExUnits): Scripted =
    val funds = outputs((0 until n).flatMap { i =>
      Vector(
        funding(i) -> Out(keyAddress(payer), 20000000),
        collateral(i) -> Out(keyAddress(payer), 30000000)
      )
    }*)
    val ls = Build.funded[Conway](Build.nes[Conway](0L, stored(major)), funds).es.ls
    val txs = (0 until n).toVector.map(transaction(_, script, units))
    Scripted(blockOf(major, txs), ls, txs.map(_.id))

  /** The units `script` spends, as its one transaction's run reports. */
  def spent(script: Bytes): ExUnits =
    val seen = AtomicReference(Vector.empty[EvalResult])
    val recording: ScriptEvaluator[Id] = (request: EvalRequest) =>
      val result = ScarabEvaluator.default.evaluate(request)
      val _ = seen.updateAndGet(_ :+ result)
      result
    val most = ExUnits(BigInt(10000000), BigInt(10000000000L))
    val _ = declaring(1, script, most).program(recording).serial
    seen.get match
      case Vector(EvalResult.Success(used, _)) => used
      case other                               => fail(s"not one passing script: $other")

  /** The block of `n` transactions of the loop of `iterations`, each declaring the units it spends.
    */
  def honest(n: Int, iterations: Int): Scripted =
    val script = PlutusLoop.script(iterations)
    declaring(n, script, spent(script))
