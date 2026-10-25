// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile}

/** Every signature of the recorded mainnet Byron data in `fixtures/mainnet` (the genesis heavy
  * delegation certificates and the captured main blocks) under both verification profiles: the
  * Byron one ([[xyz.kd5ujc.scarab.crypto.witness.ByronEd25519]]) must accept each, and on recorded
  * history it agrees with the strict one. This is a sample of mainnet's Byron era, not all of it.
  */
class ByronMainnetSignatureSuite extends FunSuite:
  import ByronSignatureCensus.Kind

  private val EpochSlots = BigInt(21600)

  private val genesis = ByronGenesisFile
    .fromJson(
      parser
        .parse(Files.readString(Path.of("fixtures/mainnet/genesis/byron-genesis.json")))
        .fold(e => fail(e.message), identity),
      None
    )
    .fold(e => fail(e.message), identity)

  private val magic = ByronLedger.Config(genesis, requiresNetworkMagic = false).magic

  private val files = Vector(
    "byron-main-blocks.hex",
    "byron-tx-blocks.hex",
    "byron-large-address-block.hex",
    "byron-tx-c1092b9c-block.hex"
  )

  private val blocks: Vector[Block.Byron] =
    files
      .flatMap(f =>
        Files.readAllLines(Path.of("fixtures/mainnet", f)).asScala.toVector.filter(_.nonEmpty)
      )
      .map { l =>
        Block.decode(Bytes.fromHex(l).fold(e => fail(e.toString), identity), EpochSlots) match
          case Right(b: Block.Byron) => b
          case other                 => fail(s"unexpected $other")
      }

  test("every recorded mainnet Byron signature is accepted, by both profiles") {
    val signed = ByronSignatureCensus.genesis(magic, genesis.delegations) ++
      ByronSignatureCensus.all(magic, blocks).fold(e => fail(e.toString), identity)
    val counts = signed.groupMapReduce(_.kind)(_ => 1)(_ + _)
    signed.foreach(s => assert(s.byron && s.strict, s.kind.toString))
    assertEquals(blocks.size, 8)
    assertEquals(counts.getOrElse(Kind.GenesisCertificate, 0), 7)
    assertEquals(counts.getOrElse(Kind.Header, 0), 8)
    assert(counts.getOrElse(Kind.TxWitness, 0) + counts.getOrElse(Kind.RedeemWitness, 0) > 0)
    println(
      s"mainnet Byron signatures verified: ${signed.size} ${counts.toVector.sortBy(_._1.ordinal)}"
    )
  }
