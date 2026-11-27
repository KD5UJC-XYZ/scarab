// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.ForgedRetention.{node, ref, resumed}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.storage.{ChainStorage, ReceivedBlocks}

/** A restart after a kill that found the copy runner behind the chain (#1499): the immutable store
  * ends `L` blocks before the selected chain's anchor, so the selected-chain log holds `k + L`
  * blocks after its tip, of which the start resumes the first `k` ([[ChainStorage.resume]]) and the
  * received log holds them all. The reference's start selects from its ImmutableDB's tip over its
  * VolatileDB (`initialChainSelection`, `Storage/ChainDB/Impl/ChainSel.hs` 115-211,
  * ouroboros-consensus 82ecba329): every path in Praos mode, paths of at most `k` blocks with the
  * limit on eagerness (`maximalCandidates`, 199-210). Scarab's startup selection does the same from
  * the resumed chain over the received log ([[CandidateSelection.startup]]), so in Praos mode the
  * node is back at the tip it had before the kill.
  */
class CopyLagRestartSuite extends FailsOnUncaught:
  private val k = 3

  private def withLog[A](dir: Path)(use: ReceivedBlocks[IO] => IO[A]): IO[A] =
    Handle
      .allow[StorageError](
        ReceivedBlocks
          .open[IO](dir)(
            ChainStorage.predecessorOf(ForgedChain.geometry.byron.epochSlots),
            ReceivedBlockRecovery.startUp(ForgedChain.geometry.byron.epochSlots)
          )
          .use(use)
      )
      .rescue(error => IO(fail(s"the received log failed: ${error.message}")))

  /** The chain after the start in `phase` (none: Praos mode), where the store's tip is block 1, the
    * received log holds blocks 0 to 7 and the start resumed blocks 2 to 4 of the 6 the
    * selected-chain log has after the store's tip; and the eight blocks.
    */
  private def restarted(phase: Option[Gsm.Phase]) =
    for
      chain <- ForgedChain.forge(5000 until 5040)
      headers = chain.headers.take(8)
      _ <- IO(assertEquals(headers.size, 8, "eight blocks forged"))
      started <- TestControl.executeEmbed(Files.forAsync[IO].tempDirectory.use { dir =>
        withLog(dir / "received") { log =>
          Handle
            .allow[StorageError](
              headers.zip(chain.raws).traverse_((h, raw) => log.put(ref(h), raw))
            )
            .rescue(error => IO(fail(error.message))) *>
            node(phase, k = k, received = Some(log), initial = Some(resumed(k, headers.take(5))))(
              _.follower.committedChain.get
            )
        }
      })
    yield (started, headers)

  test("in Praos mode the start selects again the blocks beyond `k` the copy did not reach") {
    restarted(None).map { (started, headers) =>
      assertEquals(started.tip, headers.last.point, "back at the tip of the kill")
      assertEquals(started.blocks.map(_._1.hash), headers.takeRight(k).map(_.hash))
    }
  }

  test("with the limit on eagerness the start selects at most `k` blocks past the store's tip") {
    restarted(Some(Gsm.Phase.Syncing)).map { (started, headers) =>
      assertEquals(started.tip, headers(4).point, "the resumed tip, `k` past the store's")
      assertEquals(started.blocks.map(_._1.hash), headers.slice(2, 5).map(_.hash))
    }
  }
end CopyLagRestartSuite
