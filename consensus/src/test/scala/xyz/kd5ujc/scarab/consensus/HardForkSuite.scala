// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.EnvelopeError
import xyz.kd5ujc.scarab.consensus.{ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.rules.Hfc
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The era history and envelope checks (B13), against ouroboros-consensus 4.2.0.x
  * `Shelley.Protocol.EnvelopeChecks`, `Shelley.ShelleyHFC` and cardano-ledger Conway `Bbody`.
  */
class HardForkSuite extends ScalaCheckSuite:
  private val byVersion = HardForkConfig.default

  private val eras = HardForkConfig.Sequence.toVector

  // ---- the era of a chain started from genesis (`injectInitialExtLedgerState`, Nary.hs:259-306)

  private val genesisK = BigInt(5)

  test("a genesis start enters only the eras configured at its first epoch, in order") {
    val c = HardForkConfig.fromEpochs(Map(Era.Allegra -> 0, Era.Mary -> 0, Era.Babbage -> 0))
    assertEquals(c.triggers(Era.Allegra), ForkTrigger.AtEpoch(0))
    assertEquals(c.triggers(Era.Alonzo), ForkTrigger.AtVersion(5))
    // Alonzo is entered by version, which a genesis state never decides, so Babbage is not reached
    assertEquals(c.initialEra(genesisK)(0), Era.Mary)
    val all = HardForkConfig.fromEpochs(eras.tail.map(_ -> BigInt(0)).toMap)
    assertEquals(all.initialEra(genesisK)(0), Era.Conway)
    val later = HardForkConfig.fromEpochs(Map(Era.Allegra -> 1))
    assertEquals(later.initialEra(genesisK)(0), Era.Shelley)
  }

  test("a genesis start by version stays in Shelley, whatever the genesis protocol version") {
    // the Shelley genesis's `protocolVersion` is in the parameters in force, not a definite update
    assertEquals(byVersion.initialEra(genesisK)(0), Era.Shelley)
    assertEquals(
      byVersion.initialEra(genesisK)(0),
      byVersion.eraOf(genesisK)(Hfc.Pending(Era.Shelley, 0, None, 0L), 0)
    )
  }

  private val epochs = Gen.choose(0, 30)

  property("the era a genesis start is in never decreases as the configured eras move earlier") {
    forAll(epochs, epochs) { (e1, e2) =>
      val early = HardForkConfig.fromEpochs(eras.tail.map(_ -> BigInt(e1.min(e2))).toMap)
      val late = HardForkConfig.fromEpochs(eras.tail.map(_ -> BigInt(e1.max(e2))).toMap)
      assert(late.initialEra(genesisK)(0).ordinal <= early.initialEra(genesisK)(0).ordinal)
    }
  }

  // ---- the transition rule (ouroboros-consensus 82ecba32 `ShelleyHFC.hs:220-227`)

  private val k = BigInt(2160)

  private def pending(era: Era, update: Option[Int], afterVoting: Long = 2160, epoch: Int = 40) =
    Hfc.Pending(era, epoch, update.map(BigInt(_)), afterVoting)

  test("a definite update to exactly the next era's first version leaves the era next epoch") {
    assertEquals(byVersion.transition(k)(pending(Era.Babbage, Some(9))), Some(BigInt(41)))
    assertEquals(byVersion.transition(k)(pending(Era.Mary, Some(5))), Some(BigInt(41)))
  }

  test("a version above the next era's first version is not a transition") {
    // `guard $ pvMajor protVer == transitionMajorVersion`: an equality, not a bound
    assertEquals(byVersion.transition(k)(pending(Era.Babbage, Some(10))), None)
    assertEquals(byVersion.transition(k)(pending(Era.Alonzo, Some(8))), None)
  }

  test("a version that skips an era does not enter the era after it") {
    // Mary to version 7 (Babbage's): the next era is Alonzo, whose version is 5
    assertEquals(byVersion.transition(k)(pending(Era.Mary, Some(7))), None)
    assertEquals(byVersion.eraOf(k)(pending(Era.Mary, Some(7)), 41), Era.Mary)
  }

  test("fewer than k blocks after the voting deadline do not decide the transition") {
    assertEquals(byVersion.transition(k)(pending(Era.Babbage, Some(9), k.toLong - 1)), None)
    assertEquals(byVersion.transition(k)(pending(Era.Babbage, Some(9), k.toLong)), Some(BigInt(41)))
    assertEquals(byVersion.eraOf(k)(pending(Era.Babbage, Some(9), k.toLong - 1), 41), Era.Babbage)
    assertEquals(byVersion.eraOf(k)(pending(Era.Babbage, Some(9), k.toLong), 41), Era.Conway)
  }

  test("no definite update is no transition, even when the parameters in force have the version") {
    assertEquals(byVersion.transition(k)(pending(Era.Babbage, None)), None)
  }

  test("an epoch trigger is the configured epoch, whatever the state; Conway is never left") {
    val c = HardForkConfig.fromEpochs(Map(Era.Allegra -> 2, Era.Mary -> 2))
    assertEquals(c.transition(k)(pending(Era.Shelley, None, 0, epoch = 0)), Some(BigInt(2)))
    assertEquals(byVersion.transition(k)(pending(Era.Conway, Some(10))), None)
    // the combinator translates through every era whose transition the block's epoch has reached
    assertEquals(c.eraOf(k)(pending(Era.Shelley, None, 0, epoch = 1), 1), Era.Shelley)
    assertEquals(c.eraOf(k)(pending(Era.Shelley, None, 0, epoch = 1), 2), Era.Mary)
  }

  // ---- envelope

  private def check(
      era: Era,
      headerMajor: Int = 10,
      ledgerMajor: Int = 10,
      headerSize: Int = 900,
      bodySize: Int = 1000,
      mainnet: Boolean = false,
      expected: Option[Era] = None,
      epoch: Int = 50,
      cfg: HardForkConfig = byVersion
  ) =
    cfg.check(
      era,
      expected.getOrElse(era),
      epoch,
      headerMajor,
      ledgerMajor,
      headerSize,
      1100,
      bodySize,
      90112,
      mainnet
    )

  test("a block of the era the combinator has the chain in passes") {
    assertEquals(check(Era.Conway), Right(()))
    assertEquals(check(Era.Babbage, 8, 8), Right(()))
    assertEquals(check(Era.Alonzo, 6, 6), Right(()))
  }

  test("a block of another era than the combinator's is rejected, both ways") {
    assertEquals(
      check(Era.Babbage, 10, 10, expected = Some(Era.Conway)),
      Left(EnvelopeError.WrongEra(Era.Babbage, Era.Conway, 50, 10))
    )
    assertEquals(
      check(Era.Conway, 8, 8, expected = Some(Era.Babbage)),
      Left(EnvelopeError.WrongEra(Era.Conway, Era.Babbage, 50, 8))
    )
  }

  test("ObsoleteNode: Praos eras check the ledger's major version, TPraos eras the header's") {
    val v = HardForkConfig.MaxMajorProtVer
    assertEquals(check(Era.Conway, headerMajor = v, ledgerMajor = v), Right(()))
    assertEquals(
      check(Era.Conway, headerMajor = 10, ledgerMajor = v + 1),
      Left(EnvelopeError.ObsoleteNode(v + 1, v))
    )
    // the header's own version is not looked at in Praos (the mainnet rule is separate)
    assertEquals(check(Era.Conway, headerMajor = v + 5, ledgerMajor = v), Right(()))
    // TPraos: the header's version counts, the ledger's does not
    assertEquals(check(Era.Alonzo, headerMajor = v, ledgerMajor = 6), Right(()))
    assertEquals(
      check(Era.Alonzo, headerMajor = v + 1, ledgerMajor = 6),
      Left(EnvelopeError.ObsoleteNode(v + 1, v))
    )
    assertEquals(
      HardForkConfig
        .fromEpochs(Map.empty, maxMajorProtVer = 9)
        .check(Era.Conway, Era.Conway, 50, 9, 10, 900, 1100, 1, 2, false),
      Left(EnvelopeError.ObsoleteNode(10, 9))
    )
  }

  test("header and body size limits are inclusive") {
    assertEquals(check(Era.Conway, headerSize = 1100), Right(()))
    assertEquals(
      check(Era.Conway, headerSize = 1101),
      Left(EnvelopeError.HeaderSizeTooLarge(1101, 1100))
    )
    assertEquals(check(Era.Conway, bodySize = 90112), Right(()))
    assertEquals(
      check(Era.Conway, bodySize = 90113),
      Left(EnvelopeError.BlockSizeTooLarge(90113, 90112))
    )
  }

  test("the order of the checks is era, version, header size, body size, header version") {
    val all = check(Era.Babbage, 20, 20, 5000, 99999, mainnet = true, expected = Some(Era.Conway))
    assert(all.left.exists(_.isInstanceOf[EnvelopeError.WrongEra]))
    val sizes = check(Era.Conway, 10, 10, 5000, 99999)
    assertEquals(sizes, Left(EnvelopeError.HeaderSizeTooLarge(5000, 1100)))
  }

  test("Conway on mainnet: the header's version may exceed the ledger's by at most one") {
    assertEquals(check(Era.Conway, headerMajor = 11, ledgerMajor = 10, mainnet = true), Right(()))
    assertEquals(
      check(Era.Conway, headerMajor = 12, ledgerMajor = 10, mainnet = true),
      Left(EnvelopeError.HeaderProtVerTooHigh(12, 11))
    )
    // a header version below the ledger's is fine
    assertEquals(check(Era.Conway, headerMajor = 9, ledgerMajor = 10, mainnet = true), Right(()))
    // not on a test network, and not before Conway
    assertEquals(check(Era.Conway, headerMajor = 12, ledgerMajor = 10), Right(()))
    assertEquals(check(Era.Babbage, headerMajor = 12, ledgerMajor = 8, mainnet = true), Right(()))
  }
