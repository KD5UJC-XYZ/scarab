// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, IOApp}
import cats.effect.std.Queue
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{Block, PeerError}
import xyz.kd5ujc.scarab.node.forge.ForgedChain

/** The cost of what issue 132 put on the per-batch path of a session, next to the cost of decoding
  * the block it belongs to. Not part of `check`: run it with
  * `sbt "node/Test/runMain xyz.kd5ujc.scarab.node.FiberBoundaryBench"`; it prints the median
  * nanoseconds per operation. The figures are in `docs/performance.md` section 22.
  *
  * What a session pays, per batch of headers (and per header for the decode): a fiber that ends
  * with an `Either` (`Fault.fiber`) instead of a bare fiber, the blame check in `pull`
  * (`race(blamed, queue.take)` instead of `queue.take`), and the multiplexer's byte count per SDU
  * (measured end to end in `MuxThroughputBench`).
  */
object FiberBoundaryBench extends IOApp.Simple:
  private val warmups = 20000
  private val runs = 21
  private val perRun = 20000

  private def median(xs: Vector[Double]): Double = xs.sorted.apply(xs.size / 2)

  /** Median ns per operation of `op`, repeated `perRun` times per run. */
  private def measure(op: IO[Unit]): IO[Double] =
    val many = (1 to perRun).toList.traverse_(_ => op)
    (1 to warmups / perRun + 1).toList.traverse_(_ => many) *>
      Vector
        .fill(runs)(())
        .traverse(_ =>
          IO.monotonic
            .flatMap(t0 => many *> IO.monotonic.map(t1 => (t1 - t0).toNanos.toDouble / perRun))
        )
        .map(median)

  private def report(label: String, ns: Double): IO[Unit] =
    IO.println(f"BENCH $label%-64s $ns%10.0f ns")

  def run: IO[Unit] =
    for
      forged <- ForgedChain.forge(5000 until 5060)
      blocks = forged.raws
      decode <- {
        var i = 0
        measure(IO.delay {
          i = (i + 1) % blocks.size
          Block.decodeShelley(blocks(i)).isRight
        }.void)
      }
      _ <- report("decode one forged block (the work a header and its block need)", decode)
      bare <- measure(IO.unit.start.flatMap(_.joinWithNever))
      scoped <- measure(
        Fault.fiber[IO, Unit](IO.unit).start.flatMap(_.joinWithNever).void
      )
      _ <- report("a fiber, started and joined: bare", bare)
      _ <- report("a fiber, started and joined: in its own error scope (Fault.fiber)", scoped)
      scopedRaise <- measure(
        Fault.fiber[IO, Unit](Handle.allow[PeerError](IO.unit).rescue(_ => IO.unit)).void
      )
      _ <- report("an error scope entered and left, inline (Handle.allow)", scopedRaise)
      queue <- Queue.unbounded[IO, Int]
      plain <- measure(queue.offer(1) *> queue.take.void)
      raced <- measure(
        queue.offer(1) *> IO.race(IO.never[Unit], queue.take).void
      )
      _ <- report("queue offer and take", plain)
      _ <- report(
        "queue offer and take under race with a pending Deferred (the blame check)",
        raced
      )
    yield ()
