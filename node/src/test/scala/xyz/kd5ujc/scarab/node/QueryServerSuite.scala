// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import cats.data.NonEmptyVector
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.Port
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import org.http4s.circe.jsonOf
import fs2.Stream
import org.http4s.{EntityDecoder, HttpApp, Method, Request, Response, Status, Uri}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class QueryServerSuite extends FailsOnUncaught:
  private val setup = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures/devnet/lsq/genesis"))
    .fold(e => fail(e.message), identity)
  private given EntityDecoder[IO, io.circe.Json] = jsonOf[IO, io.circe.Json]
  private val hash = "ab" * 32
  private val bytes = Bytes.fromHex(hash).fold(e => fail(e.message), identity)
  private val retained = Point.Block(UInt64.saturating(17), bytes)

  private case class Reads(tips: Int, points: Vector[Point])

  private def request(path: String): Request[IO] =
    Request[IO](Method.GET, Uri.fromString(path).fold(e => fail(e.message), identity))

  private def view(reads: Ref[IO, Reads]): ChainView[IO, MirrorState] =
    new ChainView[IO, MirrorState]:
      def tip: IO[(Point, MirrorState)] =
        reads.update(r => r.copy(tips = r.tips + 1)).as(Point.Origin -> setup.mirrorStart)
      def at(point: Point): IO[Option[MirrorState]] =
        reads
          .update(r => r.copy(points = r.points :+ point))
          .as(Option.when(point == retained)(setup.mirrorStart))

  private type Answers = SharedAnswers[IO, Point, QueryServer.StateKey, Response[IO]]

  /** The routes over `view`, with answers of their own kept for `tip` if one is given. */
  private def served[S: ChainRead](
      view: ChainView[IO, S],
      tip: Option[Point] = None
  ): IO[HttpApp[IO]] =
    answersAt(tip).map(answers => app(view, answers))

  private def answersAt(tip: Option[Point]): IO[Answers] =
    SharedAnswers
      .of[IO, Point, QueryServer.StateKey, Response[IO]](_.point)
      .flatTap(answers => tip.traverse_(answers.moved))

  /** Answers that count the builds they run. */
  private def counting(inner: Answers, builds: Ref[IO, Int]): Answers = new Answers:
    def answer(key: QueryServer.StateKey)(build: => IO[Response[IO]]) =
      inner.answer(key)(builds.update(_ + 1) *> build)
    def moved(point: Point) = inner.moved(point)

  private def app[S: ChainRead](view: ChainView[IO, S], answers: Answers): HttpApp[IO] =
    QueryServer.routes(view, answers)(failure => IO(fail(s"unexpected fatal: $failure"))).orNotFound

  test("the bound server answers on its loopback port, over the HTTP servers' network") {
    // A raw request that asks the server to close: an idle keep-alive connection would hold the
    // server's graceful stop for its shutdown timeout.
    def get(port: Int): IO[Option[String]] =
      Resource
        .make(IO.blocking {
          val socket = new Socket("127.0.0.1", port)
          socket.setSoTimeout(10000)
          socket
        })(socket => IO.blocking(socket.close()))
        .use { socket =>
          IO.blocking {
            socket.getOutputStream.write(
              "GET /tip HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII)
            )
            socket.getOutputStream.flush()
            new String(
              socket.getInputStream.readAllBytes(),
              StandardCharsets.US_ASCII
            ).linesIterator
              .nextOption()
          }
        }
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      ephemeral <- IO.fromOption(Port.fromInt(0))(new IllegalStateException("port 0"))
      answered <- QueryServer
        .resource(view(reads), QueryListen.Loopback(ephemeral), Stream.empty)(failure =>
          IO(fail(s"unexpected fatal: $failure"))
        )
        .use(_.flatTraverse(server => get(server.address.getPort)))
      observed <- reads.get
    yield
      assertEquals(answered, Some("HTTP/1.1 200 OK"), "GET /tip on the bound port")
      assertEquals(observed.tips, 1, "it read the tip once")
  }

  test("malformed or partial explicit points return 400 without reading a state") {
    val malformed = List(
      s"/state?slot=17",
      s"/state?hash=$hash",
      s"/state?slot=no&hash=$hash",
      s"/state?slot=-1&hash=$hash",
      s"/state?slot=18446744073709551616&hash=$hash",
      s"/state?slot=&hash=$hash",
      s"/state?slot&hash=$hash",
      "/state?slot=17&hash=zz",
      "/state?slot=17&hash=",
      s"/state?slot=17&hash=${"ab" * 31}",
      s"/state?slot=17&hash=${"ab" * 33}",
      s"/state?slot=17&slot=18&hash=$hash",
      s"/state?slot=17&hash=$hash&hash=$hash"
    )
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      app <- served(view(reads))
      _ <- malformed.traverse_ { path =>
        app
          .run(request(path))
          .map(response => assertEquals(response.status, Status.BadRequest, path))
      }
      observed <- reads.get
    yield assertEquals(observed, Reads(0, Vector.empty))
  }

  test("state without the utxo parameter is a summary; unknown or repeated values return 400") {
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      app <- served(view(reads))
      summary <- app.run(request("/state?utxo=summary")).flatMap(_.as[io.circe.Json])
      bare <- app.run(request("/state")).flatMap(_.as[io.circe.Json])
      full <- app.run(request("/state?utxo=full")).flatMap(_.as[io.circe.Json])
      bad <- List("/state?utxo=all", "/state?utxo=", "/state?utxo=full&utxo=full").traverse(p =>
        app.run(request(p)).map(_.status)
      )
    yield
      assertEquals(summary, bare)
      assert(summary.hcursor.downField("utxo").failed)
      assert(summary.hcursor.downField("utxoSummary").downField("entries").succeeded)
      assert(full.hcursor.downField("utxo").succeeded)
      assertEquals(bad, List.fill(3)(Status.BadRequest))
  }

  test("bare state reads the tip and a retained explicit point reads that point") {
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      app <- served(view(reads))
      tip <- app.run(request("/state"))
      at <- app.run(request(s"/state?slot=17&hash=$hash"))
      observed <- reads.get
    yield
      assertEquals(tip.status, Status.Ok)
      assertEquals(at.status, Status.Ok)
      assertEquals(observed, Reads(1, Vector(retained)))
  }

  test("well-formed unavailable points return 404, including the full unsigned slot range") {
    val slots = List(BigInt(18), BigInt("18446744073709551615"))
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      app <- served(view(reads))
      _ <- slots.traverse_ { slot =>
        app.run(request(s"/state?slot=$slot&hash=$hash")).map { response =>
          assertEquals(response.status, Status.NotFound)
        }
      }
      observed <- reads.get
    yield assertEquals(
      observed,
      Reads(0, slots.map(slot => Point.Block(UInt64.saturating(slot), bytes)).toVector)
    )
  }

  // GET /state's answers are built one at a time and shared (#1150): SharedAnswersSuite has the
  // sharing; these are its keys and keeping through the routes.

  test("state's answer at the tip is kept until the tip moves, and the new tip's is then built") {
    val next = Point.Block(
      UInt64.saturating(18),
      Bytes.fromHex("cd" * 32).fold(e => fail(e.message), identity)
    )
    for
      current <- Ref.of[IO, Point](retained)
      answers <- answersAt(Some(retained))
      moving = new ChainView[IO, MirrorState]:
        def tip: IO[(Point, MirrorState)] = current.get.map(_ -> setup.mirrorStart)
        def at(point: Point): IO[Option[MirrorState]] =
          IO.pure(Option.when(point == retained || point == next)(setup.mirrorStart))
      run = app(moving, answers).run
      first <- run(request("/state"))
      kept <- run(request("/state"))
      _ <- current.set(next) *> answers.moved(next)
      moved <- run(request("/state"))
      movedKept <- run(request("/state"))
      old <- run(request(s"/state?slot=17&hash=$hash"))
      points <- List(first, moved, old).traverse(
        _.as[io.circe.Json].map(_.hcursor.downField("point").focus)
      )
    yield
      assertEquals(first.status, Status.Ok)
      assert(kept eq first, "the second request at the tip is answered with the kept answer")
      assert(moved ne first, "the new tip's answer is built")
      assert(movedKept eq moved, "and kept in turn")
      assert(
        old ne first,
        "the old tip's answer was dropped: asked for by its point, it is built again"
      )
      assertEquals(
        points,
        List(
          Some(Projection.point(retained)),
          Some(Projection.point(next)),
          Some(Projection.point(retained))
        )
      )
  }

  test("state's two views of the tip are kept apart") {
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      builds <- Ref.of[IO, Int](0)
      answers <- answersAt(Some(Point.Origin)).map(counting(_, builds))
      run = app(view(reads), answers).run
      summary <- run(request("/state"))
      full <- run(request("/state?utxo=full"))
      summaryAgain <- run(request("/state"))
      fullAgain <- run(request("/state?utxo=full"))
      bodies <- List(summary, full).traverse(_.as[io.circe.Json])
      built <- builds.get
    yield
      assert(summaryAgain eq summary, "the summary is kept")
      assert(fullAgain eq full, "the full view is kept")
      assert(bodies(0).hcursor.downField("utxoSummary").succeeded, "the summary is the summary")
      assert(bodies(1).hcursor.downField("utxo").succeeded, "the full view is the full view")
      assertEquals(built, 2, "one build of each view")
  }

  test("state's answer at an explicit point that is not the tip is built for each request") {
    for
      reads <- Ref.of[IO, Reads](Reads(0, Vector.empty))
      run <- served(view(reads), tip = Some(Point.Origin)).map(_.run)
      once <- run(request(s"/state?slot=17&hash=$hash"))
      twice <- run(request(s"/state?slot=17&hash=$hash"))
      point <- once.as[io.circe.Json].map(_.hcursor.downField("point").focus)
    yield
      assertEquals((once.status, twice.status), (Status.Ok, Status.Ok))
      assert(twice ne once, "built again: only the tip's answers are kept")
      assertEquals(point, Some(Projection.point(retained)))
  }

  test("state summarises a UTxO set past the full view's limit, and is not refused") {
    val held = ChainRead[MirrorState].utxo(setup.mirrorStart)
    val large =
      QueryServerSuite.Sized(setup.mirrorStart, held.copy(count = QueryServer.MaxFullUtxo + 1))
    val sized = new ChainView[IO, QueryServerSuite.Sized]:
      def tip: IO[(Point, QueryServerSuite.Sized)] = IO.pure(Point.Origin -> large)
      def at(point: Point): IO[Option[QueryServerSuite.Sized]] = IO.pure(None)
    for
      builds <- Ref.of[IO, Int](0)
      answers <- answersAt(Some(Point.Origin)).map(counting(_, builds))
      run = app(sized, answers).run
      summary <- run(request("/state"))
      entries <- summary
        .as[io.circe.Json]
        .map(
          _.hcursor.downField("utxoSummary").downField("entries").as[Long]
        )
      full <- run(request("/state?utxo=full"))
      built <- builds.get
    yield
      assertEquals(summary.status, Status.Ok)
      assertEquals(entries, Right(QueryServer.MaxFullUtxo + 1))
      assertEquals(full.status, Status.PayloadTooLarge)
      assertEquals(built, 1, "the full view is refused before any build")
  }

object QueryServerSuite:
  /** A mirror whose UTxO set is `held`; the summary reads only its totals. */
  final case class Sized(mirror: MirrorState, held: xyz.kd5ujc.scarab.ledger.Utxo)

  object Sized:
    import xyz.kd5ujc.scarab.consensus.{BlockRef, ConsensusExtras}
    import xyz.kd5ujc.scarab.consensus.state.{
      EraSummary,
      MirrorProvenance,
      Placement,
      ProtocolState
    }
    import xyz.kd5ujc.scarab.ledger as flat
    import xyz.kd5ujc.scarab.ledger.state.{SlotNo, WithOrigin}
    import xyz.kd5ujc.scarab.node.read.{InEra, Read}
    import xyz.kd5ujc.scarab.protocol.Era

    given ChainRead[Sized] with
      extension (s: Sized)
        def era: Era = s.mirror.era
        def provenance: MirrorProvenance = s.mirror.provenance
        def ledgerEpoch: BigInt = s.mirror.ledgerEpoch
        def tipSlot: WithOrigin[SlotNo] = s.mirror.tipSlot
        def protocol: ProtocolState = s.mirror.protocol
        def afterVoting: Long = s.mirror.afterVoting
        def consensus: ConsensusExtras = s.mirror.consensus
        def plutus: flat.PlutusStats = s.mirror.plutus
        def utxo: flat.Utxo = s.held
        def ledger(at: Option[BlockRef]): Read[Option[InEra]] = s.mirror.ledger(at)
        def summary(placement: Placement): NonEmptyVector[EraSummary] = s.mirror.summary(placement)
