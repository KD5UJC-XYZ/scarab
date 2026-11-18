// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{NtcVersion, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** `DebugLedgerConfig` against cardano-node 11.1.3's own answers, recorded from a node started on
  * each genesis directory (`fixtures/ntc-golden/debug-ledger-config.json`): the configuration of
  * the Conway devnet and of the eras capture, whose eras are entered at configured epochs, and
  * those of preview, preprod and mainnet, whose eras (on preview, Babbage and Conway) are entered
  * by protocol version, mainnet's without the network magic and with AVVM balances, which the
  * reference's ledger configuration leaves out. The answer is the configuration's, whatever the
  * state; below node-to-client version 20 the query is refused (`QueryVersion3`).
  */
class LedgerConfigSuite extends FunSuite:
  private val golden = parser
    .parse(Files.readString(Path.of("fixtures/ntc-golden/debug-ledger-config.json")))
    .fold(e => fail(e.message), identity)
    .hcursor

  private final case class Case(
      genesis: String,
      refused: Vector[Int],
      answered: Vector[Int],
      result: String
  )

  private val cases = golden
    .downField("cases")
    .values
    .toVector
    .flatten
    .map(_.hcursor)
    .map(c =>
      (
        c.get[String]("genesis"),
        c.get[Vector[Int]]("refused"),
        c.get[Vector[Int]]("answered"),
        c.get[String]("result")
      ) match
        case (Right(g), Right(r), Right(a), Right(x)) => Case(g, r, a, x)
        case other                                    => fail(s"malformed case: $other")
    )

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.message), identity)

  /** Where two encodings first differ, with the bytes around it. */
  private def firstDifference(got: Bytes, expected: Bytes): String =
    val at = got.toArray.zip(expected.toArray).indexWhere(_ != _) match
      case -1 => got.size.min(expected.size)
      case i  => i
    def around(b: Bytes) = b.slice((at - 16).max(0), at + 32).hex
    s"length ${got.size} against ${expected.size}; first difference at byte $at: ${around(got)} against ${around(expected)}"

  test("the fixture has the five genesis directories, refused below 20 and answered from 20") {
    assertEquals(
      cases.map(_.genesis),
      Vector(
        "fixtures/devnet/lsq/genesis",
        "fixtures/eras/genesis",
        "fixtures/preview/genesis",
        "fixtures/preprod/genesis",
        "fixtures/mainnet/genesis"
      )
    )
    cases.foreach { c =>
      assertEquals(c.refused, Vector(16, 19), c.genesis)
      assertEquals(c.answered, Vector(20, 21, 22, 23), c.genesis)
    }
  }

  cases.foreach { c =>
    lazy val setup = NodeSetup
      .fromDirectory(ReferenceBuild.Default)(Path.of(c.genesis))
      .fold(e => fail(e.message), identity)

    test(s"${c.genesis}: the ledger configuration equals the reference's") {
      val expected = hex(c.result)
      setup.ledgerConfig match
        case Right(got) =>
          assert(got == expected, firstDifference(got, expected))
        case Left(error) => fail(s"not written: ${error.message}")
    }

    test(s"${c.genesis}: DebugLedgerConfig is refused below version 20 and answered from it") {
      val env = QueryEnv(
        setup.geometry.systemStart,
        setup.geometry,
        setup.praos.forks,
        EraHistory.ByronParams(50, 1000, 5),
        setup.globals.maxSupply,
        setup.genesis.raw,
        setup.mirrorEnv().ledger,
        networkMagic = setup.genesis.networkMagic.toLong,
        ledgerConfig = LedgerConfig.answer(setup.ledgerConfig),
        layout = StateLayout.Node11_1
      )
      val view = QueryView(Point.Origin, None, setup.mirrorStart)
      val query = hex("8104")
      NtcVersion.All.foreach { version =>
        val answer = NtcQuery
          .decode(query, version)
          .map(q => QueryAnswers.answerOrWhy(env, view, Map.empty, q, version))
        if version.number < 20 then
          assertEquals(
            answer,
            Left(
              NtcQuery.Error
                .RequiresQueryVersion(NtcQuery.DebugLedgerConfig, QueryVersion.QueryVersion3)
            ),
            s"${version.number}"
          )
        else assertEquals(answer.map(_.map(_.hex)), Right(Right(c.result)), s"${version.number}")
      }
    }
  }
