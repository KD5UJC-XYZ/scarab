// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockFailure, BlockPoolProblem, Header, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Actual signed originals enter the pool through the same checked admission as the network. These
  * tests exercise causal ownership and retained policy state, not synthetic string errors.
  */
class BlockPoolPunishmentSuite extends FailsOnUncaught:
  private def first(chain: ForgedChain): IO[(Header, Bytes)] =
    chain.headers
      .zip(chain.raws)
      .headOption
      .fold(IO(fail("the forged chain has no original")))(IO.pure)

  private def delivered(block: (Header, Bytes)): IO[BlockPool.Delivered] =
    BlockPool.Delivered.checked(block._1, block._2).fold(error => IO(fail(error.message)), IO.pure)

  private def refusal(header: Header): ValidationError =
    ValidationError(header.point, BlockFailure.EpochPrecedesLedger(0))

  private def context(claims: BlockPool.Claims[IO], header: Header): IO[BlockPool.Taken] =
    claims.takeAddition(header).flatMap {
      case Right(Some(value)) => IO.pure(value)
      case other              => IO(fail(s"expected a first body-admission context, got $other"))
    }

  test(
    "another validator consumes the first sender's admission once; a duplicate creates no new cause"
  ) {
    for
      forged <- ForgedChain.forge(5000 until 5060)
      block <- first(forged)
      checked <- delivered(block)
      pool <- BlockPool.create[IO]
      punished <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      _ <- (pool.session(Some(error => punished.update(_ :+ error))), pool.session).tupled.use {
        (sender, validator) =>
          for
            _ <- sender.claim(Vector(block._1.hash)) *> sender.fillDelivered(Vector(checked))
            added <- context(validator, block._1)
            _ <- IO(assert(!added.own))
            result <- validator.reject(added, block._1.point, refusal(block._1), Point.Origin)
            _ <- IO(assertEquals(result, Right(BlockPool.Rejection.Forgiven(added.origin))))
            duplicate <- validator.claim(Vector(block._1.hash))
            _ <- IO(assertEquals(duplicate, Vector(false)))
            _ <- validator.fillDelivered(Vector(checked))
            repeated <- sender.takeAddition(block._1)
            cached <- pool.take(block._1.hash)
            reports <- punished.get
            _ <- IO {
              assertEquals(repeated, Right(None))
              assertEquals(cached, Some(block._2))
              assertEquals(reports, Vector.empty)
            }
          yield ()
      }
    yield ()
  }

  test(
    "forgiving one invalid tip retains other originals and a same-height issuer repeat punishes its sender"
  ) {
    for
      forks <- ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)
      original <- first(forks._1)
      competing <- first(forks._2)
      _ <- IO(assertNotEquals(original._1.hash, competing._1.hash))
      firstBody <- delivered(original)
      repeatBody <- delivered(competing)
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      _ <- pool.session(Some(error => reports.update(_ :+ error))).use { sender =>
        for
          _ <- sender.claim(Vector(original._1.hash, competing._1.hash))
          _ <- sender.fillDelivered(Vector(firstBody, repeatBody))
          firstContext <- context(sender, original._1)
          firstResult <- sender.reject(
            firstContext,
            original._1.point,
            refusal(original._1),
            Point.Origin
          )
          secondContext <- context(sender, competing._1)
          secondResult <- sender.reject(
            secondContext,
            competing._1.point,
            refusal(competing._1),
            Point.Origin
          )
          originals <- Vector(original, competing).traverse(block => pool.take(block._1.hash))
          actualReports <- reports.get
          _ <- IO {
            assertEquals(firstResult, Right(BlockPool.Rejection.Forgiven(firstContext.origin)))
            assertEquals(secondResult, Right(BlockPool.Rejection.Punished(secondContext.origin)))
            assertEquals(actualReports, Vector(refusal(competing._1)))
            assertEquals(originals, Vector(Some(original._2), Some(competing._2)))
          }
        yield ()
      }
    yield ()
  }

  test("explicit diffusion-off punishes without deleting received bodies") {
    for
      forged <- ForgedChain.forge(5000 until 5060)
      block <- first(forged)
      checked <- delivered(block)
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      _ <- pool.session(Some(error => reports.update(_ :+ error)), BlockPool.Diffusion.Off).use {
        sender =>
          for
            _ <- sender.claim(Vector(block._1.hash)) *> sender.fillDelivered(Vector(checked))
            added <- context(sender, block._1)
            result <- sender.reject(added, block._1.point, refusal(block._1), Point.Origin)
            original <- pool.take(block._1.hash)
            actualReports <- reports.get
            _ <- IO {
              assertEquals(result, Right(BlockPool.Rejection.Punished(added.origin)))
              assertEquals(actualReports, Vector(refusal(block._1)))
              assertEquals(original, Some(block._2))
            }
          yield ()
      }
    yield ()
  }

  test("the adding descendant is punished when validation discovers an invalid prefix") {
    for
      forged <- ForgedChain.forge(5000 until 5060)
      prefix <- first(forged)
      descendant <- forged.headers
        .zip(forged.raws)
        .lift(1)
        .fold(IO(fail("the forged descendant is missing")))(IO.pure)
      deliveredBodies <- Vector(prefix, descendant).traverse(delivered)
      pool <- BlockPool.create[IO]
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      _ <- pool.session(Some(error => reports.update(_ :+ error))).use { sender =>
        for
          _ <- sender
            .claim(deliveredBodies.map(_.header.hash)) *> sender.fillDelivered(deliveredBodies)
          added <- context(sender, descendant._1)
          result <- sender.reject(added, descendant._1.point, refusal(prefix._1), Point.Origin)
          actual <- reports.get
          originals <- Vector(prefix, descendant).traverse(block => pool.take(block._1.hash))
          _ <- IO {
            assertEquals(result, Right(BlockPool.Rejection.Punished(added.origin)))
            assertEquals(actual, Vector(refusal(prefix._1)))
            assertEquals(originals, Vector(Some(prefix._2), Some(descendant._2)))
          }
        yield ()
      }
    yield ()
  }

  test("opaque helper data cannot silently enter the native semantic-forgiveness path") {
    for
      forged <- ForgedChain.forge(5000 until 5060)
      block <- first(forged)
      pool <- BlockPool.create[IO]
      _ <- pool.session.use { claims =>
        for
          _ <- claims.claim(Vector(block._1.hash)) *> claims.fill(Vector(block._1.hash -> block._2))
          taken <- claims
            .take(block._1.hash)
            .flatMap(_.fold(IO(fail("the helper original vanished")))(IO.pure))
          rejected <- claims.reject(taken, block._1.point, refusal(block._1), Point.Origin)
        yield assertEquals(rejected, Left(BlockPoolProblem.MissingDeliveredHeader(block._1.point)))
      }
    yield ()
  }

  test(
    "known-invalid originals outlive source expiry and are collected only below the native cutoff"
  ) {
    for
      forged <- ForgedChain.forge(5000 until 5060)
      block <- first(forged)
      checked <- delivered(block)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), BlockPool.AttributionPolicy(retained = 0))
      _ <- pool.session.use { sender =>
        for
          _ <- sender.claim(Vector(block._1.hash)) *> sender.fillDelivered(Vector(checked))
          taken <- context(sender, block._1)
          result <- sender.invalidated(taken, block._1, refusal(block._1))
          _ <- IO(assertEquals(result, Right(())))
        yield ()
      }
      _ <- pool.reap
      before <- pool.invalidBlocks.snapshot
      retained <- pool.take(block._1.hash)
      _ <- pool.invalidBlocks.pruneBefore(block._1.slot)
      atCutoff <- pool.take(block._1.hash)
      _ <- pool.invalidBlocks.pruneBefore(block._1.slot + 1)
      after <- pool.invalidBlocks.snapshot
      collected <- pool.take(block._1.hash)
      _ <- IO {
        assertEquals(retained, Some(block._2))
        assertEquals(atCutoff, Some(block._2))
        assertEquals(collected, None)
        assertEquals(before.entries, Map(block._1.hash -> refusal(block._1)))
        assertEquals(after.entries, Map.empty)
        assertEquals(after.revision, before.revision)
      }
    yield ()
  }

  test("a missing body has no context to consume; its later actual admission remains causal") {
    for
      forged <- ForgedChain.forge(5000 until 5060)
      block <- first(forged)
      checked <- delivered(block)
      pool <- BlockPool.create[IO]
      _ <- pool.session.use { claims =>
        for
          absent <- claims.takeAddition(block._1)
          _ <- claims.claim(Vector(block._1.hash)) *> claims.fillDelivered(Vector(checked))
          present <- context(claims, block._1)
          consumed <- claims.takeAddition(block._1)
        yield
          assertEquals(absent, Right(None))
          assertEquals(present.metadata.map(_.header.point), Some(block._1.point))
          assertEquals(consumed, Right(None))
      }
    yield ()
  }
