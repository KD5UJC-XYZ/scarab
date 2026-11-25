// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Id
import java.util.concurrent.atomic.AtomicReference
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, PlutusLoop, ScriptIntegrity, TxLevel}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.{
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusLanguage,
  ScarabEvaluator,
  ScriptEvaluator
}

/** Script-heavy Dijkstra blocks, which the recorded testnet does not hold (#85, ADR 0018): `n`
  * independent batches, each a top level that mints with [[PlutusLoop]]'s script of `iterations`
  * (Plutus V3) from an input of its own, with a collateral input of its own, so that the batches'
  * jobs are one dependency batch. Each redeemer declares the units its script spends, or the units
  * given; the block is at the recorded blocks' parameters, era history and state
  * ([[DijkstraRecordedBlocks]]), holding exactly the inputs the batches spend.
  */
object DijkstraScriptedBlocks extends Assertions:
  private val B = DijkstraImpBatch
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private lazy val payer = flat.TestLedger.key()
  private val slot = BigInt(1015165)
  private val name = Bytes.fromArray("loop".getBytes(java.nio.charset.StandardCharsets.US_ASCII))

  /** The `i` th batch's own inputs: its funding (20 ada) at 0, its collateral (30 ada) at 1. */
  private def funding(i: Int): B.In = B.In(Bytes(Vector.fill(31)(0x5a.toByte) :+ i.toByte), 0)
  private def collateral(i: Int): B.In = funding(i).copy(ix = 1)

  /** The `i` th batch, minting one token of `script` 's policy, its redeemer declaring `units`. */
  private def batch(i: Int, script: Bytes, units: ExUnits): Bytes =
    val policy = B.scriptHash(3, script)
    val token = B.cmap(B.bytes(policy) -> B.cmap(B.bytes(name) -> B.uint(1)))
    def body(integrity: Option[Bytes]): Node =
      B.body(
        (Vector(
          0 -> B.set(funding(i).node),
          1 -> B.arr(B.arr(B.bytes(B.keyAddress(payer)), B.arr(B.uint(17000000), token))),
          2 -> B.uint(3000000),
          9 -> token,
          13 -> B.set(collateral(i).node)
        ) ++ integrity.map(h => 11 -> B.bytes(h)).toVector).sortBy(_._1)*
      )
    def wits(b: Node): Node =
      B.witnesses(
        b,
        Vector(payer),
        5 -> B.cmap(
          B.arr(B.uint(1), B.uint(0)) -> B
            .arr(B.uint(0), B.arr(B.uint(units.mem), B.uint(units.steps)))
        ),
        7 -> B.set(B.bytes(script))
      )
    val unsigned = body(None)
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Dijkstra, TxLevel.Top](
        DijkstraRecordedBlocks.params,
        B.topTx(unsigned, wits(unsigned)),
        Set(PlutusLanguage.V3)
      )
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    val signed = body(Some(integrity))
    B.encode(B.arr(signed, wits(signed), B.nil, B.leaf(Value.Bool(true))))

  /** The block of `n` batches whose redeemers each declare `units`, on the state of their inputs.
    */
  def declaring(n: Int, script: Bytes, units: ExUnits): DijkstraRecordedBlocks.Sample =
    val utxo = B.utxo((0 until n).flatMap { i =>
      Vector(
        funding(i) -> B.out(B.keyAddress(payer), 20000000),
        collateral(i) -> B.out(B.keyAddress(payer), 30000000)
      )
    }*)
    DijkstraRecordedBlocks.Sample(
      BigInt(n),
      slot,
      n,
      DijkstraRecordedBlocks.blockOf((0 until n).toVector.map(batch(_, script, units)), slot),
      DijkstraRecordedBlocks.stateOf(utxo)
    )

  /** The units [[PlutusLoop]]'s script of `iterations` spends, as its one batch's run reports. */
  def spent(script: Bytes): ExUnits =
    val seen = AtomicReference(Vector.empty[EvalResult])
    val recording: ScriptEvaluator[Id] = (request: EvalRequest) =>
      val result = ScarabEvaluator.default.evaluate(request)
      val _ = seen.updateAndGet(_ :+ result)
      result
    val most = ExUnits(BigInt(16500000), BigInt(10000000000L))
    val _ = DijkstraRecordedBlocks.program(declaring(1, script, most), recording).serial
    seen.get match
      case Vector(EvalResult.Success(used, _)) => used
      case other                               => fail(s"not one passing script: $other")

  /** The block of `n` batches of the loop of `iterations`, each declaring the units it spends. */
  def honest(n: Int, iterations: Int): DijkstraRecordedBlocks.Sample =
    val script = PlutusLoop.script(iterations)
    declaring(n, script, spent(script))
