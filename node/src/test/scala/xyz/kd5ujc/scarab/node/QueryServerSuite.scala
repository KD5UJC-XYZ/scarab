// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import java.nio.file.Path
import munit.CatsEffectSuite
import org.http4s.circe.jsonOf
import org.http4s.{EntityDecoder, Method, Request, Status, Uri}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class QueryServerSuite extends CatsEffectSuite:
  private val setup = NodeSetup
    .fromDirectory(Path.of("fixtures/devnet/lsq/genesis"))
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
      app = QueryServer
        .routes(view(reads))(failure => IO(fail(s"unexpected fatal: $failure")))
        .orNotFound
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
      app = QueryServer
        .routes(view(reads))(failure => IO(fail(s"unexpected fatal: $failure")))
        .orNotFound
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
      app = QueryServer
        .routes(view(reads))(failure => IO(fail(s"unexpected fatal: $failure")))
        .orNotFound
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
      app = QueryServer
        .routes(view(reads))(failure => IO(fail(s"unexpected fatal: $failure")))
        .orNotFound
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
