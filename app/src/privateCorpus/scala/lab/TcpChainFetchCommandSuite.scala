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
class TcpChainFetchCommandSuite extends munit.FunSuite:
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
        "format" -> "tcp-direct-range-v1",
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
    code <- TcpChainFetchCommand.runWith(args, s => out.update(_ :+ s), s => err.update(_ :+ s))
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
      p.expect(0, proposal) *> p
        .send(accepted) *> p.expect(3, request(in.range.first, in.range.last)) *>
        p.send(bytes, fragment) *> (if clientDone then p.expect(3, hex("8101"))
                                    else IO.unit) *> p.expectEof
    }
    observeBoth(
      run(in.args(resume)),
      peer,
      r => s"exit=${r.code.code}; stdout=${r.out.mkString(";")}; stderr=${r.err.mkString(";")}"
    )
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
        "keepAliveImplemented",
        "generalMuxDispatcher"
      )
    do
      assert(
        result.json.contains(s"\"$field\":false"),
        s"missing truthful flag $field: ${result.json}"
      )
    assert(result.json.contains("tcp-direct-range-report-v1"))
    assert(result.json.contains("ntn14-blockfetch-short-strict-v1"))
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

  for era <- Vector("shelley", "allegra", "babbage") do
    test(s"$era unknown payload download preserves bytes and reacquires full resume") {
      guarded((temporary, listener).tupled.use { (root, server) =>
        for
          in <- inputs(root, server.port, range(era))
          first <- session(server, in, response(in.range.raws), fragment = 97)
          _ <- IO { assertCode(first, 0); truth(first) }
          before <- inspect(in)
          _ <- verifyRecords(in, before)
          second <- session(server, in, response(in.range.raws), resume = true, fragment = 97)
          after <- inspect(in)
          _ <- IO { assertCode(second, 0); truth(second); assertEquals(after, before) }
        yield ()
      })
    }

  test("no checkpoint objects are committed before independently released BatchDone") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        sent <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        peer = server.accept.use { p =>
          p.expect(0, proposal) *> p.send(accepted) *> p
            .expect(3, request(in.range.first, in.range.last)) *>
            p.send(response(in.range.raws, terminal = false)) *> sent
              .complete(())
              .void *> release.get *>
            p.send(frames(hex("8105"))) *> p.expect(3, hex("8101")) *> p.expectEof
        }
        observer = sent.get *> IO
          .blocking {
            val objects = Files.list(in.output.resolve("objects"))
            try assertEquals(objects.count(), 0L)
            finally objects.close()
          }
          .guarantee(release.complete(()).void)
        all <- (run(in.args()), peer, observer).parTupled
        _ <- IO(assertCode(all._1, 0))
      yield ()
    })
  }

  test("NoBlocks, wrong endpoint, missing BatchDone, malformed profile and protocol8 fail closed") {
    val r = range("shelley")
    val cases = Vector(
      ("NoBlocks", accepted, frames(hex("8103")), true, false, 3),
      ("wrong first endpoint", accepted, response(r.raws.tail), true, false, 5),
      ("missing BatchDone", accepted, response(r.raws, terminal = false), true, false, 4),
      ("query profile", frame(0, hex("83010e84182af500f5")), Bytes.empty, false, false, 4),
      ("wrong version", frame(0, hex("83010d84182af500f4")), Bytes.empty, false, false, 4),
      ("KeepAlive is unsupported", accepted, frames(hex("820001"), 8), true, false, 4),
      ("ChainSync unsupported", accepted, frames(hex("8102"), 2), true, false, 4),
      ("wrong direction", accepted, frame(3, hex("8102"), responder = false), true, false, 4),
      ("wrong magic", frame(0, hex("83010e84182bf500f4")), Bytes.empty, false, false, 4),
      ("handshake EOF", Bytes.empty, Bytes.empty, false, false, 4),
      ("oversized length", accepted, frames(hex("81028204d8185a00100001")), true, false, 4),
      ("malformed wrapper", accepted, frames(hex("810282044100")), true, false, 4),
      ("short batch", accepted, response(r.raws.take(3)), true, false, 3),
      ("extra block", accepted, response(r.raws :+ r.raws.last), true, false, 5),
      (
        "reordered",
        accepted,
        response(Vector(r.raws(1), r.raws(0), r.raws(2), r.raws(3))),
        true,
        false,
        5
      ),
      (
        "duplicated",
        accepted,
        response(Vector(r.raws.head, r.raws.head, r.raws(2), r.raws(3))),
        true,
        false,
        5
      )
    )
    guarded(cases.traverse_ { (label, handshake, wire, expectRequest, clientDone, code) =>
      (temporary, listener).tupled.use { (root, server) =>
        for
          in <- inputs(root, server.port, r)
          peer = server.accept.use { p =>
            p.expect(0, proposal) *> p.send(handshake) *>
              (if expectRequest then p.expect(3, request(r.first, r.last)) else IO.unit) *>
              p.send(wire) *> (if clientDone then p.expect(3, hex("8101"))
                               else p.endOutput) *> p.expectEof
          }
          pair <- (run(in.args()), peer).parTupled
          _ <- IO(assertCode(pair._1, code))
          snap <- inspect(in)
          _ <- IO(assertEquals(snap.records.size, 0, label))
        yield ()
      }
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

  test(
    "preflight rejects malformed descriptors, provenance, duplicate options and selectors before connector"
  ) {
    guarded(temporary.use { root =>
      for
        in <- inputs(root, 12345, range("shelley"))
        original <- IO.blocking(Files.readString(in.descriptor))
        opens <- Ref.of[IO, Int](0)
        invoke = (args: List[String]) =>
          TcpChainFetchCommand.runUsing(
            args,
            _ => IO.unit,
            _ => IO.unit,
            d =>
              lab.fetcher.TcpDirectRangeSource.fromConnection[IO](
                d,
                Resource.eval(
                  opens.update(_ + 1) *> IO.raiseError[lab.network.ByteTransport[IO]](
                    new AssertionError("preflight opened connector")
                  )
                )
              )
          )
        descriptors = Vector(
          "x" * 65537,
          original + "unknown\tvalue\n",
          original + "peer\t127.0.0.1\n",
          original.replace("format\ttcp-direct-range-v1", "format\ttcp-direct-range-v99"),
          original.replace("networkMagic\t42", "networkMagic\t4294967296"),
          original.replace("peer\t127.0.0.1", "peer\texample.invalid"),
          original.replace("bytePins\t-", s"bytePins\t${in.range.anchor},100,${"00" * 32}"),
          original.replace(
            "provenanceSha256\t" + Digests.sha256(Files.readAllBytes(in.provenance)),
            "provenanceSha256\t" + "00" * 32
          )
        )
        _ <- descriptors.traverse_ { text =>
          IO.blocking(Files.writeString(in.descriptor, text)) *> invoke(in.args()).flatMap(code =>
            IO(assertEquals(code.code, 2))
          )
        }
        _ <- IO.blocking(Files.writeString(in.descriptor, original))
        _ <- Vector(
          in.args() ++ List("--peer", "127.0.0.1"),
          in.args().updated(in.args().indexOf("42"), "43"),
          in.args().updated(in.args().indexOf(in.range.first), "18446744073709551616:" + "00" * 32),
          in.args().updated(in.args().indexOf("127.0.0.1"), "example.invalid"),
          in.args() ++ List("--max-blocks", "5"),
          List("run")
        ).traverse_(args => invoke(args).flatMap(code => IO(assertEquals(code.code, 2))))
        provenance <- IO.blocking(Files.readAllBytes(in.provenance))
        _ <- IO.blocking(Files.write(in.provenance, Array.fill[Byte](262145)(0)))
        oversizedProvenance <- invoke(in.args())
        _ <- IO(assertEquals(oversizedProvenance.code, 2))
        _ <- IO.blocking(Files.write(in.provenance, provenance))
        help <- invoke(List("--help"))
        _ <- IO(assertEquals(help.code, 0))
        count <- opens.get
        _ <- IO(assertEquals(count, 0))
      yield ()
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
          TcpChainFetchCommand.runUsing(
            args,
            _ => IO.unit,
            _ => IO.unit,
            d =>
              lab.fetcher.TcpDirectRangeSource.fromConnection[IO](
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

  test("singleton no-pin TCP range still waits for BatchDone and preserves original block") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      val full = range("shelley")
      val one = full.copy(points = full.points.take(1), raws = full.raws.take(1))
      for
        in <- inputs(root, server.port, one)
        result <- session(server, in, response(one.raws), fragment = 1)
        _ <- IO { assertCode(result, 0); truth(result) }
        snap <- inspect(in)
        _ <- verifyRecords(in, snap)
      yield ()
    })
  }

  test("partial committed prefix reacquires the entire TCP range and compares overlap") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        tcp <- IO.fromEither(lab.network.TcpLimits.checked().left.map(new Exception(_)))
        limits <- IO.fromEither(lab.fetcher.TcpFetchLimits.checked(tcp).left.map(new Exception(_)))
        descriptor <- IO.blocking(
          lab.fetcher.TcpDirectRangeDescriptor.load(in.descriptor, in.provenance, limits)
        )
        _ <- NioSegmentStore
          .resource[IO](in.output, descriptor.spec, descriptor.identity, false)
          .use(st => st.append(right(CardanoBlockIndex.inspect(in.range.raws.head, 1048576))).void)
        result <- session(server, in, response(in.range.raws), resume = true)
        _ <- IO(assertCode(result, 0))
        snap <- inspect(in)
        _ <- verifyRecords(in, snap)
      yield ()
    })
  }

  test("canceling real TCP acquisition closes and retains an empty checkpoint") {
    guarded((temporary, listener).tupled.use { (root, server) =>
      for
        in <- inputs(root, server.port, range("shelley"))
        sent <- Deferred[IO, Unit]
        peer = server.accept.use { p =>
          p.expect(0, proposal) *> p.send(accepted) *> p
            .expect(3, request(in.range.first, in.range.last)) *>
            p.send(response(in.range.raws.take(1), terminal = false)) *> sent
              .complete(())
              .void *> p.expectEof.handleErrorWith {
              // Closing with unread received data may send a TCP reset rather than FIN.
              case e: java.io.IOException if e.getMessage == "Connection reset" => IO.unit
              case e                                                            => IO.raiseError(e)
            }
        }
        _ <- (
          peer,
          (run(in.args()).start.flatMap(f =>
            sent.get *> f.cancel *> f.join.flatMap(o => IO(assert(o.isCanceled)))
          ))
        ).parTupled
        snap <- inspect(in)
        _ <- IO(assertEquals(snap.records.size, 0))
      yield ()
    })
  }
