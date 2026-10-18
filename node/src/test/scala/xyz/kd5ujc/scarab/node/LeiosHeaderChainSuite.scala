// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.LeiosHeaderChain
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.LeiosHeader
import xyz.kd5ujc.scarab.protocol.HfcHeader
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra

/** The header rules over the first 1,100 headers of the public Leios testnet, from its genesis
  * (`fixtures/leios/testnet/headers-from-origin.tsv`; provenance in `fixtures/leios/README.md`):
  * epoch 0 and the start of epoch 1, all led by the one pool the genesis seats, whose stake is the
  * whole of the distribution. Every operational certificate, KES signature and VRF proof is
  * verified, under epoch nonces evolved from the hash of the Shelley genesis file, and every
  * header's leader value is compared with the pool's threshold.
  */
class LeiosHeaderChainSuite extends munit.FunSuite:
  private val dir = Path.of("fixtures", "leios", "testnet")

  private val setup: NodeSetup =
    NodeSetup
      .fromConfigFile(dir.resolve("config").resolve("config.json"))
      .fold(e => fail(e.message), identity)
  private val config = setup.praos
  private val stake =
    LeiosHeaderChain.StakePolicy(
      setup.mirrorStart
        .ledger(None)
        .toOption
        .flatten
        .fold(fail("the start state has no ledger state")) { case InEra.Of(_, l) =>
          l.leaderPools(nextEpoch = false)
        },
      LeiosHeaderChain.GenesisEpochs
    )
  private val initial = LeiosHeaderChain.initial(setup.mirrorStart.protocol)

  private val headers: Vector[LeiosHeader] =
    Files
      .readAllLines(dir.resolve("headers-from-origin.tsv"))
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map { l =>
        val envelope = Bytes.fromHex(l.split('\t')(4)).fold(e => fail(e), identity)
        val raw = HfcHeader.decode(envelope) match
          case Right(HfcHeader.Supported(h)) => h.raw
          case other                         => fail(other.toString)
        LeiosHeader.decode(raw).fold(e => fail(e.message), identity)
      }

  private def run(
      hs: Vector[LeiosHeader],
      from: LeiosHeaderChain.State = initial
  ): Either[(Int, LeiosHeaderChain.Fault), LeiosHeaderChain.State] =
    @tailrec def go(
        i: Int,
        s: LeiosHeaderChain.State
    ): Either[(Int, LeiosHeaderChain.Fault), LeiosHeaderChain.State] =
      if i >= hs.size then Right(s)
      else
        LeiosHeaderChain.step(config, stake)(s, hs(i)) match
          case Left(f)  => Left(i -> f)
          case Right(n) => go(i + 1, n)
    go(0, from)

  private def corrupt(raw: Bytes, at: Int): LeiosHeader =
    val bit = Bytes(Vector((raw(at) ^ 1).toByte))
    LeiosHeader
      .decode(raw.take(at) ++ bit ++ raw.drop(at + 1))
      .fold(e => fail(e.message), identity)

  test(
    "the node setup reads the genesis: Praos parameters, one seated pool, the hash of the file"
  ) {
    assertEquals(config.slotsPerKesPeriod, BigInt(129600))
    assertEquals(config.maxKesEvolutions, BigInt(62))
    assertEquals(config.geometry.epochLength, 21600L)
    assertEquals(stake.genesis.stake.size, 1)
    assertEquals(stake.genesis.total, BigInt(900000000)) // the delegated funds of `extraConfig`
    assertEquals(
      setup.identity.shelleyGenesis.hex,
      "1944510a4fd91415444285231058f6f6ff0f6f3ff3d0356c76c00c5a77f29567"
    ) // the ShelleyGenesisHash of the published config.json
  }

  test("1,100 headers from the genesis are accepted, every leader threshold evaluated") {
    run(headers) match
      case Right(s) =>
        assertEquals(s.length, 1100L)
        assertEquals(s.leadership, LeiosHeaderChain.Leadership(threshold = 1100, unchecked = 0))
        assertEquals(s.tip.map(_.blockNo), Some(BigInt(1099)))
        // the headers cross the first epoch boundary (slot 21,600), so the nonces evolved once
        assertEquals(s.protocol.epoch, BigInt(1))
        assertNotEquals(s.protocol.epochNonce, initial.protocol.epochNonce)
      case Left((i, f)) => fail(s"header $i: $f")
  }

  test("a chain from another genesis hash fails at the first VRF proof") {
    val other = LeiosHeaderChain.initial(
      ProtocolState.initial(Era.Conway, Bytes(Vector.fill(32)(7.toByte)))
    )
    run(headers.take(3), other) match
      case Left(
            (0, LeiosHeaderChain.Fault.Praos(_: xyz.kd5ujc.scarab.ledger.PraosError.VrfRejected))
          ) =>
        ()
      case other => fail(other.toString)
  }

  test("a missing header, a repeated header and a swapped pair break the chain link") {
    assertEquals(
      run(headers.take(2) ++ headers.drop(3).take(2)).left.map(_._2),
      Left(
        LeiosHeaderChain.Fault.PrevHashDiffers(Some(headers(1).hash), headers(3).prevHash)
      )
    )
    assert(
      run(headers.take(2) :+ headers(1)).left
        .exists(_._2.isInstanceOf[LeiosHeaderChain.Fault.PrevHashDiffers])
    )
    assert(run(Vector(headers(1), headers(0))).isLeft)
    assertEquals(
      run(headers.drop(1).take(1)).left.map(_._2),
      Left(LeiosHeaderChain.Fault.PrevHashDiffers(None, headers(1).prevHash))
    )
  }

  test(
    "a flipped bit in the KES signature, the VRF proof or the certificate is refused by the Praos rules"
  ) {
    val h = headers(5)
    val raw = h.raw
    // locate the fields by their bytes in the encoding
    val kes = raw.size - 448
    val vrfProof = raw.hex.indexOf(h.vrf.proof.hex) / 2
    val sigma = raw.hex.indexOf(h.opCert.sigma.hex) / 2
    Vector("KES" -> (kes + 3), "VRF" -> (vrfProof + 7), "opcert" -> (sigma + 9)).foreach {
      (what, at) =>
        val bad = corrupt(raw, at)
        val prefix = headers.take(5)
        run(prefix :+ bad) match
          case Left((5, LeiosHeaderChain.Fault.Praos(_))) => ()
          case other                                      => fail(s"$what: $other")
    }
  }

  test("a header in the wrong slot is refused: the VRF input is the slot") {
    val h = headers(5)
    val moved = h.copy(slot = h.slot + 1)
    // praosView reads the decoded fields, so the signed bytes and the VRF input now disagree
    assert(run(headers.take(5) :+ moved).isLeft)
  }

  test("a block number that does not follow, and a slot that does not advance, are typed") {
    val h = headers(2)
    assertEquals(
      run(headers.take(2) :+ h.copy(blockNo = BigInt(9))).left.map(_._2),
      Left(LeiosHeaderChain.Fault.BlockNumberNotSuccessor(BigInt(2), BigInt(9)))
    )
    assertEquals(
      run(headers.take(2) :+ h.copy(slot = headers(1).slot)).left.map(_._2),
      Left(LeiosHeaderChain.Fault.SlotNotAfterTip(headers(1).slot, headers(1).slot))
    )
  }
end LeiosHeaderChainSuite
