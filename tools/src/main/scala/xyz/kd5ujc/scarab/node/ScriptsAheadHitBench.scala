// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Phase2, RecordedRequests}
import xyz.kd5ujc.scarab.plutus.{EvalRequest, PlutusData, ScarabEvaluator}

/** What a hit of the scripts evaluated ahead costs the rules (#1499 item 4): over the recorded
  * Preview and preprod script requests, each kept once in a [[ScriptsAhead.Results]] and looked up
  * with a request built anew (its data, script and cost model fresh objects, as the worker's own
  * collection builds them), the time of the lookup and of its parts, against the evaluation itself.
  *
  * `keys [rounds] [blocks.hex]`: the rules' cost of a transaction whose collection and results were
  * made ahead, before and after collections were kept (#1499), on the recorded cases read twice
  * (the speculator's reading and the rules' own, no object shared): before, the rules' collection
  * and a lookup of each request it collected; after, the comparison of the rules' inputs with the
  * kept collection's and a lookup of each request it hands them. With a file of captured mainnet
  * blocks (one hex block a line), the comparison of each of their transactions with redeemers read
  * twice, the part of the key a mainnet transaction's size decides; their outputs are not in the
  * file.
  *
  * `contention [threads...]`: the flat body's preparation alone and beside that many threads
  * evaluating flat out.
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

  /** A recorded case's collection inputs, as the rules make them from what they read. */
  private def inputsOf(c: xyz.kd5ujc.scarab.ledger.PlutusCase): Phase2.Inputs[?] =
    xyz.kd5ujc.scarab.ledger.TestLedger.usualEra(c.protocolMajor) match
      case era: xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra[e] =>
        Phase2.Inputs[e](era, c.tx, c.params, RecordedRequests.scriptsFor(c), c.spent, c.references)

  private def collectedOf(
      in: Phase2.Inputs[?],
      posix: Phase2.Posix,
      ahead: Phase2.Ahead
  ): Phase2.Collected[Vector[(xyz.kd5ujc.scarab.ledger.Redeemer, EvalRequest)]] =
    in match
      case i: Phase2.Inputs[e] => Phase2.collected[e](i, posix, ahead)

  /** The `keys` mode (see above). */
  private def keys(rounds: Int, blocks: Option[String]): Unit =
    val evaluator = ScarabEvaluator.pure
    // the speculator's reading and the rules' own, each case read from its file anew
    val speculated = RecordedRequests.recordedCases.map(_._2)
    val own = RecordedRequests.recordedCases.map(_._2)
    val cases = speculated.zip(own).filter((s, _) => inputsOf(s).collect(s.posix).isRight)
    val scripts = cases.map((s, _) => inputsOf(s).collect(s.posix).fold(_ => 0, _.size)).sum
    println(s"${cases.size} recorded transactions with $scripts scripts")
    // kept as the speculator keeps them: each transaction's collection, then its results
    def holding: ScriptsAhead.Results =
      val results =
        ScriptsAhead.Results.create(ScriptsAhead.Bounds.copy(entries = 1 << 20, bytes = 1L << 40))
      val block = Bytes.fromArray(Array[Byte](1))
      cases.foreach { (s, _) =>
        Phase2.Kept.of(inputsOf(s), s.posix).foreach { kept =>
          val collection = ScriptsAhead.Collection.Flat(kept)
          val _ = results.collected(block, Vector(collection))
          collection.requests.foreach(r => { val _ = results.put(block, r, evaluator.evaluate(r)) })
        }
      }
      results
    val times = Vector.newBuilder[(String, Long)]
    // the rules' inputs, made from what they read before they collect
    val ownInputs = cases.map((_, o) => inputsOf(o) -> o.posix)
    val keptInputs = cases.map((s, _) => inputsOf(s))
    def part(name: String, f: (Phase2.Inputs[?], Phase2.Inputs[?]) => Boolean): (String, Long) =
      val start = System.nanoTime()
      ownInputs.zip(keptInputs).foreach { case ((o, _), k) => { val _ = f(o, k) } }
      name -> (System.nanoTime() - start)
    (0 until rounds).foreach { round =>
      val (before, after) = (holding, holding)
      val rules = before.evaluator(evaluator)
      val beforeTime =
        val start = System.nanoTime()
        ownInputs.foreach((o, posix) =>
          o.collect(posix).foreach(_.foreach((_, r) => { val _ = rules.evaluate(r) }))
        )
        System.nanoTime() - start
      val collectTime =
        val start = System.nanoTime()
        ownInputs.foreach((o, posix) => { val _ = o.collect(posix) })
        System.nanoTime() - start
      val rulesAfter = after.evaluator(evaluator)
      val afterTime =
        val start = System.nanoTime()
        ownInputs.foreach((o, posix) =>
          collectedOf(o, posix, after.flat)
            .foreach(_.foreach((_, r) => { val _ = rulesAfter.evaluate(r) }))
        )
        System.nanoTime() - start
      // what the rules found on the pass above alone
      val found = (after.collectionCounts, after.counts)
      val keyTime =
        val start = System.nanoTime()
        ownInputs.foreach((o, posix) => { val _ = collectedOf(o, posix, after.flat) })
        System.nanoTime() - start
      val parts = Vector(
        part("key part: transaction", (a, b) => a.tx == b.tx),
        part("key part: parameters", (a, b) => a.pp == b.pp),
        part("key part: scripts", (a, b) => a.scripts == b.scripts),
        part(
          "key part: spent and reference outputs",
          (a, b) => a.spent == b.spent && a.references == b.references
        )
      )
      if round == 0 then
        println(
          s"before: hits ${before.counts.hit} of ${before.counts.hit + before.counts.miss + before.counts.stale}; " +
            s"after: collections offered ${found._1.offered} of ${cases.size}, " +
            s"scripts found by their request ${found._1.byRequest}, hits ${found._2.hit}"
        )
      if round >= rounds / 2 then
        times ++= Vector(
          "before: the rules' collection and lookups" -> beforeTime,
          "before: the rules' collection alone" -> collectTime,
          "after: the kept collection taken and lookups" -> afterTime,
          "after: the kept collection taken alone" -> keyTime
        ) ++ parts
    }
    val n = cases.size.toDouble
    times.result().groupMapReduce(_._1)(p => Vector(p._2))(_ ++ _).toVector.sortBy(_._1).foreach {
      (name, ts) =>
        val median = ts.sorted.apply(ts.size / 2)
        println(f"$name%-48s median ${median / n / 1000}%9.2f us per transaction")
    }
    blocks.foreach(mainnet(_, rounds))

  /** Each transaction with redeemers of the captured blocks in `file` read twice and compared, as
    * the rules' inputs are with a kept collection's, and how many of them read only outputs the
    * file's blocks create.
    */
  private def mainnet(file: String, rounds: Int): Unit =
    import xyz.kd5ujc.scarab.ledger.{Block, Tx}
    val lines = scala.io.Source.fromFile(file).getLines().filter(_.nonEmpty).toVector
    def read: Vector[Tx] = lines.flatMap { hex =>
      Bytes
        .fromHex(hex.trim)
        .toOption
        .flatMap(raw => Block.decodeShelley(raw).toOption)
        .toVector
        .flatMap {
          case b: Block.Shelley =>
            xyz.kd5ujc.scarab.ledger.rules.EraBlock.decode(b).flatMap(_.toOption)
          case _ => Vector.empty
        }
    }
    val (one, two) = (read, read)
    val pairs = one.zip(two).filter(_._1.witnesses.redeemers.nonEmpty)
    val made = one.flatMap(ScriptsAhead.created(_).keys).toSet
    val inWindow = pairs.count((tx, _) => (tx.body.inputs ++ tx.body.referenceInputs).forall(made))
    val sizes = pairs.map(_._1.size)
    println(
      s"${lines.size} mainnet blocks: ${one.size} transactions, ${pairs.size} with redeemers " +
        s"(mean ${sizes.sum / sizes.size.max(1)} bytes, max ${sizes.maxOption.getOrElse(0L)}); " +
        s"$inWindow of them read only outputs the blocks create"
    )
    val equal = pairs.forall((a, b) => a == b && !(a eq b))
    val runs = (0 until rounds).map { _ =>
      val (x, y) = (read, read)
      val ps = x.zip(y).filter(_._1.witnesses.redeemers.nonEmpty)
      val start = System.nanoTime()
      ps.foreach((a, b) => { val _ = a.body.id == b.body.id && a == b })
      (System.nanoTime() - start).toDouble / ps.size.max(1) / 1000
    }
    println(
      f"a mainnet transaction with redeemers compared with its copy (equal: $equal): median ${runs.sorted.apply(runs.size / 2)}%.2f us"
    )
    // the blocks and their transactions decoded, as the speculator reads a queued block first
    val decodes = (0 until rounds).map { _ =>
      val start = System.nanoTime()
      val _ = read
      (System.nanoTime() - start).toDouble / lines.size / 1e6
    }
    println(
      f"a mainnet block and its transactions decoded: median ${decodes.sorted.apply(decodes.size / 2)}%.2f ms"
    )

  def main(args: Array[String]): Unit =
    if args.headOption.contains("keys") then
      keys(args.lift(1).map(_.toInt).getOrElse(20), args.lift(2))
      sys.exit(0)
    if args.headOption.contains("contention") then
      val requests = RecordedRequests.sources.flatMap(_.requests).map(_.request)
      val counts = args.drop(1).map(_.toInt).toVector match
        case Vector() => Vector(2)
        case asked    => asked
      // warm up, then alternate alone and beside the evaluating threads
      val _ = prepared(2000, 0, requests)
      (1 to 3).foreach { i =>
        val alone = prepared(2000, 0, requests)
        counts.foreach { threads =>
          val beside = prepared(2000, threads, requests)
          println(
            f"run $i: preparation alone $alone%.1f us, beside $threads evaluating threads $beside%.1f us, ratio ${beside / alone}%.3f"
          )
        }
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
