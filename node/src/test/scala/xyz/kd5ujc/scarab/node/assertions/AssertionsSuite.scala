// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.assertions

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Base64
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** `Assertions.recording` and the fallback SDK's lines (#1032). The concurrent tests run under
  * `TestControl` with seeded schedules; the release of the resource is where a test looks at what
  * was written, so nothing waits on time.
  */
class AssertionsSuite extends FailsOnUncaught:
  private final case class Seen(slot: Long, peer: String)
  private given Encoder.AsObject[Seen] =
    Encoder.AsObject.instance(s => JsonObject("slot" -> s.slot.asJson, "peer" -> s.peer.asJson))

  private val seen = Seen(42L, "10.0.0.1:3001")
  private val seenJson = """{"slot":42,"peer":"10.0.0.1:3001"}"""
  private val here = Location("node/src/main/scala/a/B.scala", "a.B")

  /** A line as the SDK's docs give it (`/docs/reference/sdk/fallback/assert/`): the values its
    * table sets verbatim for each assertion function, in the order of its examples.
    */
  private def documented(
      hit: Boolean,
      mustHit: Boolean,
      assertType: String,
      displayType: String,
      message: String,
      condition: Boolean,
      location: Location,
      details: Option[String]
  ): String =
    val after = details.fold("")(d => s""","details":$d""")
    s"""{"antithesis_assert":{"hit":$hit,"must_hit":$mustHit,"assert_type":"$assertType",""" +
      s""""display_type":"$displayType","message":"$message","condition":$condition,""" +
      s""""id":"$message","location":{"class":"${location.className}","function":"",""" +
      s""""file":"${location.file}","begin_line":0,"begin_column":0}$after}}"""

  /** The declaration the docs' table gives for a property of `kind`. */
  private def declared(kind: Kind, message: String, location: Location): String = kind match
    case Kind.Always =>
      documented(false, true, "always", "Always", message, false, location, None)
    case Kind.Sometimes =>
      documented(false, true, "sometimes", "Sometimes", message, false, location, None)
    case Kind.Unreachable =>
      documented(false, false, "reachability", "Unreachable", message, false, location, None)

  private val declarations: Vector[String] =
    Property.catalogue.map(p => declared(p.kind, p.message, p.location))

  /** The evaluation the docs give for `property` with `condition` and the details of [[seen]]. */
  private def evaluated(property: Property, condition: Boolean): String = property.kind match
    case Kind.Always =>
      documented(
        true,
        true,
        "always",
        "Always",
        property.message,
        condition,
        property.location,
        Some(seenJson)
      )
    case Kind.Sometimes =>
      documented(
        true,
        true,
        "sometimes",
        "Sometimes",
        property.message,
        condition,
        property.location,
        Some(seenJson)
      )
    case Kind.Unreachable =>
      documented(
        true,
        false,
        "reachability",
        "Unreachable",
        property.message,
        false,
        property.location,
        Some(seenJson)
      )

  /** A sink that keeps each line and, at its release, says it closed. */
  private def kept(lines: Ref[IO, Vector[String]]): Resource[IO, String => IO[Unit]] =
    Resource.make(IO.pure((line: String) => lines.update(_ :+ line)))(_ =>
      lines.update(_ :+ "<closed>")
    )

  private def noFailure(failure: AssertionsFailure): IO[Unit] =
    IO.raiseError(new AssertionError(s"unexpected writer failure: ${failure.message}"))

  private def base64Seed(seed: Long): String =
    Base64.getEncoder.encodeToString(seed.toString.getBytes(UTF_8))

  test("the lines are those of the SDK's docs, declared and evaluated, for every kind") {
    val details = JsonObject("slot" -> 42L.asJson, "peer" -> "10.0.0.1:3001".asJson)
    Kind.values.foreach { kind =>
      assertEquals(SdkLine.declaration(kind, "m", here), declared(kind, "m", here), kind)
    }
    assertEquals(
      SdkLine.evaluation(Kind.Sometimes, "m", here, true, details),
      documented(true, true, "sometimes", "Sometimes", "m", true, here, Some(seenJson))
    )
    assertEquals(
      SdkLine.evaluation(Kind.Sometimes, "m", here, false, details),
      documented(true, true, "sometimes", "Sometimes", "m", false, here, Some(seenJson))
    )
    assertEquals(
      SdkLine.evaluation(Kind.Always, "m", here, false, details),
      documented(true, true, "always", "Always", "m", false, here, Some(seenJson))
    )
    // an unreachable property has no condition: its evaluation's is false, whatever is passed
    assertEquals(
      SdkLine.evaluation(Kind.Unreachable, "m", here, true, details),
      documented(true, false, "reachability", "Unreachable", "m", false, here, Some(seenJson))
    )
    assertEquals(
      SdkLine.evaluation(Kind.Sometimes, "m", here, true, JsonObject.empty),
      documented(true, true, "sometimes", "Sometimes", "m", true, here, Some("{}"))
    )
  }

  test("the catalogue's messages are distinct, and each location's file defines its class") {
    val messages = Property.catalogue.map(_.message)
    assertEquals(messages.distinct, messages)
    messages.foreach(m => assert(!m.exists(c => c == '"' || c == '\\'), m))
    val root = Iterator
      .iterate(Path.of(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .find(dir => Files.exists(dir.resolve("build.sbt")) && Files.isDirectory(dir.resolve("node")))
      .getOrElse(fail("no repository root"))
    Property.catalogue.map(_.location).distinct.foreach { location =>
      val file = root.resolve(location.file)
      assert(Files.exists(file), location.file)
      val name = location.className.split('.').last
      val source = new String(Files.readAllBytes(file), UTF_8)
      assert(
        List("class", "object", "trait").exists(k => source.contains(s"$k $name")),
        s"${location.file} defines no $name"
      )
    }
  }

  test("the whole catalogue is declared first, then each evaluation in the order it was made") {
    val made = Vector(
      Property.Sometimes.Forged -> true,
      Property.Sometimes.ForkSwitched -> false,
      Property.Sometimes.Forged -> false
    )
    TestControl.executeEmbed {
      for
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        _ <- Assertions.recording[IO](kept(lines), noFailure).use { assertions =>
          made.traverse_((p, c) => assertions.sometimes(p, c, seen)) *>
            assertions.unreachable(Property.Unreachable.ForgedInvalid, seen)
        }
        written <- lines.get
      yield assertEquals(
        written,
        declarations ++ made.map((p, c) => evaluated(p, c)) ++
          Vector(evaluated(Property.Unreachable.ForgedInvalid, false), "<closed>")
      )
    }
  }

  test("concurrent calls record the first true and the first false evaluation of each property") {
    val properties: Vector[Property.Sometimes | Property.Unreachable] =
      Property.Sometimes.values.toVector ++ Property.Unreachable.values.toVector
    val calls = Gen.listOfN(
      200,
      Gen.zip(Gen.oneOf(properties), Gen.oneOf(true, false))
    )
    (1L to 12L).toList.traverse_ { seed =>
      val drawn = calls.apply(Gen.Parameters.default, Seed(seed)).getOrElse(fail("no calls"))
      val expected = drawn.map {
        case (p: Property.Sometimes, c)   => (p: Property) -> c
        case (p: Property.Unreachable, _) => (p: Property) -> false
      }.toSet
      val program =
        for
          lines <- Ref.of[IO, Vector[String]](Vector.empty)
          _ <- Assertions.recording[IO](kept(lines), noFailure).use { assertions =>
            drawn.parTraverse_ {
              case (p: Property.Sometimes, c)   => assertions.sometimes(p, c, seen)
              case (p: Property.Unreachable, _) => assertions.unreachable(p, seen)
            }
          }
          written <- lines.get
        yield
          val evaluations = written.drop(declarations.size).dropRight(1)
          assertEquals(written.take(declarations.size), declarations, s"seed $seed")
          assertEquals(
            evaluations.size,
            expected.size,
            s"seed $seed: one line per first evaluation"
          )
          assertEquals(
            evaluations.toSet,
            expected.map((p, c) => evaluated(p, c)),
            s"seed $seed: the first true and the first false of each property"
          )
      TestControl.executeEmbed(program, seed = Some(base64Seed(seed)))
    }
  }

  test("a caller never waits for the writer, and the release drains the queue before closing") {
    TestControl.executeEmbed {
      for
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        gate <- Deferred[IO, Unit]
        sink = kept(lines).map(write => (line: String) => gate.get *> write(line))
        _ <- Assertions.recording[IO](sink, noFailure).use { assertions =>
          // the writer is held at its first declaration: every call returns all the same
          Property.Sometimes.values.toVector.traverse_(p => assertions.sometimes(p, true, seen)) *>
            lines.get.map(held => assertEquals(held, Vector.empty[String])) *>
            gate.complete(()).void
        }
        written <- lines.get
      yield assertEquals(
        written,
        declarations ++ Property.Sometimes.values.toVector.map(evaluated(_, true)) :+ "<closed>"
      )
    }
  }

  test("a sink that cannot be opened is one failure, and the callers go on") {
    val refused = new IOException("no such directory")
    TestControl.executeEmbed {
      for
        failures <- Ref.of[IO, Vector[AssertionsFailure]](Vector.empty)
        sink = Resource.eval(IO.raiseError[String => IO[Unit]](refused))
        _ <- Assertions.recording[IO](sink, f => failures.update(_ :+ f)).use { assertions =>
          Property.Sometimes.values.toVector.traverse_(p => assertions.sometimes(p, true, seen)) *>
            assertions.unreachable(Property.Unreachable.StoppedOnFault, seen)
        }
        failed <- failures.get
      yield failed match
        case Vector(AssertionsFailure.Unwritable(cause)) => assert(cause eq refused, cause)
        case other => fail(s"one failure of the sink expected, got $other")
    }
  }

  test("a write that fails ends the writer once; later calls neither wait nor fail") {
    val full = new IOException("no space left on device")
    TestControl.executeEmbed {
      for
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        failures <- Ref.of[IO, Vector[AssertionsFailure]](Vector.empty)
        sink = kept(lines).map(write =>
          (line: String) =>
            lines.get.flatMap(sofar => if sofar.size >= 2 then IO.raiseError(full) else write(line))
        )
        _ <- Assertions.recording[IO](sink, f => failures.update(_ :+ f)).use { assertions =>
          Property.Sometimes.values.toVector.traverse_(p =>
            assertions.sometimes(p, true, seen) *> assertions.sometimes(p, false, seen)
          )
        }
        written <- lines.get
        failed <- failures.get
      yield
        assertEquals(written, declarations.take(2) :+ "<closed>")
        failed match
          case Vector(AssertionsFailure.Unwritable(cause)) => assert(cause eq full, cause)
          case other => fail(s"one failure of the write expected, got $other")
    }
  }

  test("details whose encoder throws are written as the exception's class; the caller goes on") {
    final case class Broken()
    given Encoder.AsObject[Broken] =
      Encoder.AsObject.instance(_ => throw new IllegalStateException("encoder broke"))
    val forged = Property.Sometimes.Forged
    TestControl.executeEmbed {
      for
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        _ <- Assertions.recording[IO](kept(lines), noFailure).use { assertions =>
          assertions.sometimes(forged, true, Broken()) *>
            assertions.sometimes(forged, true, Broken()) *>
            assertions.sometimes(forged, false, seen)
        }
        written <- lines.get
      yield assertEquals(
        written.drop(declarations.size),
        Vector(
          documented(
            true,
            true,
            "sometimes",
            "Sometimes",
            forged.message,
            true,
            forged.location,
            Some("""{"unencoded":"java.lang.IllegalStateException"}""")
          ),
          evaluated(forged, false),
          "<closed>"
        )
      )
    }
  }

  test("details are built only for an evaluation that is written, and never without the flag") {
    val built = new java.util.concurrent.atomic.AtomicInteger(0)
    def details: Seen =
      built.incrementAndGet()
      seen
    val forged = Property.Sometimes.Forged
    val refused = Property.Unreachable.ForgedInvalid
    TestControl.executeEmbed {
      for
        _ <- Assertions.noop[IO].sometimes(forged, true, details)
        _ <- Assertions.noop[IO].unreachable(refused, details)
        withoutFlag <- IO(built.get)
        lines <- Ref.of[IO, Vector[String]](Vector.empty)
        _ <- Assertions.recording[IO](kept(lines), noFailure).use { assertions =>
          assertions.sometimes(forged, true, details) *>
            assertions.sometimes(forged, true, details) *>
            assertions.unreachable(refused, details) *>
            assertions.unreachable(refused, details)
        }
        recorded <- IO(built.get)
      yield
        assertEquals(withoutFlag, 0, "noop builds no details")
        assertEquals(recorded, 2, "one build for each pair written, none for a pair already seen")
    }
  }

  test("a report of the writer's failure that raises leaves the writer's value, and the callers") {
    val full = new IOException("no space left on device")
    val unlogged = new IOException("the log cannot be written either")
    val refused = Resource.eval(IO.raiseError[String => IO[Unit]](full))
    TestControl.executeEmbed {
      for
        queue <- Queue.unbounded[IO, Option[Assertions.Evaluation]]
        ended <- Assertions.writer[IO](refused, queue, _ => IO.raiseError(unlogged))
        _ <- Assertions.recording[IO](refused, _ => IO.raiseError(unlogged)).use { assertions =>
          Property.Sometimes.values.toVector.traverse_(p => assertions.sometimes(p, true, seen))
        }
      yield ended match
        case Left(AssertionsFailure.Unwritable(cause)) => assert(cause eq full, cause)
        case other => fail(s"the writer ends with the sink's failure as its value, got $other")
    }
  }

  test("a run started again appends to the file, each line whole and ending in a newline") {
    val run = (file: Path, condition: Boolean) =>
      Assertions
        .recording[IO](Assertions.appending[IO](file), noFailure)
        .use(
          _.sometimes(Property.Sometimes.SnapshotWritten, condition, seen)
        )
    Resource
      .make(IO.blocking(Files.createTempDirectory("assertions")))(dir =>
        IO.blocking(
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
        )
      )
      .use { dir =>
        // the directory the file goes in is made, as for the log file
        val file = dir.resolve("out").resolve("sdk.jsonl")
        for
          _ <- run(file, true)
          _ <- run(file, false)
          bytes <- IO.blocking(Files.readAllBytes(file))
        yield
          val text = new String(bytes, UTF_8)
          val first = declarations :+ evaluated(Property.Sometimes.SnapshotWritten, true)
          val second = declarations :+ evaluated(Property.Sometimes.SnapshotWritten, false)
          assertEquals(text, (first ++ second).map(_ + "\n").mkString)
          assert(text.contains("1 µs"), "the messages are written in UTF-8")
          assertEquals(text.split("\n", -1).count(_.isEmpty), 1, "exactly one trailing newline")
      }
  }
