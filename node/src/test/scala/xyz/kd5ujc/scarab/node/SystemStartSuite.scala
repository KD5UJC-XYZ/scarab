// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.Genesis

import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite

/** The slot clock starts where the reference's does: at the Byron genesis' `startTime` (H6). */
class SystemStartSuite extends FunSuite:
  private val dir = Path.of("fixtures/eras/genesis")

  private def setupWith(shelleyStart: Instant): NodeSetup =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name))) {
        val bytes = Files.readAllBytes(dir.resolve(name))
        if name == "shelley-genesis.json" then
          new String(bytes, "UTF-8")
            .replaceAll("\"systemStart\"\\s*:\\s*\"[^\"]*\"", s"\"systemStart\": \"$shelleyStart\"")
            .getBytes("UTF-8")
        else bytes
      }
    NodeSetup.fromFiles(read).fold(e => fail(e.message), identity)

  private val byronStart: Instant =
    val unchanged = NodeSetup.fromDirectory(dir).fold(e => fail(e.message), identity)
    Instant.ofEpochSecond(
      unchanged.byronParams.flatMap(_.startTime).getOrElse(fail("no startTime"))
    )

  test(
    "a Shelley systemStart a second (or a fraction) off the Byron startTime does not move the clock"
  ) {
    List(
      byronStart.plusSeconds(1),
      byronStart.minusSeconds(1),
      byronStart.plusMillis(500),
      byronStart
    ).foreach { shelley =>
      val setup = setupWith(shelley)
      assertEquals(setup.geometry.systemStart, byronStart, s"Shelley systemStart $shelley")
      assertEquals(setup.praos.geometry.systemStart, byronStart)
    }
  }

  test("without a Byron startTime the Shelley systemStart stands") {
    val shelley = Genesis
      .shelley(new String(Files.readAllBytes(dir.resolve("shelley-genesis.json")), "UTF-8"))
      .fold(e => fail(e.message), identity)
    assertEquals(Genesis.withSystemStart(shelley, None), shelley)
  }
