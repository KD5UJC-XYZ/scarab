// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import scala.util.{Random, Using}

class GenErrorSuite extends munit.FunSuite:
  private def config(extra: String = ""): Config =
    Config
      .parse(s"package test.models\nmodule t t t.cddl\nroot t:r\n$extra")
      .fold(e => fail(e.message), identity)

  private def schema(body: Ast.Ty): Schema = Schema.build(
    Vector(
      "t" -> Vector(
        Ast.File(Vector(Ast.Rule("r", Vector.empty, Ast.Assign.Eq, body)), Vector.empty)
      )
    )
  )

  private def smallStack[A](body: => A): A =
    // FutureTask retains every failure, including StackOverflowError; no uncaught worker exception.
    val task = new FutureTask[A](() => body)
    val thread = new Thread(null, task, "generator-small-stack", 256 * 1024)
    thread.start()
    task.get()

  test("configuration failures retain their directive, line and qualified-name input") {
    assertEquals(
      Config.parse("package p\nroot nope\n"),
      Left(GenError.BadConfig(2, ConfigProblem.QualifiedName("nope")))
    )
    assertEquals(
      Config.parse("package p\nwat x y\n"),
      Left(GenError.BadConfig(2, ConfigProblem.Directive(Vector("wat", "x", "y"))))
    )
    assertEquals(
      Config.parse("# no package\n"),
      Left(GenError.BadConfig(0, ConfigProblem.MissingPackage))
    )
  }

  test("I/O and unexpected defects retain original exceptions in distinct cases") {
    val path = Path.of("unreadable.cddl")
    val io = new IOException("read failed")
    val defect = new IllegalStateException("broken provider")
    Generator.io(path)(throw io) match
      case Left(GenError.Io(p, cause)) => assertEquals(p, path.toString); assert(cause eq io)
      case other                       => fail(other.toString)
    Generator.io(path)(throw defect) match
      case Left(GenError.Defect(p, cause)) =>
        assertEquals(p, path.toString); assert(cause eq defect)
      case other => fail(other.toString)
  }

  test("missing input files remain I/O failures instead of unsupported syntax") {
    val dir = Files.createTempDirectory("gen-errors")
    Generator.load(dir, config()) match
      case Left(GenError.Io(path, _: java.nio.file.NoSuchFileException)) =>
        assertEquals(path, dir.resolve("t.cddl").toString)
      case other => fail(other.toString)
    Files.delete(dir)
  }

  test("an empty integer domain is rejected by sampling as a typed failure") {
    val s =
      schema(Ast.Ty.Ctrl(Ast.Ty.Name("uint", Vector.empty), "lt", Ast.Ty.Literal(Ast.Lit.IntL(0))))
    assertEquals(
      new CddlSampler(s).sample(QName("t", "r"), new Random(1)),
      Left(GenError.EmptyIntegerDomain(1, 0))
    )
  }

  test("unsupported controls retain syntax and are returned through every generator boundary") {
    val s = schema(
      Ast.Ty.Ctrl(Ast.Ty.Name("text", Vector.empty), "regexp", Ast.Ty.Literal(Ast.Lit.TextL("x+")))
    )
    val expected = GenError.Unsupported("t:r", UnsupportedFeature.Control("regexp"))
    assertEquals(new Emitter(s, config()).generate(), Left(expected))
    assertEquals(
      new CddlSampler(s).sample(QName("t", "r"), new Random(1)),
      Left(GenError.Unsupported("sampler", UnsupportedFeature.Control("regexp")))
    )
  }

  test("unreachable configured rules and unsupported occurrences have closed cases") {
    val s = Schema.build(
      Vector(
        "t" -> Vector(
          Ast.File(
            Vector(
              Ast.Rule("r", Vector.empty, Ast.Assign.Eq, Ast.Ty.Name("uint", Vector.empty)),
              Ast.Rule("other", Vector.empty, Ast.Assign.Eq, Ast.Ty.Name("uint", Vector.empty))
            ),
            Vector.empty
          )
        )
      )
    )
    assertEquals(
      new Emitter(s, config("preserve t:other")).generate(),
      Left(GenError.BadConfig(0, ConfigProblem.UnreachableRule(QName("t", "other"))))
    )
    val repeated = Ast.Occ(2, Some(3))
    val fixed = schema(
      Ast.Ty.Array(
        Ast.Group(
          Vector(
            Vector(
              Ast.Entry(repeated, None, Ast.Ty.Name("uint", Vector.empty)),
              Ast.Entry(Ast.Occ.Once, None, Ast.Ty.Name("uint", Vector.empty))
            )
          )
        )
      )
    )
    assertEquals(
      new Emitter(fixed, config()).generate(),
      Left(GenError.Unsupported("t:r", UnsupportedFeature.FixedArrayOccurrence(repeated)))
    )
  }

  test("cyclic named integer bounds return a typed failure") {
    val s = Schema.build(
      Vector(
        "t" -> Vector(
          Ast.File(
            Vector(
              Ast.Rule(
                "r",
                Vector.empty,
                Ast.Assign.Eq,
                Ast.Ty.Range(Ast.Ty.Name("a", Vector.empty), Ast.Ty.Literal(Ast.Lit.IntL(4)), true)
              ),
              Ast.Rule("a", Vector.empty, Ast.Assign.Eq, Ast.Ty.Name("b", Vector.empty)),
              Ast.Rule("b", Vector.empty, Ast.Assign.Eq, Ast.Ty.Name("a", Vector.empty))
            ),
            Vector.empty
          )
        )
      )
    )
    assertEquals(s.rule(QName("t", "r")), Left(GenError.CyclicExpansion(QName("t", "a"))))
  }

  test("codec hoist expansion preserves regex word boundaries and replacement order") {
    def legacy(codec: String, hoists: Vector[(String, String, String)]): String =
      hoists.reverseIterator.foldLeft(codec) { case (expanded, (name, _, value)) =>
        expanded.replaceAll(s"\\b$name\\b", java.util.regex.Matcher.quoteReplacement(value))
      }

    val hoists = Vector(
      ("c0", "Int", "rt.Codec.int"),
      ("c1", "Int", "rt.Codec.tagged(BigInt(1), c0)"),
      ("c10", "Int", "rt.Codec.tagged(BigInt(10), c1)"),
      ("c11", "Int", "rt.Codec.literal(\"c10 c99\")")
    )
    val codec = "Tuple(c10, c1, c0, c100, xc1, \"c11\")"
    assertEquals(Emitter.unhoist(codec, hoists), legacy(codec, hoists))

    val forwardReference = Vector(
      ("c0", "Int", "rt.Codec.literal(c1)"),
      ("c1", "Int", "rt.Codec.int")
    )
    assertEquals(
      Emitter.unhoist("Tuple(c0, c1)", forwardReference),
      legacy("Tuple(c0, c1)", forwardReference)
    )

    val selfReference = Vector(("c0", "Int", "rt.Codec.literal(c0)"))
    assertEquals(Emitter.unhoist("c0", selfReference), legacy("c0", selfReference))
  }

  test("schema depth, reference chains and config width do not consume the JVM stack") {
    smallStack {
      val depth = 12000
      val body = {
        (0 until depth).foldLeft[Ast.Ty](Ast.Ty.Name("uint", Vector.empty))((t, _) =>
          Ast.Ty.Tag(6, Some(1), Some(t))
        )
      }
      val s = schema(body)
      assert(s.rule(QName("t", "r")).isRight)
      assert(
        new Emitter(s, config()).generate().exists(_.values.head.contains("rt.Codec.tagged"))
      )
      assert(
        new CddlSampler(s).sample(QName("t", "r"), new Random(1)).exists(_.length > depth)
      )
      val chain = (0 until depth).map { i =>
        Ast.Rule(
          s"n$i",
          Vector.empty,
          Ast.Assign.Eq,
          Ast.Ty.Name(if i == depth - 1 then "uint" else s"n${i + 1}", Vector.empty)
        )
      }.toVector
      val linked = Schema.build(Vector("t" -> Vector(Ast.File(chain, Vector.empty))))
      val cfg = config().copy(roots = Vector(QName("t", "n0")))
      assert(new Emitter(linked, cfg).generate().isRight)
      assert(new CddlSampler(linked).sample(QName("t", "n0"), new Random(1)).isRight)
      assert(
        Config.parse("package p\n" + "root t:r\n" * 100000).exists(_.roots.size == 100000)
      )
    }
  }

  test(
    "deep unsupported syntax retains its payload and renders bounded diagnostics on a small stack"
  ) {
    smallStack {
      val ast = (0 until 12000).foldLeft[Ast.Ty](Ast.Ty.Name("uint", Vector.empty))((t, _) =>
        Ast.Ty.Tag(6, Some(1), Some(t))
      )
      val resolved = (0 until 12000).foldLeft[RT](RT.Prim(PrimKind.Uint))((t, _) => RT.Tagged(1, t))
      val errors = Vector(
        GenError.Unsupported("t:r", UnsupportedFeature.NonLiteralBound(ast)),
        GenError.Unsupported("t:r", UnsupportedFeature.Enumeration(ast)),
        GenError.Unsupported("t:r", UnsupportedFeature.TypeInPosition(resolved))
      )
      errors.foreach { error =>
        val text = error.message
        assert(text.startsWith("unsupported CDDL feature in `t:r`: "))
        assert(text.contains("Tag"))
        assert(text.contains("…"))
        assert(text.length < 650)
      }
      errors.head match
        case GenError.Unsupported(_, UnsupportedFeature.NonLiteralBound(kept)) =>
          assert(kept.asInstanceOf[AnyRef] eq ast.asInstanceOf[AnyRef])
        case _ => fail("typed syntax payload was discarded")
    }
  }

  test("every configured generated source matches the reviewed conformance output snapshot") {
    val dir = Vector(Paths.get("models/src/main/cddl"), Paths.get("../models/src/main/cddl"))
      .find(Files.isDirectory(_))
      .getOrElse(fail("cddl directory not found"))
    val files = Generator
      .configFrom(dir)
      .flatMap(Generator.generate(dir, _))
      .fold(e => fail(e.message), identity)
    val expected = Using.resource(scala.io.Source.fromResource("pinned-output.sha256")) { source =>
      source
        .getLines()
        .filterNot(_.startsWith("#"))
        .map { line =>
          val Array(hash, path) = line.split("  ", 2): @unchecked
          path -> hash
        }
        .toMap
    }
    val actual = files.map { (path, text) =>
      val hash = MessageDigest
        .getInstance("SHA-256")
        .digest(text.getBytes(UTF_8))
        .map(b => f"${b & 0xff}%02x")
        .mkString
      path -> hash
    }
    assertEquals(actual, expected)
  }
