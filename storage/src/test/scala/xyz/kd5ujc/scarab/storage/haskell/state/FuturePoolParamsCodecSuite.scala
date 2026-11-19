// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, StateLayout}

/** `PState` 's future parameters at the state's types ([[StakePoolParams]]) keep the bytes the
  * state's `PState` had while it held the node's parameters: the codec of `PState` as it was, the
  * future map written by the node's parameters' codec, writes what the codec of `PState` writes
  * now, and each reads the other's bytes as the same pools, in the 11.1 layout and in the Leios
  * build's, at Conway's version and at Dijkstra's (`State/StakePool.hs:550-589` at cardano-ledger
  * f649f975, `:701-772` at 9df9f98c).
  */
class FuturePoolParamsCodecSuite extends ScalaCheckSuite:
  /** `PState` holding the node's parameters, as it was before the state's parameters. */
  private final case class FlatPState(
      vrfKeyHashes: SortedMap[VRFVerKeyHash, NonZero[Word64]],
      stakePools: SortedMap[KeyHash[StakePool], StakePoolState],
      future: SortedMap[KeyHash[StakePool], node.PoolParams],
      retiring: SortedMap[KeyHash[StakePool], EpochNo]
  )

  private def version(major: Long): ProtVer = ProtVer(Word64.fromUnsigned(major), Word64.zero)

  /** The codecs of a layout's family at a version: the codec of `PState` as it was, and now. */
  private final case class Codecs(
      layout: StateLayout,
      flat: Codec[FlatPState],
      typed: Codec[PState],
      params: Codec[StakePoolParams],
      flatParams: Codec[node.PoolParams]
  )

  private def codecs(family: LedgerFileCodecs, major: Long): Codecs =
    given ProtVer = version(major)
    Codecs(
      family.stateLayout,
      Record.of[FlatPState](
        (
          Wire.mapOf(family.vrfVerKeyHash, family.nonZero[Word64](using Wire.word64)),
          Wire.mapOf(family.keyHash[StakePool], family.stakePoolState),
          Wire.mapOf(family.keyHash[StakePool], family.poolParams),
          Wire.mapOf(family.keyHash[StakePool], family.epochNo)
        )
      ),
      family.pState,
      family.stakePoolParams,
      family.poolParams
    )

  private val families: Vector[Codecs] = Vector(
    codecs(HaskellStateCodecs, 9),
    codecs(NativeStateCodecs, 9),
    codecs(LeiosStateCodecs, 9),
    codecs(LeiosStateCodecs, 12),
    codecs(NativeLeiosStateCodecs, 12)
  )

  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  private def readOk[A](codec: Codec[A], bytes: Bytes): A =
    codec.decodeBytes(bytes, HaskellState.limits).fold(e => fail(e.render), identity)

  /** A pool's state in `layout`, its voting key's place empty in the Leios layout. */
  private def inLayout(s: StakePoolState, layout: StateLayout): StakePoolState =
    s.copy(blsKey = PoolBlsKey.certified(layout))

  private def pState(layout: StateLayout): Gen[(PState, FlatPState)] =
    Gen
      .zip(
        LedgerStateGen.sortedMap(arb[VRFVerKeyHash], arb[NonZero[Word64]]),
        LedgerStateGen.sortedMap(arb[KeyHash[StakePool]], StateGen.stakePoolState),
        Gen.nonEmptyListOf(Gen.zip(arb[KeyHash[StakePool]], StateGen.poolParams)),
        LedgerStateGen.sortedMap(arb[KeyHash[StakePool]], arb[EpochNo])
      )
      .map { (vrfs, pools, future, retiring) =>
        val current = pools.transform((_, s) => inLayout(s, layout))
        val flat = Sorted.map(future.toVector)
        (
          PState(
            vrfs,
            current,
            flat.transform((_, p) => Build.stakePoolParams(p, PoolParamsBlsKey.certified(layout))),
            retiring
          ),
          FlatPState(vrfs, current, flat, retiring)
        )
      }

  families.foreach { c =>
    property(
      s"${c.layout} at the codec's version: a non-empty future map writes the bytes it did"
    ) {
      forAll(pState(c.layout)) { (typed, flat) =>
        val bytes = c.flat.write(flat).fold(v => fail(v.toString), identity)
        assertEquals(c.typed.write(typed), Right(bytes))
        assertEquals(readOk(c.typed, bytes), typed)
        assertEquals(c.flat.write(readOk(c.flat, c.typed.encodeUnchecked(typed))), Right(bytes))
      }
    }
  }

  test(
    "a genesis pool's negative pledge and cost are written as the node's parameters write them"
  ) {
    val p = node.PoolParams(
      Bytes(Vector.fill(28)(2.toByte)),
      Bytes(Vector.fill(32)(3.toByte)),
      BigInt(-1),
      -(BigInt(1) << 70),
      Rational(1, 2),
      node.RewardAccount(1, node.Credential.Script(Bytes(Vector.fill(28)(4.toByte)))),
      Set(Bytes(Vector.fill(28)(5.toByte))),
      Bytes.empty,
      None
    )
    families.foreach { c =>
      val typed = Build.stakePoolParams(p, PoolParamsBlsKey.certified(c.layout))
      assertEquals(c.params.write(typed), c.flatParams.write(p), c.layout.toString)
      assert(c.params.write(typed).isRight)
    }
  }

  test("parameters holding a voting key, or of the other layout, are not written") {
    val p = Build.stakePoolParams(
      node.PoolParams(
        Bytes(Vector.fill(28)(2.toByte)),
        Bytes(Vector.fill(32)(3.toByte)),
        BigInt(0),
        BigInt(0),
        Rational(1, 2),
        node.RewardAccount(0, node.Credential.Key(Bytes(Vector.fill(28)(4.toByte)))),
        Set.empty,
        Bytes.empty,
        None
      )
    )
    val key = BlsKey(Bytes(Vector.fill(96)(6.toByte)), Bytes(Vector.fill(48)(7.toByte)))
    families.foreach { c =>
      val keyed = p.copy(blsKey = PoolParamsBlsKey.Leios(Some(key)))
      assert(c.params.write(keyed).isLeft, s"${c.layout}: a key is written")
      val other = c.layout match
        case StateLayout.Node11_1 => PoolParamsBlsKey.Leios(None)
        case StateLayout.Leios    => PoolParamsBlsKey.Node11_1
      assert(c.params.write(p.copy(blsKey = other)).isLeft, s"${c.layout}: another layout")
      assertEquals(
        c.params.write(p.copy(blsKey = PoolParamsBlsKey.certified(c.layout))).isRight,
        true
      )
    }
    // a key in the Leios layout from version 12 is refused on reading, as before
    // (`DijkstraNotYet.PoolParamsBlsKey`): ten items whose third is a list
    val c = codecs(LeiosStateCodecs, 12)
    val nine = c.flatParams.encodeUnchecked(Convert.poolParamsBack(p))
    val keyItem = StateGen.item { w =>
      w.arrayHead(2)
      w.bytes(key.key)
      w.bytes(key.proofOfPossession)
    }
    val ten = Bytes(
      Vector(0x8a.toByte) ++ nine.value.slice(1, 1 + 30 + 34) ++ keyItem.value ++
        nine.value.drop(1 + 30 + 34)
    )
    assertEquals(
      c.params.decodeBytes(ten, HaskellState.limits).left.map(_.kind),
      Left(DecodeError.Kind.Domain(DijkstraNotYet.PoolParamsBlsKey))
    )
  }

  // ---- the read of the two fields the node's parameters' codec keeps unread (owner's decision of
  // 2026-11-19, #85 item 38): the metadata as `DecCBOR PoolMetadata` at the state's version
  // (`State/StakePool.hs:522-524` at f649f975, `:683-685` at 9df9f98c) and the account's network
  // as `decodeAccountAddressT` (`Address.hs:930-948`, f649f975)

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private val key28 = "01" * 28

  /** Nine items of `StakePoolParams`, its account's header `header` and its metadata `metadata`;
    * the owners' empty set tagged 258 (from version 9) or not (`owners`).
    */
  private def stored(metadata: String, header: String = "e0", owners: String = "d9010280"): Bytes =
    hex(s"89 581c$key28 5820${"02" * 32} 00 00 d81e820102 581d$header$key28 $owners 80 $metadata")

  private def readAt(
      major: Long,
      bytes: Bytes,
      family: LedgerFileCodecs = HaskellStateCodecs
  ): Either[DecodeError.Kind, StakePoolParams] =
    given ProtVer = version(major)
    family.stakePoolParams.decodeBytes(bytes, HaskellState.limits).left.map(_.kind)

  private def refused(e: node.LedgerDecodeError): Left[DecodeError.Kind, StakePoolParams] =
    Left(DecodeError.Kind.Domain(e))

  private def text(s: String): String =
    val b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val head =
      if b.length < 24 then f"${0x60 + b.length}%02x"
      else if b.length < 256 then f"78${b.length}%02x"
      else fail("too long")
    head + b.map(x => f"${x & 0xff}%02x").mkString

  test("the future metadata is a record of a URL and a byte string; anything else is refused") {
    Vector(9L, 12L).foreach { major =>
      assert(readAt(major, stored(s"82 ${text("x")} 4100")).isRight, s"$major")
      assertEquals(
        readAt(major, stored(s"82 ${text("x")} 00")),
        refused(
          node.LedgerDecodeError.Expected(
            node.DecodeField.PoolMetadataHash,
            node.LedgerDecodeError.Shape.Bytes,
            xyz.kd5ujc.scarab.codec.Value.UInt(0)
          )
        ),
        s"$major"
      )
      assertEquals(
        readAt(major, stored(s"83 ${text("x")} 4100 00")),
        refused(node.LedgerDecodeError.ArrayLength(node.DecodeField.PoolMetadata, 2, 3)),
        s"$major"
      )
      assertEquals(
        readAt(major, stored("82 00 4100")),
        refused(
          node.LedgerDecodeError.Expected(
            node.DecodeField.PoolMetadataURL,
            node.LedgerDecodeError.Shape.Text,
            xyz.kd5ujc.scarab.codec.Value.UInt(0)
          )
        ),
        s"$major"
      )
    }
  }

  test("the future metadata's URL is at most 64 bytes below version 9 and 128 from it") {
    assertEquals(
      readAt(2, stored(s"82 ${text("u" * 64)} 4100", owners = "80")).map(_ => ()),
      Right(())
    )
    assertEquals(
      readAt(2, stored(s"82 ${text("u" * 65)} 4100", owners = "80")),
      refused(node.LedgerDecodeError.TextTooLong(node.DecodeField.PoolMetadataURL, 64, 65))
    )
    assert(readAt(9, stored(s"82 ${text("u" * 128)} 4100")).isRight)
    assertEquals(
      readAt(9, stored(s"82 ${text("u" * 129)} 4100")),
      refused(node.LedgerDecodeError.TextTooLong(node.DecodeField.PoolMetadataURL, 128, 129))
    )
  }

  test(
    "the future metadata's hash is read chunked from version 12, its URL only in the Leios build"
  ) {
    val chunkedUrl = "82 7f6178ff 4100"
    val chunkedHash = s"82 ${text("x")} 5f4100ff"
    val url = refused(node.LedgerDecodeError.IndefiniteLength(node.DecodeField.PoolMetadataURL))
    val hash = refused(node.LedgerDecodeError.IndefiniteLength(node.DecodeField.PoolMetadataHash))
    def readOk(major: Long, m: String, family: LedgerFileCodecs): StakePoolParams =
      val read = readAt(major, stored(m), family).fold(k => fail(k.toString), identity)
      assertEquals(read.metadata.map(_.hash), Some(hex("00")), m)
      // and written as the node's parameters write it, from the values
      given ProtVer = version(major)
      assertEquals(
        family.stakePoolParams.write(read),
        family.poolParams.write(Convert.poolParamsBack(read)),
        m
      )
      read
    // below 12 neither build reads either chunked
    Vector(HaskellStateCodecs, LeiosStateCodecs).foreach { family =>
      assertEquals(readAt(9, stored(chunkedUrl), family), url)
      assertEquals(readAt(9, stored(chunkedHash), family), hash)
    }
    // from 12 both builds read a chunked hash (`decodeByteArray`, `Decoder.hs:1429-1448` at f649f975)
    readOk(12, chunkedHash, HaskellStateCodecs)
    readOk(12, chunkedHash, LeiosStateCodecs)
    // and only the Leios build a chunked URL: `decodeString` takes definite text at every version
    // at f649f975, and chunked text from 12 at 9df9f98c (`Decoding/Decoder.hs:1564-1598`)
    assertEquals(readAt(12, stored(chunkedUrl), HaskellStateCodecs), url)
    readOk(12, chunkedUrl, LeiosStateCodecs)
  }

  test("a future VRF key hash of 31 bytes is refused by the state's reader") {
    val short = hex(s"89 581c$key28 581f${"02" * 31} 00 00 d81e820102 581de0$key28 d9010280 80 f6")
    Vector(HaskellStateCodecs, LeiosStateCodecs).foreach { family =>
      // `sppVrf` is a 32-byte hash (`decodeStakePoolParamsFlat`, `State/StakePool.hs:578-589` at
      // f649f975, `:746-772` at 9df9f98c), read by the node's parameters' codec
      assertEquals(readAt(9, short, family), Left(DecodeError.Kind.BadSize("32", 31)))
    }
  }

  test("the future account's network is 0 or 1; a header with bits 1 to 3 set is refused") {
    Vector("e0", "e1", "f0", "f1").foreach(h => assert(readAt(9, stored("f6", h)).isRight, h))
    Vector("e2" -> 2, "e8" -> 8, "fe" -> 14).foreach { (h, network) =>
      assertEquals(
        readAt(9, stored("f6", h)),
        Left(
          DecodeError.Kind.Domain(VocabError.OutOfRange(VocabError.Kind.Network, BigInt(network)))
        ),
        h
      )
    }
  }
