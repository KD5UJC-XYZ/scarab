// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.protocol.Era

/** Full BBODY fixture with independent, funded script spends and real script admission/context
  * collection. The injected evaluator controls only phase 2, not the ordinary ledger checks.
  */
object PreparedBlockFixture:
  private val recorded = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
  private val script = (recorded.tx.witnesses.scripts ++
    (recorded.spent.values ++ recorded.references.values).flatMap(_.referenceScript))
    .find(_.kind.isPlutus)
    .getOrElse(sys.error("fixture script absent"))
  private val owner = TestLedger.key()
  private val address = Address
    .decode(Bytes(Vector(0x70.toByte)) ++ script.hash)
    .fold(e => sys.error(e.toString), identity)
  private val scriptOutput = TestLedger
    .out(address, 10000000)
    .copy(datum = Datum.Inline(Bytes(Vector(0))))
  val params = recorded.params.copy(
    protocolMajor = 10,
    minFeeA = 0,
    minFeeB = 0,
    coinsPerUTxOByte = 0,
    priceMem = Rational.zero,
    priceSteps = Rational.zero
  )
  val txs: Vector[Tx] = Vector.tabulate(3) { i =>
    val witnesses = Witnesses.empty.copy(
      scripts = Vector(script),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0)), ExUnits(10000, 10000000)))
    )
    val b = TestLedger.body(
      s"prepared-$i",
      Vector(TestLedger.txIn(i * 2)),
      Vector(TestLedger.out(owner.address(), 9000000)),
      1000000,
      collateral = Vector(TestLedger.txIn(i * 2 + 1))
    )
    val unsigned = TestLedger.tx(b, Vector.empty, witnesses)
    val integrity = ScriptIntegrity.hash(unsigned, params, Set(script.kind))
    TestLedger.tx(b.copy(scriptDataHash = integrity), Vector(owner), witnesses)
  }
  private def resign(tx: Tx, pp: ProtocolParams = params): Tx =
    val integrity = ScriptIntegrity.hash(tx, pp, Set(script.kind))
    TestLedger.tx(
      tx.body.copy(scriptDataHash = integrity),
      Vector(owner),
      tx.witnesses.copy(vkeys = Vector.empty),
      tx.isValid
    )

  val dependent: Vector[Tx] =
    val first = resign(
      txs(0).copy(body =
        txs(0).body.copy(outputs = Vector(scriptOutput.copy(value = Value.coin(9000000))))
      )
    )
    val second = resign(
      txs(1).copy(body =
        txs(1).body.copy(
          inputs = Vector(TxIn(first.body.id, 0)),
          outputs = Vector(TestLedger.out(owner.address(), 8000000))
        )
      )
    )
    Vector(first, second, txs(2))

  private val utxo = Utxo.empty ++ txs.flatMap(tx =>
    tx.body.inputs.map(_ -> scriptOutput) ++
      tx.body.collateral.map(_ -> TestLedger.out(owner.address(), 10000000))
  )
  private val initial = NewEpochState.empty[Conway](EpochNo.fromBigInt(5).toOption.get, params)
  val state: EraNes = EraNes.InEra(
    ShelleyBasedEra.SConway,
    initial.copy(es =
      initial.es.copy(ls =
        initial.es.ls.copy(utxo = initial.es.ls.utxo.copy(utxo = UtxoTables(utxo)))
      )
    )
  )
  private val empty = Bytes.empty
  val header = Header.Shelley(
    Era.Conway,
    empty,
    empty,
    empty,
    1,
    1000,
    None,
    owner.vkey,
    empty,
    HeaderVrf.Praos(VrfCert(empty, empty)),
    100,
    empty,
    Header.OpCert(empty, 0, 0, empty),
    (10, 0),
    empty
  )
  def body(
      evaluator: ScriptEvaluator[Id],
      transactions: Vector[Tx] = txs,
      observer: Option[Phase2.Observation => Unit] = None,
      verdicts: Verdicts = Verdicts.Direct
  ): EraBlock.Body =
    val block = Block.Shelley(
      Era.Conway,
      header,
      transactions.map(tx => Block.Transaction(tx.body.raw, Bytes.empty, None, tx.isValid)),
      100,
      empty
    )
    val ctx = EraBlock.Context(
      TestLedger.globals,
      (_, _) => Era.Conway,
      evaluator = evaluator,
      observer = observer,
      verdicts = verdicts
    )
    EraBlock.program(ctx)(state, header, block, transactions.map(Right(_)), PlutusStats.zero)

  val collectionFailure: Vector[Tx] = txs.updated(
    1,
    resign(
      txs(1).copy(witnesses =
        txs(1).witnesses
          .copy(redeemers = txs(1).witnesses.redeemers.map(_.copy(data = Bytes.empty)))
      )
    )
  )

  def babbage(evaluator: ScriptEvaluator[Id]): EraBlock.Body =
    val pp = params.copy(protocolMajor = 8)
    val transactions = txs.map(resign(_, pp))
    val emptyState = NewEpochState.empty[Babbage](EpochNo.fromBigInt(5).toOption.get, pp)
    val from = emptyState.copy[Babbage](es =
      emptyState.es.copy[Babbage](ls =
        emptyState.es.ls
          .copy[Babbage](utxo = emptyState.es.ls.utxo.copy[Babbage](utxo = UtxoTables(utxo)))
      )
    )
    val h = header.copy(era = Era.Babbage, protocolVersion = (8, 0))
    val block = Block.Shelley(
      Era.Babbage,
      h,
      transactions.map(tx => Block.Transaction(tx.body.raw, Bytes.empty, None, tx.isValid)),
      100,
      empty
    )
    val ctx = EraBlock.Context(TestLedger.globals, (_, _) => Era.Babbage, evaluator = evaluator)
    EraBlock.program(ctx)(
      EraNes.InEra(ShelleyBasedEra.SBabbage, from),
      h,
      block,
      transactions.map(Right(_)),
      PlutusStats.zero
    )
