// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.EnvelopeError
import xyz.kd5ujc.scarab.consensus.{ForkTrigger, HardForkConfig}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The era history and envelope checks (B13), against ouroboros-consensus 4.2.0.x
  * `Shelley.Protocol.EnvelopeChecks`, `Shelley.ShelleyHFC` and cardano-ledger Conway `Bbody`.
  */
class HardForkSuite extends ScalaCheckSuite:
  private val byVersion = HardForkConfig.default

  private val eras = HardForkConfig.Sequence

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

  // ---- envelope

  private def check(
      era: Era,
      headerMajor: Int = 10,
      ledgerMajor: Int = 10,
      headerSize: Int = 900,
      bodySize: Int = 1000,
      mainnet: Boolean = false,
      tracks: Boolean = true,
      epoch: Int = 50,
      cfg: HardForkConfig = byVersion
  ) =
    cfg.check(
      era,
      epoch,
      headerMajor,
      ledgerMajor,
      headerSize,
      1100,
      bodySize,
      90112,
      mainnet,
      tracks
    )

  test("a block of the era the version selects passes") {
    assertEquals(check(Era.Conway), Right(()))
    assertEquals(check(Era.Babbage, 8, 8), Right(()))
    assertEquals(check(Era.Alonzo, 6, 6), Right(()))
  }

  test("a block of another era than the version selects is rejected, both ways") {
    assertEquals(
      check(Era.Babbage, 10, 10),
      Left(EnvelopeError.WrongEra(Era.Babbage, Era.Conway, 50, 10))
    )
    assertEquals(
      check(Era.Conway, 8, 8),
      Left(EnvelopeError.WrongEra(Era.Conway, Era.Babbage, 50, 8))
    )
  }

  test("a configured epoch moves the boundary exactly to that epoch") {
    val cfg = HardForkConfig.fromEpochs(Map(Era.Allegra -> 2))
    assertEquals(check(Era.Shelley, 2, 2, epoch = 1, cfg = cfg), Right(()))
    assert(check(Era.Allegra, 2, 2, epoch = 1, cfg = cfg).isLeft)
    assertEquals(check(Era.Allegra, 2, 2, epoch = 2, cfg = cfg), Right(()))
    assert(check(Era.Shelley, 2, 2, epoch = 2, cfg = cfg).isLeft)
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
        .check(Era.Conway, 50, 9, 10, 900, 1100, 1, 2, false),
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
    val all = check(Era.Babbage, 20, 20, 5000, 99999, mainnet = true)
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

  test("a ledger that does not follow governance may enter Conway from a Babbage version only") {
    assertEquals(check(Era.Conway, 9, 8, tracks = false), Right(()))
    assert(check(Era.Conway, 9, 5, tracks = false).isLeft)
    assert(check(Era.Babbage, 9, 8, tracks = false).isRight)
  }
