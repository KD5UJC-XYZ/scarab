// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.bls.{
  Committee,
  RbHash,
  Seat,
  SeatId,
  SecretKey,
  Seed,
  VerificationKey,
  Vote,
  Weight
}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{dir, right}

/** The vote this node signs against the reference's own: `fixtures/leios/bls/LeiosVote.golden.cbor`
  * is `typicalVote` of ouroboros-consensus 2416493b (`Test/Consensus/Cardano/Golden.hs:57-62` in
  * `ouroboros-consensus-cardano/test/cardano-test`), `signLeiosVote` of the key
  * `generateWith genLeiosSigningKey 42` at seat 1000 over the hash `[0 .. 31]`. That key is made
  * again here, as the reference's test library makes it, so the signature is the reference's
  * signing of the same key and message, byte for byte.
  *
  * `generateWith gen seed = unGen gen (mkQCGen seed) 30` (cardano-base c1e1ed8d
  * `cardano-crypto-leios/testlib/Test/Cardano/Crypto/Leios/Gen.hs:162-163`), and
  * `genLeiosSigningKey` binds `genKeyDSIGN` to `arbitrarySeedOfSize 32` (`Gen.hs:50-53`), which is
  * `vector 32` of `arbitrary :: Gen Word8` (`cardano-crypto-class/testlib/Test/Crypto/Util.hs:
  * 209-215`). In QuickCheck a bind gives its left side the first half of a split and its right side
  * the second, `vector` is `replicateM`, which binds each element before the rest, and a `Word8` at
  * size 30 is `chooseInt64 (0, 255)`, a bind over `chooseUpTo 255`, `bitmaskWithRejection64'` of
  * splitmix on the generator it is given. `mkQCGen` is `mkSMGen`, [[StdGen.mk]].
  */
class LeiosVoteGoldenSuite extends munit.FunSuite:

  /** `arbitrary :: Gen Word8` at size 30 on `g`. */
  private def word8(g: StdGen): Byte = g.split._1.uniformR(0L, 255L)._1.toByte

  /** `vector n` of `word8`: each element on the first half of a split, the rest on the first half
    * of a split of the second.
    */
  private def bytes(n: Int, g: StdGen): Vector[Byte] =
    Vector
      .unfold((n, g)) { (left, at) =>
        Option.when(left > 0) {
          val (element, rest) = at.split
          (word8(element), (left - 1, rest.split._1))
        }
      }

  /** `generateWith genLeiosSigningKey 42`. */
  private lazy val goldenKey: SecretKey =
    val seed = bytes(32, StdGen.mk(42L).split._1)
    right(SecretKey.generate(right(Seed.fromBytes(Bytes.wrap(seed.toArray)))))

  private lazy val golden: Bytes =
    Bytes.wrap(Files.readAllBytes(dir.resolve("LeiosVote.golden.cbor")))

  private val hash: RbHash = RbHash.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(_.toByte))).get
  private val seat: SeatId = right(SeatId.fromInt(1000))

  test(
    "the vote signed with the reference's key, seat and hash is the reference's, byte for byte"
  ) {
    assertEquals(right(Vote.sign(goldenKey, seat, hash)).encode, golden)
  }

  test("the reference's vote counts with its seat's weight against a committee holding its key") {
    val vk = right(VerificationKey.derive(goldenKey))
    val weight = Weight(Rational(1, 4))
    val committee = right(
      Committee.honoured(
        Vector.fill(1000)(Seat(Weight(Rational(3, 4000)), None)) :+ Seat(weight, Some(vk))
      )
    )
    assertEquals(committee.seatOf(vk), Some(seat))
    assertEquals(VoteInvalid.validate(committee, right(Vote.decode(golden))), Right(Right(weight)))
  }
