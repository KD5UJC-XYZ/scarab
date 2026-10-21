// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.forge.ForgedChain

class BlockAdmissionsSuite extends FailsOnUncaught:
  test("the eleventh queued body is not published or persisted before native capacity admission") {
    for
      chain <- ForgedChain.forge(5000 until 5100)
      checked <- chain.headers
        .zip(chain.raws)
        .take(11)
        .traverse((h, raw) =>
          BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
        )
      _ <- IO(assertEquals(checked.size, 11))
      _ <- TestControl.executeEmbed {
        for
          pool <- BlockPool.create[IO]
          persisted <- Ref.of[IO, Vector[Bytes]](Vector.empty)
          done <- Deferred[IO, Unit]
          _ <- pool.admissions.resource(_ => IO.unit).use { _ =>
            pool.session.use { sender =>
              for
                _ <- sender.claim(checked.map(_.header.hash))
                _ <- sender
                  .fillDeliveredWith(checked.take(10))(b => persisted.update(_ :+ b.header.hash))
                _ <- Resource
                  .make(
                    (sender.fillDeliveredWith(checked.drop(10))(b =>
                      persisted.update(_ :+ b.header.hash)
                    ) *>
                      done.complete(())).start
                  )(_.cancel)
                  .use { blocked =>
                    for
                      _ <- IO.sleep(1.millis)
                      pending <- done.tryGet
                      visible <- pool.selectionSnapshot
                      writes <- persisted.get
                      _ <- IO {
                        assertEquals(pending, None)
                        assert(!visible.contains(checked.last.header.hash))
                        assertEquals(writes, Vector.empty)
                      }
                      _ <- blocked.cancel
                      first <- pool.admissions.next
                      _ <- IO(first match
                        case BlockAdmissions.Work.Add(taken, _) =>
                          assertEquals(
                            Some(taken.delivered.header.hash),
                            Some(checked.head.header.hash)
                          )
                        case other => fail(s"unexpected work: $other"))
                    yield ()
                  }
              yield ()
            }
          }
        yield ()
      }
    yield ()
  }
