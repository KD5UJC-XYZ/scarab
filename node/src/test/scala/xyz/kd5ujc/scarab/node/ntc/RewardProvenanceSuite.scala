// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Credential, PoolParams, RewardAccount}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Coin,
  KeyHash,
  ProtVer,
  RewardBlockCount,
  ShelleyBasedEra,
  Sorted,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.storage.haskell.state.Versions

/** The codec of `RewardProvenance` (`Cardano/Ledger/Shelley/RewardProvenance.hs`, cardano-ledger
  * f649f975) beyond the default the queries answer with (whose bytes `MirrorAnswersSuite` holds
  * against the reference's): a provenance with a pool in it reads back as it was written, before
  * Conway and in it.
  */
class RewardProvenanceSuite extends FunSuite:
  private val id = KeyHash
    .fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte)))
    .fold(e => fail(e.toString), identity)
  private val owner = Bytes.fromArray(Array.fill(28)(8.toByte))

  private val pool = RewardProvenancePool(
    RewardBlockCount.fromBigInt(3).fold(e => fail(e.toString), identity),
    Rational(1, 3),
    Rational(2, 5),
    Coin(1000),
    PoolParams(
      id.bytes,
      Bytes.fromArray(Array.fill(32)(9.toByte)),
      BigInt(500),
      BigInt(340),
      Rational(1, 10),
      RewardAccount(0, Credential.Key(owner)),
      Set(owner),
      Bytes.empty,
      None
    ),
    Rational(1, 2),
    Coin(70000),
    Rational(3, 4),
    Coin(65000),
    Coin(800)
  )

  private val populated = RewardProvenance.default.copy(
    spe = Word64.fromUnsigned(500L),
    blocks = BlocksMade(Sorted.map(List(id -> Word64.fromUnsigned(3L)))),
    totalStake = Coin(BigInt(10).pow(16)),
    blocksCount = BigInt(3),
    d = Rational(1, 2),
    expBlocks = BigInt(100),
    eta = Rational(3, 100),
    pools = Sorted.map(List(id -> pool)),
    desirabilities = Sorted.map(List(id -> Desirability(1234.5, 0.75)))
  )

  test("a provenance with a pool reads back as it was written, before Conway and in Conway") {
    List(ShelleyBasedEra.SShelley, ShelleyBasedEra.SBabbage, ShelleyBasedEra.SConway).foreach {
      era =>
        given ProtVer = Versions.eraLow(era)
        val codec = RewardProvenance.codec
        val written = codec.write(populated).fold(e => fail(s"$era: $e"), identity)
        assertEquals(codec.decodeBytes(written), Right(populated), s"$era")
    }
  }

  test("the rationals are under tag 30 in Conway only") {
    def default(era: ShelleyBasedEra[?]): String =
      RewardProvenance
        .codec(using Versions.eraLow(era))
        .write(RewardProvenance.default)
        .fold(e => fail(s"$era: $e"), _.hex)
    assertEquals(default(ShelleyBasedEra.SBabbage), "9000a000000000000082000100820001000000a0a0")
    assertEquals(
      default(ShelleyBasedEra.SConway),
      "9000a0000000000000d81e82000100d81e820001000000a0a0"
    )
  }
