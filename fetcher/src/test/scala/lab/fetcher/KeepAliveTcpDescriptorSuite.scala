// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import lab.network.{KeepAliveBlockFetchSession, TcpLimits}
import scala.concurrent.duration.*

class KeepAliveTcpDescriptorSuite extends munit.FunSuite:
  private val limits = TcpFetchLimits.checked(TcpLimits.checked().toOption.get).toOption.get
  private val claims = "a" * 64
  private val text = Vector(
    "format\ttcp-direct-range-v2",
    "profile\tntn14-blockfetch-keepalive-short-v1",
    "peer\t127.0.0.1",
    "port\t12345",
    "networkLabel\tlocal",
    "networkMagic\t42",
    s"anchor\t9:${"09" * 32}",
    s"first\t10:${"10" * 32}",
    s"last\t20:${"20" * 32}",
    s"provenanceSha256\t$claims",
    "attribution\tlocal-script",
    "expectedCount\t-",
    "expectedPoints\t-",
    "bytePins\t-"
  ).mkString("", "\n", "\n")
  private def parse(
      policy: KeepAliveBlockFetchSession.Policy = KeepAliveBlockFetchSession.Policy()
  ) =
    KeepAliveTcpDirectRangeDescriptor.parse(text, claims, limits, policy).toOption.get

  test("strict v1 and explicit v2 parsers never auto-upgrade or downgrade") {
    val strict = text
      .replace("tcp-direct-range-v2", "tcp-direct-range-v1")
      .linesIterator
      .filterNot(_.startsWith("profile\t"))
      .mkString("", "\n", "\n")
    assert(TcpDirectRangeDescriptor.parse(text, claims, limits).isLeft)
    assert(KeepAliveTcpDirectRangeDescriptor.parse(strict, claims, limits).isLeft)
    val v1 = TcpDirectRangeDescriptor.parse(strict, claims, limits).toOption.get
    val v2 = parse()
    assertNotEquals(v1.identity.digest, v2.identity.digest)
    assert(
      v2.canonical.startsWith("tcp-direct-range-v2\nprofile\tntn14-blockfetch-keepalive-short-v1\n")
    )
    assert(v1.canonical.contains("keepAlive\tnone\nrouting\tstrict-protocol3\n"))
  }

  test("every configurable KeepAlive policy changes source identity") {
    val p = KeepAliveBlockFetchSession.Policy()
    val original = parse(p)
    Vector(
      p.copy(interval = 9.seconds),
      p.copy(response = 9.seconds),
      p.copy(finish = 4.seconds),
      p.copy(keepAliveQueueBytes = 1407),
      p.copy(keepAliveQueueFrames = 15),
      p.copy(maxOutgoingFrames = 255)
    )
      .foreach(changed => assertNotEquals(parse(changed).identity.digest, original.identity.digest))
    assert(original.canonical.contains("completion\tgraceful-required-v1\n"))
    assert(original.canonical.contains("writerFairness\talternating-ready-sdus-v1\n"))
    assert(original.canonical.contains("globalIngressBytes\t"))
  }

  test("duplicate profile, changed provenance and implicit profile reject") {
    Vector(
      text + "profile\tntn14-blockfetch-keepalive-short-v1\n",
      text.replace("profile\tntn14-blockfetch-keepalive-short-v1\n", ""),
      text.replace("ntn14-blockfetch-keepalive-short-v1", "automatic")
    )
      .foreach(value =>
        assert(KeepAliveTcpDirectRangeDescriptor.parse(value, claims, limits).isLeft)
      )
    assert(KeepAliveTcpDirectRangeDescriptor.parse(text, "b" * 64, limits).isLeft)
  }

  test(
    "scripted outgoing budget rejects under a five-second virtual watchdog without writes or appends"
  ) {
    import cats.effect.{IO, Ref, Resource}
    import cats.effect.unsafe.implicits.global
    import cats.effect.testkit.TestControl
    import cats.syntax.all.*
    val bounded = TcpFetchLimits.checked(limits.tcp, maxOutgoingBytes = 1).toOption.get
    val descriptor = KeepAliveTcpDirectRangeDescriptor
      .parse(text, claims, bounded, KeepAliveBlockFetchSession.Policy(maxOutgoingBytes = 1))
      .toOption
      .get
    val program = for
      writes <- Ref.of[IO, Int](0)
      appends <- Ref.of[IO, Int](0)
      transport = new lab.network.ByteTransport[IO]:
        def read = IO.never
        def write(bytes: lab.cbor.Bytes) = writes.update(_ + 1)
        def close = IO.unit
        def isClosed = IO.pure(false)
      empty = Snapshot(Vector.empty, Digests.text("empty"))
      store = new SegmentStore[IO]:
        def selectionIdentity = descriptor.spec.identity
        def sourceIdentity = descriptor.identity
        def snapshot = IO.pure(empty)
        def append(block: lab.chain.CardanoBlockIndex.IndexedBlock): IO[Snapshot] =
          appends.update(_ + 1) *> IO.raiseError(
            new AssertionError("append forbidden before budget rejection")
          )
      result <- KeepAliveTcpDirectRangeSource
        .fromConnection(descriptor, Resource.pure[IO, lab.network.ByteTransport[IO]](transport))
        .use(source => source.runOwned(Resource.pure[IO, SegmentStore[IO]](store)))
        .attempt
      _ = assert(
        result.toOption.exists(r => !r.complete && r.reason == "outgoingWireBudget"),
        result.toString
      )
      writeCount <- writes.get
      appendCount <- appends.get
      snapshot <- store.snapshot
    yield
      assertEquals(writeCount, 0)
      assertEquals(appendCount, 0)
      assertEquals(snapshot.records.size, 0)
    TestControl.executeEmbed(program.timeout(5.seconds)).unsafeToFuture()
  }

  // Actual Nio allocation/force/inspection/cleanup remains under MUnit's unchanged 30s guard.
  test("real Nio outgoing-budget rejection preserves zero writes and persisted records") {
    import cats.effect.{IO, Ref, Resource}
    import cats.effect.unsafe.implicits.global
    import cats.syntax.all.*
    import java.nio.file.Files
    import scala.jdk.CollectionConverters.*
    val bounded = TcpFetchLimits.checked(limits.tcp, maxOutgoingBytes = 1).toOption.get
    val descriptor = KeepAliveTcpDirectRangeDescriptor
      .parse(text, claims, bounded, KeepAliveBlockFetchSession.Policy(maxOutgoingBytes = 1))
      .toOption
      .get
    Resource
      .make(IO.blocking(Files.createTempDirectory("ka-outgoing-budget-")))(root =>
        IO.blocking {
          val files = Files.walk(root)
          try
            files
              .iterator()
              .asScala
              .toVector
              .sortBy(_.getNameCount)
              .reverse
              .foreach(Files.deleteIfExists(_))
          finally files.close()
        }
      )
      .use { root =>
        for
          writes <- Ref.of[IO, Int](0)
          transport = new lab.network.ByteTransport[IO]:
            def read = IO.never
            def write(bytes: lab.cbor.Bytes) = writes.update(_ + 1)
            def close = IO.unit
            def isClosed = IO.pure(false)
          result <- KeepAliveTcpDirectRangeSource
            .fromConnection(descriptor, Resource.pure[IO, lab.network.ByteTransport[IO]](transport))
            .use(source =>
              source.runOwned(
                NioSegmentStore.resource[IO](root, descriptor.spec, descriptor.identity, false)
              )
            )
            .attempt
          _ = assert(
            result.toOption.exists(r => !r.complete && r.reason == "outgoingWireBudget"),
            result.toString
          )
          count <- writes.get
          _ = assertEquals(count, 0)
          snapshot <- NioSegmentStore.inspect[IO](root, bounded.fetch)
          _ = assertEquals(snapshot.records.size, 0)
        yield ()
      }
      .unsafeToFuture()
  }
