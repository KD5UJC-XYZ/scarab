// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Files
import lab.cbor.Bytes

class NativeScriptFixtureMainSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  // Preserve an indefinite MemPack map exactly; surrounding fields are only layout scaffolding.
  private val mempack = "bf41004101ff"
  private val seed = bytes("870080808480828086" + mempack + "000080800080808080f6")

  test("MemPack accessor retains the original span without re-encoding") {
    assertEquals(NativeEpochComponents.mempackUtxo(seed), Right(bytes(mempack)))
  }
  test("MemPack accessor rejects malformed layouts, trailing bytes and oversized input") {
    Vector(
      Bytes.empty,
      bytes("80"),
      bytes("8700808080808080"),
      Bytes(seed.value :+ 0.toByte),
      Bytes(Vector.fill(524289)(0.toByte))
    ).foreach { raw =>
      assert(NativeEpochComponents.mempackUtxo(raw).isLeft)
    }
    assert(NativeEpochComponents.mempackUtxo(null).isLeft)
  }
  test("proof publication fails on an existing target and preserves complete bytes") {
    IO.blocking(Files.createTempDirectory("native-script-proof-test-"))
      .bracket { dir =>
        val target = dir.resolve("proof.json")
        val proof = NativeLiveBoundaryMain.record("passed" -> NativeLiveBoundaryMain.bool(true))
        for
          _ <- NativeScriptFixtureMain.saveNew(target, proof)
          before <- IO.blocking(Files.readAllBytes(target).toVector)
          duplicate <- NativeScriptFixtureMain.saveNew(target, proof).attempt
          after <- IO.blocking(Files.readAllBytes(target).toVector)
          _ <- IO {
            assert(duplicate.isLeft)
            assertEquals(after, before)
            assertEquals(before, NativeLiveBoundaryMain.encode(proof).value)
          }
        yield ()
      } { dir =>
        IO.blocking {
          Files.deleteIfExists(dir.resolve("proof.json"))
          Files.delete(dir)
        }
      }
      .unsafeToFuture()
  }
