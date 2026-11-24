// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Id
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.era.{CostModels, EraPParams, PParams, StoredTxOut, UTxO}
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraGovRun, EraStake, EraTx, EraTxBody, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.{
  BbodyEnv,
  DijkstraBlockRun,
  DijkstraPhase2,
  ShelleyBbodyState
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ChainAccountState,
  Coin,
  Dijkstra,
  EpochNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScarabEvaluator, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The public Leios testnet's recorded script transactions
  * (`fixtures/reference/leios-testnet-chain-contexts`), each block's in the order the chain holds
  * them, in a block of their own: the devnet's first Dijkstra block with that body and the recorded
  * block's slot, on a state holding the outputs they spend but those the block's own transactions
  * make (so a later transaction that spends one finds it only once its maker applied), at the
  * testnet's genesis parameters with the chain's cost models, and an era history whose forecast
  * reaches every recorded slot. What the chain's state held beyond those outputs (registered
  * accounts above all) is not recorded, so a transaction that needs it is refused here, which the
  * chain did not refuse: the blocks are inputs of the era run's own differential (serial against
  * prepared), not of a verdict against the reference.
  */
object DijkstraRecordedBlocks extends Assertions:
  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  /** A recorded block: its number, its slot, the block made of its sampled transactions, and the
    * state BBODY runs it from.
    */
  final case class Sample(
      block: BigInt,
      slot: BigInt,
      txs: Int,
      raw: Bytes,
      state: ShelleyBbodyState[Dijkstra]
  )

  val globals: Globals =
    flat.TestLedger.globals
      .copy(ledger = flat.TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.Leios))

  /** The testnet's genesis parameters with the chain's cost models. */
  lazy val stored: flat.ProtocolParams =
    def genesis(era: String) = json(s"fixtures/leios/testnet/config/$era-genesis.json")
    flat.dijkstra.DijkstraFixture.genesis.upgrade(
      flat.ProtocolParams
        .fromGenesis(genesis("shelley"), genesis("alonzo"), Some(genesis("conway")))
        .fold(e => fail(e.toString), identity)
    )

  lazy val params: PParams[Dijkstra] =
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    val models = json("fixtures/reference/leios-testnet-chain-contexts/cases.json").hcursor
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)
    PParams
      .lensHKD[Dijkstra]
      .andThen(EraPParams.dijkstra.hkdCostModelsL[Id])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(era(stored))

  /** An era history of one era whose forecast reaches every recorded slot. */
  lazy val history: flat.Horizon =
    val params = flat.Horizon.Params(21600, 1000, 3000000, 3000000)
    flat.Horizon
      .History(Vector.empty, flat.Horizon.Edge(0, 0, 0), params, Some(params))
      .at(flat.Horizon.Transition.Unknown(Some(BigInt(0))))

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

  private def encoded(v: Value): Bytes = ok(Cbor.encode(v))

  /** The devnet's first Dijkstra block, which has no transaction, with the transactions `txs` as
    * its body and `slot` as its header's slot, its header committing to that body.
    */
  private def blockOf(txs: Vector[Bytes], slot: BigInt): Bytes =
    val plain = devnetBlocks
      .map(_._2)
      .find(raw =>
        flat.Block.decodeShelley(raw) match
          case Right(flat.Block.Dijkstra(b)) => b.transactions.isEmpty
          case _                             => false
      )
      .getOrElse(fail("no empty devnet block"))
    val outer = items(node(plain))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val newBody = array(items(inner(1)).map(_.original).updated(0, array(txs)))
    val fields = items(signed(0))
      .map(_.original)
      .updated(1, encoded(Value.UInt(slot)))
      .updated(6, encoded(Value.UInt(BigInt(newBody.size))))
      .updated(7, encoded(Value.ByteString(Blake2b.hash256.hash(newBody))))
    val newHeader = array(Vector(array(fields), signed(1).original))
    array(Vector(outer(0).original, array(Vector(newHeader, newBody))))

  /** Every recorded block, in block order. */
  lazy val samples: Vector[Sample] =
    val start =
      xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(EpochNo.fromUnsigned(0L), stored).es.ls
    chainContextCases
      .map { c =>
        def big(name: String) = c.hcursor.get[BigInt](name).fold(e => fail(e.message), identity)
        val utxo = c.hcursor
          .get[Vector[(String, String)]]("utxo")
          .fold(e => fail(e.message), identity)
          .map((i, o) =>
            txIn(ok(DijkstraTxIn.decode(node(hex(i))))) -> ok(DijkstraTxOut.decode(node(hex(o))))
          )
        (big("block"), big("index"), big("slot"), hex(str(c, "bytes")), utxo)
      }
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .map { (block, rows) =>
        val sorted = rows.sortBy(_._2)
        // the outputs the block's own transactions make, at any level, are not in the state the
        // block starts from: a later transaction's request built from that state would not find them
        val made = sorted.flatMap { row =>
          val t = ok(DijkstraTx.inBlock(node(row._4)))
          val body = EraTx.dijkstra.body[TxLevel.Top](t)(using BothLevels.Top)
          EraTxBody.dijkstra.id[TxLevel.Top](body)(using BothLevels.Top) +:
            EraTxBody.dijkstra
              .subTransactions(body)
              .toVector
              .map(sub =>
                EraTxBody.dijkstra.id[TxLevel.Sub](
                  EraTx.dijkstra.body[TxLevel.Sub](sub)(using BothLevels.Sub)
                )(using BothLevels.Sub)
              )
        }.toSet
        val utxo = UTxO[Dijkstra](sorted.flatMap(_._5).filterNot((in, _) => made(in.id)).toMap)
        val ls = start.copy[Dijkstra](utxo =
          start.utxo.copy[Dijkstra](
            utxo = UtxoTables(
              flat.Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
            ),
            instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, start.utxo.instantStake))
          )
        )
        Sample(
          block,
          sorted.head._3,
          sorted.size,
          blockOf(sorted.map(_._4), sorted.head._3),
          ShelleyBbodyState[Dijkstra](ls, BlocksMade.empty)
        )
      }

  /** The node's era run on `sample` with `evaluator` ([[DijkstraBlockRun.program]]). */
  def program(
      sample: Sample,
      evaluator: ScriptEvaluator[Id],
      observer: Option[DijkstraPhase2.Observation => Unit] = None
  ): DijkstraBlockRun.Body =
    DijkstraBlockRun.program(
      globals,
      BbodyEnv[Dijkstra](params, ChainAccountState(Coin(0), Coin(0))),
      ok(DijkstraBlock.decode(sample.raw)),
      flat.SystemStart(java.time.Instant.EPOCH),
      history,
      sample.state,
      EraGovRun.dijkstraEmpty,
      evaluator,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slot => Right(slot * 1000),
      observer
    )
