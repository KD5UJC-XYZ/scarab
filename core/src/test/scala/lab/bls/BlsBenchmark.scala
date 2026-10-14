// SPDX-License-Identifier: Apache-2.0
package lab.bls

import lab.bls.BlsFixtures.*

/** Micro-benchmark for a 900-seat committee. Not a test: run
  * `core/Test/runMain lab.bls.BlsBenchmark` and record the output in docs/leios.md. Wall-clock,
  * single thread, after a warm-up; numbers depend on the host.
  */
object BlsBenchmark:
  private def time[A](a: => A): (A, Double) =
    val t0 = System.nanoTime()
    val r = a
    (r, (System.nanoTime() - t0) / 1e6)

  private def median(xs: Seq[Double]): Double = xs.sorted.apply(xs.size / 2)

  def main(args: Array[String]): Unit =
    val seats = 900
    val h = rbHash(42)
    val (keys, tKeys) = time(Vector.tabulate(seats)(i => key(i + 1)))
    val (pops, tPops) = time(keys.map(k => right(MinSig.provePossession(k._1))))
    val registered = keys.zip(pops).map { case ((_, vk), pop) =>
      (Weight.fromNatural(BigInt(1000)).get, Some((vk, pop)))
    }
    val (committee, tBuild) = time(right(Committee.fromSeats(registered)))
    val (sigs, tSign) = time(keys.map(k => right(MinSig.sign(k._1, h.bytes))))
    val quorum = right(Signers.of(seats, (0 until 600).map(i => right(SeatId.fromInt(i))).toSet))
    def cert(n: Int) =
      right(
        Certificate.aggregate(
          committee,
          (0 until n).map(i => right(SeatId.fromInt(i)) -> sigs(i)).toMap
        )
      )
    val full = cert(seats)
    val two3 = cert(600)
    val need = committee.quorum(2, 3).get
    // warm-up
    (0 until 30).foreach(_ => right(Certificate.verify(committee, need, h, full)))
    def bench(label: String, c: Certificate): Unit =
      val ts = (0 until 50).map(_ => time(right(Certificate.verify(committee, need, h, c)))._2)
      println(
        f"$label%-34s median ${median(ts)}%7.2f ms   min ${ts.min}%7.2f ms   max ${ts.max}%7.2f ms"
      )
    println(
      s"seats $seats, certificate ${full.encode.size} bytes, bitfield ${full.signers.bytes.size} bytes"
    )
    println(f"generate $seats keys                  ${tKeys}%8.1f ms")
    println(f"prove $seats possessions              ${tPops}%8.1f ms")
    println(f"build committee ($seats PoP checks)   ${tBuild}%8.1f ms  (once per epoch)")
    println(
      f"sign $seats votes                     ${tSign}%8.1f ms  (${tSign / seats}%.3f ms each)"
    )
    bench("verify, 600 signers (2/3)", two3)
    bench("verify, 900 signers", full)
    val (_, tAgg) = time(right(MinSig.aggregateSignatures(sigs)))
    println(f"aggregate $seats signatures            ${tAgg}%8.2f ms")
    val vote = right(Vote.sign(keys(0)._1, right(SeatId.fromInt(0)), h))
    val tv = (0 until 100).map(_ => time(right(Vote.verify(committee, vote)))._2)
    println(f"verify one vote                      median ${median(tv)}%7.3f ms")
    val tp = (0 until 100).map(_ => time(right(VerificationKey.fromBytes(keys(0)._2.bytes)))._2)
    println(f"decode one key (subgroup check)      median ${median(tp)}%7.3f ms")
    val _ = quorum
end BlsBenchmark
