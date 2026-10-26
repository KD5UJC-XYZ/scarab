// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO

import ProtocolResults.{Control, Entry, Result, Snapshot, Temperature}

/** The first/all completion reads and retained results of PeerStateActions at 2fb69829. */
class ProtocolResultsSuite extends FailsOnUncaught:
  private val error = NetworkError.Timeout(Protocols.BlockFetch, "streaming")

  test("first-to-finish gives established results precedence over hot results") {
    for
      results <- ProtocolResults.create[IO, Unit]
      hot <- results.startHot(transactions = true)
      established <- results.start(Protocols.KeepAlive, Temperature.Established)
      _ <- hot.blockFetch.complete(Left(error))
      _ <- established.complete(Right(()))
      snapshot <- results.snapshot
    yield assertEquals(
      snapshot.first,
      Some((Protocols.KeepAlive, Temperature.Established, Right(())))
    )
  }

  test("retry follows selection: an inactive lower-number handle masks the rest of that bundle") {
    val entries = Map(
      Protocols.ChainSync -> Entry(Temperature.Hot, 1, Result.NotRunning(Right(()))),
      Protocols.BlockFetch -> Entry(Temperature.Hot, 1, Result.Errored(error))
    )
    val snapshot = Snapshot[IO, Unit](entries, Map.empty, IO.unit)
    assertEquals(snapshot.first, None)
    assertEquals(
      snapshot
        .copy(entries =
          entries.updated(Protocols.ChainSync, Entry(Temperature.Hot, 1, Result.Running))
        )
        .first,
      Some((Protocols.BlockFetch, Temperature.Hot, Left(error)))
    )
  }

  test("all-results waits for the last running application and retains its error") {
    for
      results <- ProtocolResults.create[IO, Unit]
      hot <- results.startHot(transactions = false)
      _ <- hot.chainSync.complete(Right(()))
      before <- results.snapshot
      _ <- hot.blockFetch.complete(Left(error))
      all <- results.awaitAll(Set(Temperature.Hot))
    yield
      assertEquals(before.entries(Protocols.BlockFetch).result, Result.Running)
      assertEquals(
        all,
        ProtocolResults.LastToFinish.SomeErrored(
          cats.data.NonEmptyVector.one(
            ProtocolResults.MiniProtocolFailure(Protocols.BlockFetch, error)
          )
        )
      )
  }

  test("retiring keeps completed values but does not produce another hot completion") {
    for
      results <- ProtocolResults.create[IO, Unit]
      hot <- results.startHot(transactions = false)
      _ <- hot.chainSync.complete(Right(()))
      _ <- hot.blockFetch.complete(Right(()))
      _ <- results.terminateHot
      _ <- results.awaitAll(Set(Temperature.Hot))
      _ <- results.retireHot
      snapshot <- results.snapshot
    yield
      assertEquals(snapshot.first, None)
      assert(snapshot.entries.values.forall(_.result == Result.NotRunning(Right(()))))
      assertEquals(snapshot.controls(Temperature.Hot), Control.Terminate)
      assertEquals(snapshot.controls(Temperature.Warm), Control.Continue)
  }

  test("a late result from an earlier activation cannot finish a replacement protocol") {
    for
      results <- ProtocolResults.create[IO, Unit]
      old <- results.startHot(transactions = false)
      _ <- results.activateHot
      fresh <- results.startHot(transactions = false)
      _ <- old.chainSync.complete(Left(error))
      _ <- old.blockFetch.complete(Right(()))
      before <- results.snapshot
      _ <- fresh.blockFetch.complete(Right(()))
      after <- results.snapshot
    yield
      assertEquals(before.first, None)
      assertEquals(before.entries.values.map(_.generation).toSet.size, 1)
      assertEquals(after.first, Some((Protocols.BlockFetch, Temperature.Hot, Right(()))))
      assertEquals(after.controls(Temperature.Warm), Control.Quiesce)
  }

  test("retained typed values are replaced with the entire next hot bundle") {
    final case class ApplicationReturn(sequence: BigInt, context: Vector[Int])
    val returned = ApplicationReturn(BigInt(42), Vector(3, 7))
    for
      results <- ProtocolResults.create[IO, ApplicationReturn]
      old <- results.startHot(transactions = true)
      _ <- old.chainSync.complete(Right(returned))
      _ <- old.blockFetch.complete(Right(returned))
      _ <- old.transactions.fold(IO.unit)(_.complete(Right(returned)))
      _ <- results.retireHot
      retained <- results.snapshot
      _ <- results.startHot(transactions = false)
      fresh <- results.snapshot
    yield
      assertEquals(retained.entries(Protocols.ChainSync).result, Result.NotRunning(Right(returned)))
      assertEquals(fresh.entries.keySet, Set(Protocols.ChainSync, Protocols.BlockFetch))
      assertEquals(fresh.first, None)
  }

  test("all-results preserves every failing protocol and its original typed cause") {
    val other = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
    for
      results <- ProtocolResults.create[IO, Unit]
      hot <- results.startHot(transactions = false)
      _ <- hot.chainSync.complete(Left(other))
      _ <- hot.blockFetch.complete(Left(error))
      all <- results.awaitAll(Set(Temperature.Hot))
    yield assertEquals(
      all,
      ProtocolResults.LastToFinish.SomeErrored(
        cats.data.NonEmptyVector.of(
          ProtocolResults.MiniProtocolFailure(Protocols.ChainSync, other),
          ProtocolResults.MiniProtocolFailure(Protocols.BlockFetch, error)
        )
      )
    )
  }

  test("starting a prepared bundle cannot revive a terminate control") {
    for
      results <- ProtocolResults.create[IO, Unit]
      _ <- results.activateHot
      _ <- results.terminateAll
      _ <- results.startHot(transactions = false)
      snapshot <- results.snapshot
      _ <- results.awaitTermination(Temperature.Hot)
    yield assertEquals(snapshot.controls.values.toSet, Set(Control.Terminate))
  }

  test("closing combines failures in hot, warm, established order before protocol number") {
    val warmError = NetworkError.Timeout(Protocols.PeerSharing, "busy")
    val establishedError = NetworkError.Timeout(Protocols.KeepAlive, "client")
    for
      results <- ProtocolResults.create[IO, Unit]
      hot <- results.startHot(transactions = false)
      warm <- results.start(Protocols.PeerSharing, Temperature.Warm)
      established <- results.start(Protocols.KeepAlive, Temperature.Established)
      _ <- hot.chainSync.complete(Left(error))
      _ <- hot.blockFetch.complete(Left(error))
      _ <- warm.complete(Left(warmError))
      _ <- established.complete(Left(establishedError))
      all <- results.awaitAll(Temperature.values.toSet)
    yield assertEquals(
      all,
      ProtocolResults.LastToFinish.SomeErrored(
        cats.data.NonEmptyVector.of(
          ProtocolResults.MiniProtocolFailure(Protocols.ChainSync, error),
          ProtocolResults.MiniProtocolFailure(Protocols.BlockFetch, error),
          ProtocolResults.MiniProtocolFailure(Protocols.PeerSharing, warmError),
          ProtocolResults.MiniProtocolFailure(Protocols.KeepAlive, establishedError)
        )
      )
    )
  }
