// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf

/** Development tool (issue 133): the time the transaction rules of a late-Alonzo block take, check
  * by check, on captured mainnet blocks (`CaptureMain`).
  *
  * `ApplyBenchMain <blocks.hex> <blocks> [<passes>]`. The capture does not carry the UTxO set it
  * spends, so the inputs a block takes from before the capture are made up: an enterprise address
  * whose key is one of the transaction's witnesses, holding the value the transaction needs (the
  * first one takes all of it). Nothing here depends on the made-up values, which the checks that
  * are timed only read. Transactions with redeemers are timed in the checks that do not evaluate a
  * script; scripts are not run (a script evaluation is another cost, `ScalusEvaluator`). Signatures
  * are not verified (the cache does that off the apply thread, issue 125).
  *
  * Prints, per pass, microseconds per transaction for `PreConwayRules.apply` as a whole and for
  * each shared predicate on its own, and the share of the transactions that `apply` accepted.
  */
object ApplyBenchMain:
  private object Unverified extends Verdicts:
    def witness(vkey: Bytes, signature: Bytes, message: Bytes): Boolean = true
    def opCert(h: Header.Shelley, a: BigInt, b: BigInt) = Verdicts.Direct.opCert(h, a, b)
    def praosVrf(i: Vrf.Input, v: Bytes, p: Bytes, o: Bytes) = Verdicts.Direct.praosVrf(i, v, p, o)
    def tpraosVrf(a: Bytes, v: Bytes, p: Bytes, o: Bytes) = Verdicts.Direct.tpraosVrf(a, v, p, o)
    def keyHash(vkey: Bytes): Bytes = Verdicts.Direct.keyHash(vkey)
    def takePrepared(hash: Bytes): Option[Prepared] = None

  extension [A](v: Vector[A])
    private def ifEmpty(o: => Vector[A]): Vector[A] = if v.isEmpty then o else v

  /** `Value.+` on the assets as it was before issue 133. */
  private def oldMerge(
      a: Map[Bytes, Map[Bytes, BigInt]],
      b: Map[Bytes, Map[Bytes, BigInt]]
  ): Map[Bytes, Map[Bytes, BigInt]] =
    (a.keySet ++ b.keySet).iterator
      .map { policy =>
        val x = a.getOrElse(policy, Map.empty)
        val y = b.getOrElse(policy, Map.empty)
        policy -> (x.keySet ++ y.keySet).iterator
          .map(n => n -> (x.getOrElse(n, BigInt(0)) + y.getOrElse(n, BigInt(0))))
          .filter(_._2 != 0)
          .toMap
      }
      .filter(_._2.nonEmpty)
      .toMap

  private def time[A](f: => A): (A, Long) =
    val t0 = System.nanoTime
    val a = f
    (a, System.nanoTime - t0)

  private def enterprise(credential: Credential): Address =
    val (header, hash) = credential match
      case Credential.Key(h)    => (0x61, h)
      case Credential.Script(h) => (0x71, h)
    Address.decode(Bytes(header.toByte +: hash.value)).fold(e => sys.error(e), identity)

  private def made(address: Address, value: Value): TxOut =
    TxOut(Bytes.empty, address, value, Datum.NoDatum, None, 0)

  /** `ApplyBenchMain plutus [<passes>]`: phase 2 on the recorded Preview cases
    * (`fixtures/preview/plutus`), which carry the outputs they spend, so they evaluate for real.
    * Prints the time per evaluated script and the execution units summed over all of them (the sum
    * must not change with the evaluator's speed).
    */
  private def plutus(passes: Int): Unit =
    import xyz.kd5ujc.scarab.plutus.ScalusEvaluator
    val cases = PlutusCase.all
    def scriptsOf(c: PlutusCase): Map[Bytes, Script] =
      (c.spent.values ++ c.references.values)
        .flatMap(_.referenceScript)
        .map(s => s.hash -> s)
        .toMap ++
        c.tx.witnesses.scriptsByHash
    println(s"plutus cases=${cases.size}")
    (1 to passes).foreach { i =>
      var scripts = 0
      var mem = BigInt(0)
      var steps = BigInt(0)
      val recording: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[cats.Id] = r =>
        val result = ScalusEvaluator.default.evaluate(r)
        scripts += 1
        mem += result.spent.mem
        steps += result.spent.steps
        result
      val (_, ns) = time(cases.foreach { c =>
        Phase2.evaluate(c.tx, c.params, scriptsOf(c), c.spent, c.references, c.posix, recording)
      })
      println(
        f"pass $i: $scripts scripts, ${ns / 1e6}%.1f ms, ${ns / 1e3 / scripts}%.1f us/script, mem=$mem steps=$steps"
      )
    }

  def main(args: Array[String]): Unit =
    if args(0) == "plutus" then plutus(args.lift(1).fold(10)(_.toInt)) else bench(args)

  private def bench(args: Array[String]): Unit =
    val total = args(1).toInt
    val passes = args.lift(2).fold(5)(_.toInt)
    val blocks = Files
      .readAllLines(Path.of(args(0)))
      .asScala
      .iterator
      .filter(_.nonEmpty)
      .take(total)
      .map(l => Bytes.fromHex(l).flatMap(Block.decodeShelley(_).left.map(_.toString)))
      .map(_.fold(e => sys.error(e), identity))
      .collect { case b: Block.Shelley => b }
      .toVector
    val txs: Vector[(BigInt, Tx)] = blocks.flatMap(b =>
      b.transactions.flatMap(t => Tx.fromBlock(t, b.era).toOption.map(b.header.slot -> _))
    )
    val produced: Map[TxIn, TxOut] = txs.flatMap { case (_, t) =>
      t.body.outputs.zipWithIndex.map((o, i) => TxIn(t.body.id, i) -> o)
    }.toMap
    val base = TestLedger.state(6, Vector.empty)
    val pp = base.params.copy(
      minFeeA = 44,
      minFeeB = 155381,
      maxTxSize = 16384,
      keyDeposit = 2000000,
      poolDeposit = 500000000,
      minUTxOValue = 1000000,
      coinsPerUTxOWord = 34482,
      maxValueSize = 5000,
      protocolMajor = 6
    )
    val state0 = base.copy(params = pp)
    // The inputs from before the capture, with the value the transaction needs on top of what it
    // spends from inside the capture, on the address of one of its witnesses.
    val missing = scala.collection.mutable.Map.empty[TxIn, TxOut]
    txs.foreach { case (_, t) =>
      val unknown = (t.body.inputs ++ t.body.collateral ++ t.body.referenceInputs)
        .filterNot(produced.contains)
        .distinct
      if unknown.nonEmpty then
        val signers = t.witnesses.vkeys.map(w => Credential.Key(Verdicts.Direct.keyHash(w._1)))
        val scripts = t.witnesses.scripts.map(s => Credential.Script(s.hash))
        val owners =
          (signers ++ scripts).ifEmpty(Vector(Credential.Key(Bytes.fromArray(new Array[Byte](28)))))
        val outValue = t.body.outputs.foldLeft(Value.zero)(_ + _.value)
        val known = t.body.inputs.flatMap(produced.get).foldLeft(Value.zero)(_ + _.value)
        val registers = t.body.certificates.count {
          case Certificate.Register(_, _) => true; case _ => false
        }
        val unregisters = t.body.certificates.count {
          case Certificate.Unregister(_, _) => true; case _ => false
        }
        val pools = t.body.certificates.count {
          case Certificate.RegisterPool(_) => true; case _ => false
        }
        val deposits = pp.keyDeposit * (registers - unregisters) + pp.poolDeposit * pools
        val withdrawn = t.body.withdrawals.map(_._2).sum
        val need =
          outValue + Value.coin(t.body.fee + deposits - withdrawn) - Value(0, t.body.mint) - known
        val needed = if need.coin > 0 then need else Value.coin(2000000)
        unknown.zipWithIndex.foreach { case (in, i) =>
          val value = if i == 0 then needed else Value.coin(2000000)
          missing(in) = made(enterprise(owners(i % owners.size)), value)
        }
    }
    val utxo = produced ++ missing
    val state = state0.copy(utxo = Utxo(utxo))
    val env = TestLedger.env.copy(network = 1, verdicts = Unverified)
    println(
      s"blocks=${blocks.size} txs=${txs.size} made-up inputs=${missing.size} utxo=${utxo.size}"
    )
    def run(): Unit =
      val ns = scala.collection.mutable.LinkedHashMap.empty[String, Long].withDefaultValue(0L)
      var accepted = 0
      val failures = scala.collection.mutable.Map.empty[String, Int]
      txs.foreach { case (slot, tx) =>
        val e = env.copy(slot = slot, epoch = 320)
        val body = tx.body
        val spent = body.inputs.flatMap(utxo.get)
        val refOuts = body.referenceInputs.flatMap(utxo.get)
        val (applied, tApply) = time(PreConwayRules(state, tx, e))
        applied.fold(
          f =>
            failures.updateWith(f.message.split(" ").slice(3, 4).mkString(" "))(c =>
              Some(c.getOrElse(0) + 1)
            ),
          _ => accepted += 1
        )
        ns("apply (whole)") += tApply
        ns("outputChecks") += time(PreConwayRules.outputChecks(pp, e, body.id, body.outputs))._2
        ns("collateralChecks") += time(PreConwayRules.collateralChecks(state.utxo, pp, tx))._2
        val (scripts, tProvided) =
          time(PreConwayRules.providedScripts(tx.witnesses, spent ++ refOuts))
        ns("providedScripts") += tProvided
        val (needed, tNeeded) = time(PreConwayRules.scriptsNeeded(body, spent))
        ns("scriptsNeeded") += tNeeded
        val purposes = needed
          .collect { case (h, ps) if scripts.get(h).exists(_.kind.isPlutus) => ps }
          .flatten
          .toSet
        ns("alonzoChecks") += time(PreConwayRules.alonzoChecks(pp, tx, spent, scripts, purposes))._2
        ns("auxiliaryChecks") += time(PreConwayRules.auxiliaryChecks(tx))._2
        ns("witnessChecks") += time(
          PreConwayRules.witnessChecks(
            state0.genDelegs,
            tx,
            spent,
            PreConwayRules.Collateral.none,
            scripts,
            needed,
            tx.witnesses.vkeys.map(w => e.verdicts.keyHash(w._1)),
            e.verdicts
          )
        )._2
        ns("scriptsWellFormed") += time(PreConwayRules.scriptsWellFormed(pp, tx, e))._2
        ns("value sums") += time {
          spent.foldLeft(Value.zero)(_ + _.value) == body.outputs.foldLeft(Value.zero)(_ + _.value)
        }._2
        // the real outputs of the capture carry the tokens that the made-up inputs do not
        ns("output sums") += time(body.outputs.foldLeft(Value.zero)(_ + _.value))._2
        ns("output sums (old merge)") += time(
          body.outputs.foldLeft((BigInt(0), Map.empty: Map[Bytes, Map[Bytes, BigInt]])) {
            (acc, o) =>
              (acc._1 + o.value.coin, oldMerge(acc._2, o.value.assets))
          }
        )._2
        ns("input sort (hex)") += time(body.inputs.sortBy(i => (i.id.hex, i.index)))._2
        ns("input sort") += time(body.inputs.sorted)._2
        ns("script integrity") += time(ScriptIntegrity.hash(tx, pp, Set(ScriptKind.PlutusV1)))._2
      }
      val n = txs.size.toDouble
      println(f"accepted by apply: $accepted of ${txs.size}")
      failures.toVector.sortBy(-_._2).take(14).foreach((k, v) => println(s"    refused $v: $k"))
      ns.foreach((k, v) =>
        println(f"  $k%-20s ${v / 1000.0 / n}%8.1f us/tx  ${v / 1e6 / blocks.size}%7.2f ms/block")
      )
    (1 to passes).foreach { i =>
      println(s"pass $i")
      run()
    }
