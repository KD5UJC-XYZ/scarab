// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.Era

/** `DebugChainDepState` (`GetChainDepState`, `cardano-cli query protocol-state`) as the reference
  * node answers it, at points of an era-walking chain (`fixtures/chain-dep`, issue 90): the TPraos
  * state in the Shelley to Alonzo eras, the Praos state from Babbage, and the first Babbage epoch,
  * where the Praos state is the translation of the TPraos one (`Praos.hs:763-773`). The chain is
  * replayed from its blocks and each answer must be the reference's bytes.
  */
class ChainDepGoldenSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/chain-dep")

  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(fixtures.resolve("genesis"))
      .fold(e => fail(e.message), identity)

  private val queryEnv = QueryEnv(
    setup.byronParams
      .flatMap(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger
  )

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The state after each block, by the hash of the block. */
  private lazy val states: Map[String, (Block, MirrorState)] =
    blocks.indices
      .foldLeft((setup.mirrorStart, Map.empty[String, (Block, MirrorState)])) {
        case ((before, acc), i) =>
          MirrorSteps.block(setup, before, blocks(i).header, raws(i)) match
            case Right(next) =>
              val hash = blocks(i).header.hash.hex
              assert(!acc.contains(hash), s"duplicate captured block at line ${i + 1}: $hash")
              (next, acc.updated(hash, (blocks(i), next)))
            case Left(f) => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }
      ._2

  /** A point the reference was asked at: the tip it acquired, and what it answered. */
  private final case class Golden(
      era: Era,
      epoch: BigInt,
      slot: BigInt,
      hash: String,
      answer: String
  )

  private val goldens: Vector[Golden] =
    parser
      .parse(Files.readString(fixtures.resolve("answers.json")))
      .flatMap(_.as[Vector[io.circe.Json]])
      .fold(e => fail(e.getMessage), identity)
      .map { j =>
        val c = j.hcursor
        val get = (c.get[String]("era"), c.get[BigInt]("epoch"), c.get[BigInt]("slot"))
        val era = get._1.toOption.flatMap(n => Era.values.find(_.toString.equalsIgnoreCase(n)))
        Golden(
          era.getOrElse(fail("unknown era")),
          get._2.getOrElse(fail("epoch")),
          get._3.getOrElse(fail("slot")),
          c.get[String]("hash").getOrElse(fail("hash")),
          c.get[String]("answer").getOrElse(fail("answer"))
        )
      }

  private def label(g: Golden): String = s"${g.era} epoch ${g.epoch} slot ${g.slot}"

  test("the capture has points in every Shelley-based era and in the first Babbage epoch") {
    assertEquals(raws.size, 789, "the complete original block corpus is required")
    assertEquals(goldens.size, 43, "the complete original answer corpus is required")
    assertEquals(goldens.map(_.hash).distinct.size, goldens.size, "acquired points must be unique")
    assertEquals(
      goldens.map(_.era).distinct.toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    // the first Babbage epoch is the first epoch of that era in the capture
    val babbage = goldens.filter(_.era == Era.Babbage).map(_.epoch)
    assert(goldens.count(g => g.era == Era.Babbage && g.epoch == babbage.min) >= 2)
  }

  test("every point is on the replayed chain, in the era the reference said") {
    goldens.foreach { g =>
      val (block, state) = states.getOrElse(g.hash, fail(s"${label(g)}: no such block"))
      assertEquals(block.header.slot, g.slot, label(g))
      assertEquals(state.era, g.era, label(g))
    }
  }

  test("DebugChainDepState is the reference's answer, byte for byte, at every point") {
    goldens.foreach { g =>
      val (block, state) = states(g.hash)
      val view = QueryView(
        Point.Block(UInt64.saturating(g.slot), block.header.hash),
        Some(block.header.blockNo),
        state
      )
      val answered = QueryAnswers
        .answer(
          queryEnv,
          view,
          Map.empty,
          NtcQuery.IfCurrent(g.era.index, LedgerQuery.DebugChainDepState)
        )
        .getOrElse(fail(s"${label(g)}: no answer"))
      assertEquals(answered.hex, g.answer, label(g))
    }
  }
