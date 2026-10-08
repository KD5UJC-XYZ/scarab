// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, ExitCode, IO, Ref, Resource}
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.chain.CardanoBlockIndex
import lab.fetcher.{Digests, Limits, NioSegmentStore, Point, Snapshot}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Local acceptance evidence only. The frames are synthetic; retained block bytes are not
  * re-encoded. These tests deliberately make no claim of relay interoperability or body
  * authentication.
  */
class KeepAliveTcpChainFetchCommandSuite extends munit.FunSuite:
  private given runtime: IORuntime = IORuntime.builder().build()
  override def afterAll(): Unit = runtime.shutdown()
  import LocalTcpScript.*
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val fixtures = Vector(Path.of("fixtures/chain-fetch"), Path.of("../fixtures/chain-fetch"))
    .find(Files.isDirectory(_))
    .getOrElse(throw new IllegalStateException("missing chain-fetch fixtures"))
  private val inspectLimits = right(Limits.checked(4, 1048576, 4194304L, 8388608L, 256, 45))
  private final case class Range(anchor: String, points: Vector[String], raws: Vector[Bytes]):
    def first: String = points.head
    def last: String = points.last
  private def range(era: String): Range =
    val raw = (0 until 4)
      .map(i => Bytes.fromArray(Files.readAllBytes(fixtures.resolve(s"$era/$era-$i.cbor"))))
      .toVector
    // Read the endpoint claim only. No source.tsv size/hash manifest is loaded by the CLI or this helper.
    val anchor = Files
      .readAllLines(fixtures.resolve(s"$era.tsv"))
      .asScala
      .find(_.startsWith("after\t"))
      .get
      .drop(6)
    Range(anchor, raw.map(b => Point.of(right(CardanoBlockIndex.inspect(b, 1048576))).encoded), raw)
  private val temporary: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("tcp-cli-integration-")))(root =>
      IO.blocking {
        val walk = Files.walk(root)
        try
          walk
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(Files.deleteIfExists(_))
        finally walk.close()
      }
    )
  private final case class Inputs(
      root: Path,
      descriptor: Path,
      provenance: Path,
      output: Path,
      range: Range,
      port: Int
  ):
    def args(resume: Boolean = false): List[String] = List(
      "run",
      "--peer",
      "127.0.0.1",
      "--port",
      port.toString,
      "--network-magic",
      "42",
      "--source-descriptor",
      descriptor.toString,
      "--source-provenance",
      provenance.toString,
      "--after",
      range.anchor,
      "--first",
      range.first,
      "--last",
      range.last,
      "--output",
      output.toString
    ) ++
      (if resume then List("--resume") else Nil)
  private def inputs(root: Path, port: Int, r: Range, pins: String = "-"): IO[Inputs] =
    IO.blocking {
      val provenance = root.resolve("claims.txt")
      Files.writeString(
        provenance,
        "Controlled local-script endpoint claims from retained fixture headers. Synthetic handshake magic 42. No network or body authentication.\n"
      )
      val descriptor = root.resolve("range.tsv")
      val fields = Vector(
        "format" -> "tcp-direct-range-v2",
        "profile" -> "ntn14-blockfetch-keepalive-short-v1",
        "peer" -> "127.0.0.1",
        "port" -> port.toString,
        "networkLabel" -> "local-test-unauthenticated",
        "networkMagic" -> "42",
        "anchor" -> r.anchor,
        "first" -> r.first,
        "last" -> r.last,
        "provenanceSha256" -> Digests.sha256(Files.readAllBytes(provenance)),
        "attribution" -> "local-script",
        "expectedCount" -> "-",
        "expectedPoints" -> "-",
        "bytePins" -> pins
      )
      Files.writeString(descriptor, fields.map((k, v) => s"$k\t$v").mkString("", "\n", "\n"))
      Inputs(root, descriptor, provenance, root.resolve("output"), r, port)
    }
  private final case class Run(code: ExitCode, out: Vector[String], err: Vector[String]):
    def json: String = out.mkString("\n")
  private def run(args: List[String]): IO[Run] = for
    out <- Ref.of[IO, Vector[String]](Vector.empty)
    err <- Ref.of[IO, Vector[String]](Vector.empty)
    code <- KeepAliveTcpChainFetchCommand.runWith(
      args,
      s => out.update(_ :+ s),
      s => err.update(_ :+ s)
    )
    stdout <- out.get
    stderr <- err.get
  yield Run(code, stdout, stderr)
  private def inspect(in: Inputs): IO[Snapshot] =
    NioSegmentStore.inspect[IO](in.output, inspectLimits)
  private def session(
      listener: Listener,
      in: Inputs,
      bytes: Bytes,
      resume: Boolean = false,
      fragment: Int = 65543,
      clientDone: Boolean = true
  ): IO[Run] =
    val peer = listener.accept.use { p =>
      for
        _ <- p.expect(0, proposal) *> p.send(accepted)
        requestCookie <- p.receive(8)
        _ <- IO(assert(requestCookie.value.take(2) == Vector(0x82.toByte, 0.toByte)))
        pong = Bytes(requestCookie.value.updated(1, 1.toByte))
        _ <- p.expect(3, request(in.range.first, in.range.last))
        // A genuine pending reply follows BatchDone in the same physical stream.
        _ <- p.send(join(bytes, frame(8, pong)), fragment)
        _ <- if clientDone then p.expect(8, hex("8102")) *> p.expect(3, hex("8101")) else IO.unit
        _ <- if clientDone then p.expectEof else p.expectEofOrKeepAliveDone
      yield ()
    }
    (run(in.args(resume)), peer.attempt).parTupled.flatMap { (result, peerResult) =>
      peerResult.fold(
        e =>
          IO.raiseError(
            new AssertionError(
              s"peer failure; code=${result.code.code}; stderr=${result.err.mkString(";")}",
              e
            )
          ),
        _ => IO.pure(result)
      )
    }
  private def assertCode(result: Run, expected: Int): Unit =
    assertEquals(result.code.code, expected, result.out.mkString("\n") + result.err.mkString("\n"))
  private def truth(result: Run, pinCount: Int = 0): Unit =
    for field <- Vector(
        "networkAuthenticated",
        "ledgerValidated",
        "consensusValidated",
        "mithrilAuthenticated",
        "referenceReplayChecked",
        "bodyCommitmentsValidated",
        "relayInteropEstablished",
        "generalMuxDispatcher"
      )
    do
      assert(
        result.json.contains(s"\"$field\":false"),
        s"missing truthful flag $field: ${result.json}"
      )
    assert(result.json.contains("tcp-direct-range-report-v2"))
    assert(result.json.contains("ntn14-blockfetch-keepalive-short-v1"))
    assert(result.json.contains(s"\"independentBytePins\":$pinCount"))
    assert(result.json.contains(s"\"independentBytePinsMatched\":${pinCount > 0}"))
    assert(result.json.contains("\"externalEndpointExecutedHere\":false"))
    assert(result.json.contains("local-test-unauthenticated"))
  private def verifyRecords(in: Inputs, snap: Snapshot): IO[Unit] = IO.blocking {
    assertEquals(snap.records.map(_.point.encoded), in.range.points)
    snap.records.zip(in.range.raws).foreach { (record, raw) =>
      assertEquals(record.rawHash, Digests.sha256(raw.toArray))
      assertEquals(record.size, raw.size)
      assertEquals(
        Bytes.fromArray(Files.readAllBytes(in.output.resolve(s"objects/${record.rawHash}.cbor"))),
        raw
      )
    }
  }
  // An outer timeout is solely a deadlock guard, never a latency assertion.
  private def guarded[A](io: IO[A]) = io.timeout(70.seconds).unsafeToFuture()

  for era <- Vector("shelley", "babbage") do
    test(
      s"$era pending KeepAlive response after BatchDone closes both protocols before full replay"
    ) {
      guarded((temporary, listener).tupled.use { (root, server) =>
        for
          in <- inputs(root, server.port, range(era))
          first <- session(server, in, response(in.range.raws), fragment = 97)
          _ <- IO {
            assertCode(first, 0); truth(first);
            assert(first.json.contains("\"keepAliveImplemented\":true"))
          }
          before <- inspect(in)
          _ <- verifyRecords(in, before)
          second <- session(server, in, response(in.range.raws), resume = true, fragment = 97)
          after <- inspect(in)
          _ <- IO { assertCode(second, 0); assertEquals(after, before) }
        yield ()
      })
    }

  test("BF suffix after BatchDone rejects despite a valid routed KeepAlive reply") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        result <- session(
          server,
          in,
          join(response(in.range.raws), frames(hex("8105"))),
          clientDone = false
        )
        _ <- IO(assertCode(result, 4))
        snap <- inspect(in)
        _ <- IO(assertEquals(snap.records.size, 0))
      yield ()
    })
  }

  test("strict descriptor is refused by explicit KeepAlive command before connection") {
    guarded(temporary.use { root =>
      for
        in <- inputs(root, 12345, range("shelley"))
        _ <- IO.blocking {
          val text = Files
            .readString(in.descriptor)
            .replace("tcp-direct-range-v2", "tcp-direct-range-v1")
            .linesIterator
            .filterNot(_.startsWith("profile\t"))
            .mkString("", "\n", "\n")
          Files.writeString(in.descriptor, text)
        }
        result <- run(in.args())
        _ <- IO(assertCode(result, 2))
      yield ()
    })
  }

  test("v1 store identity refuses v2 resume before connection") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        _ <- IO.blocking {
          val v2 = Files.readString(in.descriptor)
          Files.writeString(
            in.descriptor,
            v2.replace("tcp-direct-range-v2", "tcp-direct-range-v1")
              .linesIterator
              .filterNot(_.startsWith("profile\t"))
              .mkString("", "\n", "\n")
          )
        }
        out <- Ref.of[IO, Vector[String]](Vector.empty)
        err <- Ref.of[IO, Vector[String]](Vector.empty)
        peer = server.accept.use { p =>
          p.expect(0, proposal) *> p.send(accepted) *> p.expect(
            3,
            request(in.range.first, in.range.last)
          ) *>
            p.send(response(in.range.raws)) *> p.expect(3, hex("8101")) *> p.expectEof
        }
        first <- (
          TcpChainFetchCommand.runWith(in.args(), s => out.update(_ :+ s), s => err.update(_ :+ s)),
          peer
        ).parTupled
        _ <- IO(assertEquals(first._1.code, 0))
        _ <- inputs(root, server.port, in.range)
        opens <- Ref.of[IO, Int](0)
        resumed <- KeepAliveTcpChainFetchCommand.runUsing(
          in.args(resume = true),
          _ => IO.unit,
          _ => IO.unit,
          d =>
            lab.fetcher.KeepAliveTcpDirectRangeSource.fromConnection[IO](
              d,
              Resource.eval(
                opens.update(_ + 1) *> IO.raiseError[lab.network.ByteTransport[IO]](
                  new AssertionError("v1 resume opened connector")
                )
              )
            )
        )
        count <- opens.get
        _ <- IO { assertEquals(resumed.code, 6); assertEquals(count, 0) }
      yield ()
    })
  }

  private def alteredBody(raw: Bytes): Bytes =
    // Synthetic negative control only: replace the empty auxiliary-data map with {0: h'00'}.
    // Preserve original header exactly. This is NOT claimed to be a valid Cardano block.
    assertEquals(raw.value.last, 0xa0.toByte)
    val changed = join(Bytes(raw.value.dropRight(1)), hex("a1004100"))
    val before = right(CardanoBlockIndex.inspect(raw, 1048576))
    val after = right(CardanoBlockIndex.inspect(changed, 1048576))
    assertEquals(after.headerHash, before.headerHash)
    assertEquals(after.slot, before.slot)
    assertNotEquals(after.rawSha256, before.rawSha256)
    changed

  test(
    "same-header synthetic body alteration is structural-only initially and rejected on committed resume"
  ) {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        initial <- session(server, in, response(in.range.raws))
        _ <- IO(assertCode(initial, 0))
        before <- inspect(in)
        changed = in.range.raws.updated(0, alteredBody(in.range.raws.head))
        resumed <- session(server, in, response(changed), resume = true)
        after <- inspect(in)
        _ <- IO { assertCode(resumed, 5); assertEquals(after, before) }
        other <- IO.blocking(Files.createDirectory(root.resolve("unseen")))
        fresh <- inputs(other, server.port, in.range.copy(raws = changed))
        admitted <- session(server, fresh, response(changed))
        _ <- IO { assertCode(admitted, 0); truth(admitted) }
        snap <- inspect(fresh)
        _ <- verifyRecords(fresh, snap)
      yield ()
    })
  }

  test("independent partial byte pin matches and mismatched original bytes cannot commit") {
    val r = range("shelley")
    val pin = s"${r.first},${r.raws.head.size},${Digests.sha256(r.raws.head.toArray)}"
    guarded(Vector(false, true).traverse_ { mismatch =>
      (temporary, listener).tupled.use { (root, server) =>
        for
          in <- inputs(root, server.port, r, pin)
          raws = if mismatch then r.raws.updated(0, alteredBody(r.raws.head)) else r.raws
          result <- session(server, in, response(raws), clientDone = !mismatch)
          _ <- IO {
            assertCode(result, if mismatch then 5 else 0); if !mismatch then truth(result, 1)
          }
          snap <- inspect(in)
          _ <- IO(assertEquals(snap.records.size, if mismatch then 0 else 4))
        yield ()
      }
    })
  }

  test("changed endpoint or limits refuse resume before opening a connector") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        first <- session(server, in, response(in.range.raws))
        _ <- IO(assertCode(first, 0))
        before <- inspect(in)
        opens <- Ref.of[IO, Int](0)
        invoke = (args: List[String]) =>
          KeepAliveTcpChainFetchCommand.runUsing(
            args,
            _ => IO.unit,
            _ => IO.unit,
            d =>
              lab.fetcher.KeepAliveTcpDirectRangeSource.fromConnection[IO](
                d,
                Resource.eval(
                  opens.update(_ + 1) *> IO.raiseError[lab.network.ByteTransport[IO]](
                    new AssertionError("changed binding opened connector")
                  )
                )
              )
          )
        limitChange <- invoke(in.args(resume = true) ++ List("--job-seconds", "44"))
        _ <- IO(assertEquals(limitChange.code, 6))
        descriptor <- IO.blocking(Files.readString(in.descriptor))
        port = if server.port == 65535 then 65534 else server.port + 1
        _ <- IO.blocking(
          Files.writeString(
            in.descriptor,
            descriptor.replace(s"port\t${server.port}\n", s"port\t$port\n")
          )
        )
        endpointChange <- invoke(in.copy(port = port).args(resume = true))
        _ <- IO(assertEquals(endpointChange.code, 6))
        count <- opens.get
        after <- inspect(in)
        _ <- IO { assertEquals(count, 0); assertEquals(after, before) }
      yield ()
    })
  }

  test("failed KeepAlive Done write or cleanup cannot expose a cursor or mutate checkpoint") {
    guarded(Vector(false, true).traverse_ { cleanup =>
      (temporary, listener).tupled.use { (root, server) =>
        for
          in <- inputs(root, server.port, range("shelley"))
          errors <- Ref.of[IO, Vector[String]](Vector.empty)
          client = KeepAliveTcpChainFetchCommand.runUsing(
            in.args(),
            _ => IO.unit,
            s => errors.update(_ :+ s),
            d =>
              lab.fetcher.KeepAliveTcpDirectRangeSource.fromConnection[IO](
                d,
                lab.network.AsyncTcpTransport.resource[IO](d.peer, d.limits.tcp).flatMap { raw =>
                  val wrapped = new lab.network.ByteTransport[IO]:
                    def read = raw.read
                    def close = raw.close
                    def isClosed = raw.isClosed
                    def write(bytes: Bytes) =
                      if !cleanup && bytes == hex("00000000000800028102") then
                        IO.raiseError(new IllegalStateException("injected Done write failure"))
                      else raw.write(bytes)
                  Resource.make(IO.pure(wrapped))(_ =>
                    if cleanup then
                      IO.raiseError(new IllegalStateException("injected cleanup failure"))
                    else IO.unit
                  )
                }
              )
          )
          peer = server.accept.use { p =>
            for
              _ <- p.expect(0, proposal) *> p.send(accepted)
              cookie <- p.receive(8)
              _ <- p.expect(3, request(in.range.first, in.range.last))
              _ <- p.send(
                join(response(in.range.raws), frame(8, Bytes(cookie.value.updated(1, 1.toByte))))
              )
              _ <- if cleanup then p.expect(8, hex("8102")) *> p.expect(3, hex("8101")) else IO.unit
              _ <- p.expectEof
            yield ()
          }
          result <- observeBoth(client, peer, (c: ExitCode) => s"exit=${c.code}")
          messages <- errors.get
          _ <- IO {
            assertEquals(result.code, 4, messages.mkString(";"));
            assert(messages.exists(_.contains("injected")))
          }
          snap <- inspect(in)
          _ <- IO(assertEquals(snap.records.size, 0))
        yield ()
      }
    })
  }
