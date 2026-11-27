// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.RecordedRequests
import xyz.kd5ujc.scarab.plutus.{EvalRequest, PlutusData, ScarabEvaluator}

/** What a hit of the scripts evaluated ahead costs the rules (#1499 item 4): over the recorded
  * Preview and preprod script requests, each kept once in a [[ScriptsAhead.Results]] and looked up
  * with a request built anew (its data, script and cost model fresh objects, as the worker's own
  * collection builds them), the time of the lookup and of its parts, against the evaluation itself.
  *
  * `tools/runMain xyz.kd5ujc.scarab.node.ScriptsAheadHitBench [rounds]` from the checkout's root.
  * Not a gate.
  */
object ScriptsAheadHitBench:
  private def copied(d: PlutusData): PlutusData = d match
    case PlutusData.Constr(t, fs) => PlutusData.Constr(BigInt(t.toByteArray), fs.map(copied))
    case PlutusData.Map(kvs)      => PlutusData.Map(kvs.map((k, v) => copied(k) -> copied(v)))
    case PlutusData.List(xs)      => PlutusData.List(xs.map(copied))
    case PlutusData.I(i)          => PlutusData.I(BigInt(i.toByteArray))
    case PlutusData.B(b)          => PlutusData.B(Bytes.fromArray(b.toArray))

  private def fresh(r: EvalRequest): EvalRequest =
    r.copy(
      script = Bytes.fromArray(r.script.toArray),
      args = r.args.map(copied),
      costModel = r.costModel.map(identity),
      budget = r.budget.copy(mem = BigInt(r.budget.mem.toByteArray))
    )

  private def nodes(d: PlutusData): Long = d match
    case PlutusData.Constr(_, fs) => 1 + fs.iterator.map(nodes).sum
    case PlutusData.Map(kvs)      => 1 + kvs.iterator.map((k, v) => nodes(k) + nodes(v)).sum
    case PlutusData.List(xs)      => 1 + xs.iterator.map(nodes).sum
    case _                        => 1

  /** The context's information, where the arguments' last is a context whose first field is it. */
  private def info(r: EvalRequest): Option[PlutusData] =
    r.args.lastOption.collect { case PlutusData.Constr(_, fs) => fs }.flatMap(_.headOption)

  /** Copies of `requests` in which the requests of one transaction (equal information) share one
    * fresh copy of it, as the worker's collection shares it among a transaction's scripts.
    */
  private def sharing(requests: Vector[EvalRequest]): Vector[EvalRequest] =
    val infos = requests.flatMap(info).distinct.map(i => i -> copied(i)).toMap
    requests.map { r =>
      val f = fresh(r)
      (info(r).flatMap(infos.get), f.args.lastOption) match
        case (Some(shared), Some(PlutusData.Constr(t, fs))) =>
          f.copy(args = f.args.init :+ PlutusData.Constr(t, shared +: fs.drop(1)))
        case _ => f
    }

  /** `f` over every copy, timed as a whole (FUN-2 (e): the clock is read around the calls alone).
    */
  private def timed[A](copies: Vector[EvalRequest])(f: EvalRequest => A): Long =
    val start = System.nanoTime()
    copies.foreach(c => { val _ = f(c) })
    System.nanoTime() - start

  /** The flat body's preparation of `PreparedBlockFixture` (its ledger pass, which builds the
    * script contexts), timed `n` times on this thread, with `threads` other threads evaluating the
    * recorded requests flat out meanwhile, as the speculator's would at most: the median of five
    * runs, in microseconds a preparation.
    */
  private def prepared(n: Int, threads: Int, requests: Vector[EvalRequest]): Double =
    val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
    val evaluator = ScarabEvaluator.pure
    val others = Vector.fill(threads)(
      new Thread(() =>
        Iterator
          .continually(())
          .takeWhile(_ => !stop.get)
          .foreach(_ => requests.foreach(r => { val _ = evaluator.evaluate(r) }))
      )
    )
    others.foreach(_.start())
    val body = xyz.kd5ujc.scarab.ledger.PreparedBlockFixture.body(evaluator)
    val runs = Vector.fill(5) {
      val start = System.nanoTime()
      (0 until n).foreach(_ => { val _ = body.prepare })
      (System.nanoTime() - start).toDouble / n / 1000
    }
    stop.set(true)
    others.foreach(_.join())
    runs.sorted.apply(2)

  def main(args: Array[String]): Unit =
    if args.headOption.contains("contention") then
      val requests = RecordedRequests.sources.flatMap(_.requests).map(_.request)
      // warm up, then alternate alone and beside two evaluating threads
      val _ = prepared(2000, 0, requests)
      (1 to 3).foreach { i =>
        val alone = prepared(2000, 0, requests)
        val beside = prepared(2000, 2, requests)
        println(
          f"run $i: preparation alone $alone%.1f us, beside two evaluating threads $beside%.1f us, ratio ${beside / alone}%.3f"
        )
      }
      sys.exit(0)
    val rounds = args.headOption.map(_.toInt).getOrElse(20)
    val requests = RecordedRequests.sources.flatMap(_.requests).map(_.request)
    val evaluator = ScarabEvaluator.pure
    val kept = requests.map(r => r -> evaluator.evaluate(r))
    // a fresh set of kept results, so that no proven pair of an earlier round answers
    def results =
      val made =
        ScriptsAhead.Results.create(ScriptsAhead.Bounds.copy(entries = 1 << 20, bytes = 1L << 40))
      kept.foreach((r, res) => { val _ = made.put(Bytes.fromArray(Array[Byte](1)), r, res) })
      made
    val size = requests.map(r => r.args.iterator.map(nodes).sum)
    println(
      s"${requests.size} requests; data nodes per request: mean ${size.sum / size.size.max(1)}, max ${size.maxOption.getOrElse(0L)}"
    )
    val weights = kept.map((r, res) => ScriptsAhead.Results.weight(r, res))
    println(
      s"estimated weight per kept result: mean ${weights.sum / weights.size.max(1)} bytes, max ${weights.maxOption.getOrElse(0L)}"
    )
    val parts = Vector.newBuilder[(String, Long)]
    (0 until rounds).foreach { round =>
      // fresh copies for every part, so no cached hash of a part timed before is reused
      def copies = requests.map(fresh)
      val (a, b, c, d, e) = (copies, copies, copies, copies, copies)
      val scriptHash = timed(a)(_.script.hashCode)
      val equal = timed(b.zip(requests).map(_._1))(r => r.args == requests.head.args)
      val equality = {
        val pairs = c.zip(requests)
        val start = System.nanoTime()
        pairs.foreach((x, y) => { val _ = x == y })
        System.nanoTime() - start
      }
      val (plain, sharedKept, sharedCopies) = (results, results, sharing(requests))
      val lookup = timed(d)(plain.lookup)
      val shared = timed(sharedCopies)(sharedKept.lookup)
      val txs = requests.flatMap(info).distinct.size
      val evaluation = timed(e)(evaluator.evaluate)
      if round >= rounds / 2 then
        parts ++= Vector(
          "script hash" -> scriptHash,
          "args == first (mostly unequal)" -> equal,
          "request == its copy" -> equality,
          "lookup (hit)" -> lookup,
          s"lookup (hit), a tx's scripts sharing its info ($txs txs)" -> shared,
          "evaluation" -> evaluation
        )
    }
    val n = requests.size.toDouble
    parts.result().groupMapReduce(_._1)(p => Vector(p._2))(_ ++ _).toVector.sortBy(_._1).foreach {
      (name, times) =>
        val median = times.sorted.apply(times.size / 2)
        println(f"$name%-56s median ${median / n / 1000}%9.2f us per request")
    }
