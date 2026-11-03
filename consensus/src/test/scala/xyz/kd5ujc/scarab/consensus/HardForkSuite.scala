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

  test("by version, the era follows the protocol major version (eraProtVerLow)") {
    val table = Vector(
      2 -> Era.Shelley,
      3 -> Era.Allegra,
      4 -> Era.Mary,
      5 -> Era.Alonzo,
      6 -> Era.Alonzo,
      7 -> Era.Babbage,
      8 -> Era.Babbage,
      9 -> Era.Conway,
      10 -> Era.Conway,
      11 -> Era.Conway
    )
    table.foreach((major, era) => assertEquals(byVersion.eraAt(100, major), era, s"major $major"))
    assertEquals(byVersion.eraAt(0, 0), Era.Shelley)
  }

  test("an epoch trigger ignores the version, a version trigger ignores the epoch") {
    val c = HardForkConfig.fromEpochs(Map(Era.Allegra -> 2, Era.Mary -> 3))
    assertEquals(c.triggers(Era.Allegra), ForkTrigger.AtEpoch(2))
    assertEquals(c.triggers(Era.Alonzo), ForkTrigger.AtVersion(5))
    assertEquals(c.eraAt(1, 9), Era.Shelley) // Allegra not reached yet, whatever the version
    assertEquals(c.eraAt(2, 2), Era.Allegra)
    assertEquals(c.eraAt(3, 2), Era.Mary)
    assertEquals(c.eraAt(3, 5), Era.Alonzo) // the version trigger of Alonzo fires once Mary is in
    assertEquals(c.eraAt(0, 5), Era.Shelley) // eras are entered in order
  }

  private val majors = Gen.choose(0, 14)
  private val epochs = Gen.choose(0, 30)

  property("the era never decreases as the epoch or the major version grows") {
    val configs = Gen.oneOf(
      byVersion,
      HardForkConfig.fromEpochs(Map(Era.Allegra -> 2, Era.Alonzo -> 4, Era.Conway -> 8)),
      HardForkConfig.fromEpochs(eras.tail.zipWithIndex.map((e, i) => e -> BigInt(i)).toMap)
    )
    forAll(configs, epochs, epochs, majors, majors) { (c, e1, e2, m1, m2) =>
      assert(c.eraAt(e1.min(e2), m1.min(m2)).ordinal <= c.eraAt(e1.max(e2), m1.max(m2)).ordinal)
    }
  }

  property("a ledger whose version rises at the configured epochs agrees with the epoch config") {
    // era i is entered at epoch i (configured) and the ledger reaches eraProtVerLow(i) there
    val forEpochs =
      HardForkConfig.fromEpochs(eras.tail.zipWithIndex.map((e, i) => e -> BigInt(i + 1)).toMap)
    forAll(epochs) { epoch =>
      val era = eras(epoch.min(eras.size - 1))
      assertEquals(forEpochs.eraAt(epoch, HardForkConfig.eraProtVerLow(era)), era)
      assertEquals(byVersion.eraAt(epoch, HardForkConfig.eraProtVerLow(era)), era)
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
    // the stateless walk of an initial state would put the same version in Babbage
    assertEquals(byVersion.eraAt(41, 7), Era.Babbage)
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
