// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.consensus.ChainSelection.*
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}

/** Development tool (issue 133): the cost of one `ChainSelection.decide` for the candidate of a
  * peer that is behind us, which is wholly on our chain and grows by a header each time the peer
  * sends one (a `Keep` leaves the candidate as it is).
  *
  * `DecideBenchMain <headers in the candidate> [<calls>]`. Our window holds 2,160 blocks and the
  * candidate's last header is its tip, so the answer is `Keep` after one probe.
  */
object DecideBenchMain:
  def main(args: Array[String]): Unit =
    val n = args(0).toInt
    val calls = args.lift(1).fold(2000)(_.toInt)
    val k = 2160
    val chain: Vector[Header] = Fixtures.chain(k + n + 1, 0, None)
    val anchor = chain(chain.size - k - 1)
    val ours = chain.takeRight(k)
    val local = Local(
      anchor.point,
      SelectView.of(anchor),
      ours.map(h => LocalBlock(h.point, SelectView.of(h)))
    )
    // the headers the peer sent since it joined: the newest n of the chain, some older than our window
    val behind = Candidate(chain(chain.size - n - 1).point, chain.takeRight(n))
    def run(): (Double, Decision) =
      var last: Decision = Decision.Detached
      val t0 = System.nanoTime
      var i = 0
      while i < calls do
        last = decide(Tiebreaker.Unrestricted, local, behind)
        i += 1
      ((System.nanoTime - t0) / 1e3 / calls, last)
    (1 to 5).foreach { pass =>
      val (microseconds, decision) = run()
      println(f"pass $pass: candidate of $n headers: $microseconds%.1f us per decide ($decision)")
    }
