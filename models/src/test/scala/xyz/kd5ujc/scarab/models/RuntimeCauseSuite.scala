// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer}

class RuntimeCauseSuite extends FunSuite:
  test("generated-model decoding retains malformed CBOR as a typed syntax cause") {
    val result = Codec.bytes.decodeBytes(Bytes.empty)
    assertEquals(result.left.map(_.kind), Left(DecodeError.Kind.Syntax(Cbor.Error.UnexpectedEnd)))
  }

  test("a decoder defect retains the original throwable") {
    val cause = new IllegalStateException("decoder defect")
    val broken = new Codec[Unit]:
      def decode(node: Node): Decoded[Unit] = throw cause
      def encode(value: Unit, out: Writer): Unit = ()
    val valid = Bytes.fromHex("00").fold(e => fail(e.message), identity)
    broken.decodeBytes(valid) match
      case Left(DecodeError(DecodeError.Kind.Internal(actual), _, _, _)) => assert(actual eq cause)
      case other => fail(s"expected retained decoder defect, got $other")
  }

  private enum Construction extends xyz.kd5ujc.scarab.codec.DomainError:
    case MissingRuntime

  private def decoding(result: Decoded[Unit]): Codec[Unit] = new Codec[Unit]:
    def decode(node: Node) = result
    def encode(value: Unit, out: Writer): Unit = ()

  private val zero = Cbor.decode(Bytes(Vector(0.toByte))).fold(e => fail(e.message), identity)

  private val defects = Vector(
    DecodeError(DecodeError.Kind.Internal(new IllegalStateException("original")), List("model"), 0),
    DecodeError(DecodeError.Kind.Reconstruction(Construction.MissingRuntime), List("model"), 0)
  )

  defects.zipWithIndex.foreach { (error, index) =>
    test(s"terminal decoder failure $index prevents a succeeding alternative") {
      val later = new java.util.concurrent.atomic.AtomicBoolean(false)
      val alternatives = new xyz.kd5ujc.scarab.models.runtime.Alternatives[Unit](
        Array(
          _ => Left(error),
          _ => { later.set(true); Right(()) }
        )
      )
      assertEquals(alternatives(zero), Left(error))
      assert(!later.get())
    }

    test(s"terminal decoder failure $index cannot make an optional field absent") {
      val later = new java.util.concurrent.atomic.AtomicBoolean(false)
      val result = xyz.kd5ujc.scarab.models.runtime.Arr.optional(
        Vector(zero),
        0,
        decoding(Left(error)),
        "optional"
      ) { (_, _) =>
        later.set(true); Right(())
      }
      assertEquals(result, Left(error.at("optional")))
      assert(!later.get())
    }
  }

  test("ordinary mismatch still selects another alternative and an absent optional field") {
    val mismatch = DecodeError(DecodeError.Kind.TypeMismatch("bytes", "integer"), Nil, 0)
    val alternatives = new xyz.kd5ujc.scarab.models.runtime.Alternatives[Unit](
      Array(
        _ => Left(mismatch),
        _ => Right(())
      )
    )
    assertEquals(alternatives(zero), Right(()))
    val optional = xyz.kd5ujc.scarab.models.runtime.Arr.optional(
      Vector(zero),
      0,
      decoding(Left(mismatch)),
      "optional"
    ) { (value, position) => Right((value, position)) }
    assertEquals(optional, Right((None, 0)))
  }

  test("nested alternative failures retain path and cause in a bounded small-stack process") {
    val classes = Vector(
      getClass,
      classOf[DecodeError],
      classOf[xyz.kd5ujc.scarab.codec.DomainError],
      classOf[scala.Option[?]],
      classOf[scala.deriving.Mirror]
    )
    val classpath = classes
      .map(c =>
        java.nio.file.Path
          .of(
            c.getProtectionDomain.getCodeSource.getLocation.toURI
          )
          .toString
      )
      .distinct
      .mkString(java.io.File.pathSeparator)
    val javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString
    val output = java.nio.file.Files.createTempFile("scarab-terminal-depth-", ".log")
    try
      val builder = new ProcessBuilder(
        javaCommand,
        "-XX:ActiveProcessorCount=2",
        "-Xss256k",
        "-Xmx128m",
        "-cp",
        classpath,
        "xyz.kd5ujc.scarab.models.TerminalDepthProbe"
      )
        .redirectErrorStream(true)
        .redirectOutput(output.toFile)
      // JVM injection variables can override the explicit small-stack and resource controls.
      Vector("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").foreach { name =>
        builder.environment().remove(name)
      }
      val process = builder.start()
      val completed =
        try process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        finally
          if process.isAlive then process.destroyForcibly()
          process.waitFor()
      val diagnostic = java.nio.file.Files.readString(output)
      assert(completed, s"terminal traversal timed out: $diagnostic")
      assertEquals(process.exitValue(), 0, diagnostic)
    finally java.nio.file.Files.deleteIfExists(output)
  }

/** A separate process makes timeout cleanup possible even if the pure traversal stops progressing.
  */
object TerminalDepthProbe:
  def main(args: Array[String]): Unit =
    assert(args.isEmpty)
    assert(
      Vector("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forall(
        sys.env.get(_).isEmpty
      )
    )
    val original = new IllegalStateException("original")
    val leaf = DecodeError(DecodeError.Kind.Internal(original), List("model"), 7)
    val nested = (0 until 20000).foldLeft(leaf) { (cause, _) =>
      DecodeError(DecodeError.Kind.NoAlternative(Vector(cause)), List("outer"), 0)
    }
    nested.terminal match
      case Some(terminal) =>
        terminal.cause match
          case DecodeError.TerminalCause.Internal(actual) => assert(actual eq original)
          case other => throw new AssertionError(s"wrong terminal: $other")
        assert(terminal.error.path.size == 20001)
        assert(terminal.error.path.take(20000).forall(_ == "outer"))
        assert(terminal.error.path.drop(20000) == leaf.path)
        assert(terminal.error.offset == leaf.offset)
      case None => throw new AssertionError("lost terminal failure")
