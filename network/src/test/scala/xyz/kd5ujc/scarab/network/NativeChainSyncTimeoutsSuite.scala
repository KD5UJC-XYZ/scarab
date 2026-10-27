// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.protocol.ChainSync.State

class NativeChainSyncTimeoutsSuite extends FailsOnUncaught:
  test("all native active-state deadlines, random transitions and Word64 boundaries agree") {
    val rows = Files
      .readAllLines(Path.of("fixtures/chainsync-timeouts/decisions.jsonl"))
      .asScala
      .toList
    assertEquals(rows.size, 391)
    rows.zipWithIndex.traverse_ { (line, index) =>
      val c = parse(line).fold(e => fail(e.message), identity).hcursor
      def get[A: io.circe.Decoder](field: String): A =
        c.get[A](field).fold(e => fail(s"case $index $field: ${e.message}"), identity)
      def sample(wordField: String): Double =
        val word = BigInt(get[String](wordField))
        val seconds = ChainSyncTimeouts.sampledSeconds(word.toLong)
        assertEquals(seconds, get[Double]("sampleSeconds"))
        val numerator = BigInt(get[String]("sampleNumerator"))
        val denominator = BigInt(get[String]("sampleDenominator"))
        val exact = numerator * BigInt(10).pow(12) / denominator
        assertEquals(ChainSyncTimeouts.sampledPicoseconds(seconds), exact)
        seconds
      get[String]("kind") match
        case "word" =>
          val seconds = sample("word64")
          assertEquals(
            ChainSyncTimeouts.sampledPicoseconds(seconds),
            BigInt(get[String]("picoseconds"))
          )
          IO.unit
        case "state" =>
          val trusted = get[Boolean]("trusted")
          val stateName = get[String]("state")
          val sampled = stateName == "MustReply" && !trusted
          assertEquals(get[Boolean]("advanced"), sampled)
          assertEquals(
            get[String]("generatorBefore") != get[String]("generatorAfter"),
            sampled
          )
          val picos = get[Option[String]]("picoseconds").map(BigInt(_))
          val nativeDeadline = picos.fold[ProtocolDeadline](ProtocolDeadline.Unbounded)(n =>
            ProtocolDeadline.After(((n + 999) / 1000).toLong.nanos)
          )
          val seconds = if sampled then sample("sampleWord64") else 0.0
          if sampled then assertEquals(Some(ChainSyncTimeouts.sampledPicoseconds(seconds)), picos)
          else
            List("sampleWord64", "sampleSeconds", "sampleNumerator", "sampleDenominator")
              .foreach(field => assert(c.downField(field).focus.exists(_.isNull)))
          if stateName == "Idle" then
            val server = ChainSyncRuntime.ServerSettings(idle =
              if get[Boolean]("noIdle") then ProtocolDeadline.Unbounded
              else ChainSyncRuntime.DefaultIdle
            )
            assertEquals(server.idle, nativeDeadline)
            IO.unit
          else
            val state = stateName match
              case "Intersect" => State.Intersect
              case "CanAwait"  => State.NextCanAwait
              case "MustReply" => State.NextMustReply
              case other       => fail(s"unknown native state $other")
            val trust =
              if trusted then ChainSyncTimeouts.PeerTrust.Trustable
              else ChainSyncTimeouts.PeerTrust.Untrustable
            val settings =
              ChainSyncRuntime.ClientSettings(mustReply = ChainSyncTimeouts.Policy.Reference(trust))
            val draw =
              if sampled then IO.pure(seconds)
              else IO.raiseError(new AssertionError("unexpected native-policy random draw"))
            ChainSyncTimeouts
              .next(state, settings)(draw)
              .map(actual => assertEquals(actual, nativeDeadline, s"case $index $stateName"))
        case other => fail(s"unknown native row kind $other")
    }
  }
