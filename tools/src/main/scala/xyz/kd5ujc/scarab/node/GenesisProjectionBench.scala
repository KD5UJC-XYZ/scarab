// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.ChainSelection.*
import xyz.kd5ujc.scarab.consensus.SelectView
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}

/** Development tool (issue 1499): the cost of each admission's Genesis projection in the
  * block-selection worker, over a run of admissions. Each admission projects the candidate of the
  * block it admits (`CandidateSelection.project`) and then extends the window by that block, whose
  * anchor moves on by one as the window holds `k` blocks, when the projection chooses it; the limit
  * on eagerness reaches `ahead` headers past the first tip (behind it when negative), so a window
  * that reaches `k` blocks past the limit stops there. `before` is the projection of the
  * whole-structure oracle (`GenesisProjectionOracle`), its window extended and built whole at each
  * admission, as the window's index was; `after` is the projection now, on the window carried from
  * one admission to the next.
  *
  * `GenesisProjectionBench [<k>] [<ahead>] [<admissions>]`.
  */
object GenesisProjectionBench:
  def main(args: Array[String]): Unit =
    val k = args.lift(0).fold(2160)(_.toInt)
    val ahead = args.lift(1).fold(2000)(_.toInt)
    val admissions = args.lift(2).fold(2000)(_.toInt)
    val chain: Vector[Header] = Fixtures.chain(1 + k + admissions.max(ahead) + 1, 0, None)
    def block(h: Header) = LocalBlock(h.point, SelectView.of(h))
    val start = Local(chain(0).point, SelectView.of(chain(0)), chain.slice(1, 1 + k).map(block))
    val limit = Some(Fragment(chain(0).point, chain.slice(1, 1 + k + ahead).map(_.blockPoint)))
    def run(label: String, carried: Boolean): Unit =
      (1 to 5).foreach { pass =>
        val t0 = System.nanoTime
        val (_, sink) = (0 until admissions).foldLeft((start, 0)) { case ((local, sink), i) =>
          val window =
            if carried then local else Local(local.anchor, local.anchorView, local.blocks)
          // the admitted block's candidate: the blocks after our tip up to it (block `n` is at `n`)
          val tip = window.tipView.blockNo.toInt
          val candidate =
            Candidate(
              window.blocks.lastOption.fold(window.anchor)(_.point),
              chain.slice(tip + 1, 2 + k + i)
            )
          val chosen =
            if carried then CandidateSelection.project(k, window, Vector(candidate), limit)
            else GenesisProjectionOracle.project(k, window, Vector(candidate), limit)
          // the window adopts what was chosen, keeping `k` blocks
          val adopted = chosen.headOption.fold(window)(c =>
            c.headers.foldLeft(window)((w, h) =>
              val immutable = (w.blocks.size + 1 - k).max(0)
              if carried then w.extended(block(h), immutable)
              else GenesisProjectionOracle.extended(w, block(h), immutable)
            )
          )
          (adopted, sink + chosen.size)
        }
        val us = (System.nanoTime - t0) / 1e3 / admissions
        if pass == 5 then println(f"$label%-7s $us%9.1f us per admission ($sink chosen)")
      }
    println(s"k=$k ahead=$ahead admissions=$admissions")
    run("before", carried = false)
    run("after", carried = true)
