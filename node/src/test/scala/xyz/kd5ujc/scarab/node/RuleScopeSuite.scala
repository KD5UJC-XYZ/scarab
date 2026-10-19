// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, NodeError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class RuleScopeSuite extends FailsOnUncaught:
  test("mirror rules created in an expired scope reject in each new session scope") {
    for
      setup <- IO.blocking {
        NodeSetup
          .fromDirectory(Path.of("fixtures/eras/genesis"))
          .fold(error => fail(error.message), identity)
      }
      raw <- IO.blocking {
        val line = java.nio.file.Files
          .readAllLines(Path.of("fixtures/eras/blocks.hex"))
          .asScala
          .find(_.nonEmpty)
          .getOrElse(fail("empty era fixture"))
        Bytes.fromHex(line).fold(error => fail(error.message), identity)
      }
      header <- IO.fromEither(
        Block
          .decode(raw, setup.geometry.byron.epochSlots)
          .leftMap(error => new AssertionError(error.message))
          .map(_.header)
      )
      rules <- NodeScope.resource.use { _ =>
        IO.pure(
          new MirrorRules[IO](
            setup.praos,
            setup.globals,
            setup.byronRules,
            setup.handOver,
            setup.byronHistory
          )
        )
      }
      results <- List.fill(2)(()).traverse { _ =>
        Handle
          .allow[NodeError] {
            val caller = summon[Handle[IO, NodeError]]
            SessionEnd.scoped(caller) {
              rules(setup.mirrorStart, header, Bytes.empty).as(SessionEnd.Returned)
            }
          }
          .rescue(error => IO(fail(error.message)))
      }
    yield results.foreach {
      case SessionEnd.Rejected(error) => assertEquals(error.point, header.point)
      case other                      => fail(s"expected typed validation rejection, got $other")
    }
  }
