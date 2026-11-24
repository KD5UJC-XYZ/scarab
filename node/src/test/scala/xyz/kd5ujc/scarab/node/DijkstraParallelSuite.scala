// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPlanned, DijkstraRecordedBlocks}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator

/** Dijkstra's era run on a block through the node's bounded workers (`ParallelPlutus`, ADR 0018),
  * against its serial run (`DijkstraBlockRun.program`, #85): on every recorded block of the public
  * Leios testnet's script transactions (`DijkstraRecordedBlocks`), at one, two, four and eight
  * workers, the same state, proposals and counters, or the same refusal. The era run's prepared
  * plan on the reference's BBODY cases, the sub-transaction's and Alonzo's `:49` among them, and on
  * the crafted blocks whose scripts read what another batch changes, is `DijkstraBbodyImpSuite` 's
  * (ledger), on as many threads; the devnet chain through the node's rules is `MirrorDijkstraSuite`
  * 's.
  */
class DijkstraParallelSuite extends FailsOnUncaught:
  test(
    "every recorded block through the node's bounded workers, one, two, four and eight: the serial run's result"
  ) {
    Vector(1, 2, 4, 8)
      .traverse(n =>
        ParallelPlutus.Workers
          .from(n)
          .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
          .map(n -> _)
      )
      .flatMap { services =>
        DijkstraRecordedBlocks.samples.traverse_ { sample =>
          // the serial run's results given again, so that a failing script's term, which
          // compares by identity, is the serial run's
          val shared = DijkstraPlanned.Shared(ScarabEvaluator.default)
          val serial = DijkstraRecordedBlocks.program(sample, shared).serial
          services.traverse_ { (n, service) =>
            service(DijkstraRecordedBlocks.program(sample, shared.replaying))
              .map(result => assertEquals(result, serial, s"block ${sample.block}, $n workers"))
          }
        }
      }
  }
