// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.given
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.given
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Writer}

import DecodeError.Kind
import HaskellStateCodecs.{*, given}
import Wire.{mapOf, setOf}

/** The Haskell ledger-state codecs: decode after encode is the identity on every type and every
  * era, encode after decode is the identity on bytes, the decoders refuse what the Haskell ones do
  * (with a typed error), and a few values are checked against bytes worked out by hand from the
  * Haskell instances (cited in the tests).
  */
class HaskellStateCodecSuite extends ScalaCheckSuite:
  // The version of a Conway state, which writes sets with tag 258.
  given conwayVersion: ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def version(major: Long): ProtVer = ProtVer(Word64.fromUnsigned(major), Word64.zero)

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def bytesOf(body: Writer => Unit): Bytes = StateGen.item(body)

  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  private val key28 = "01" * 28
  private val keyHash28: KeyHash[Staking] =
    KeyHash.fromBytes[Staking](hex(key28)).fold(e => fail(e.toString), identity)

  private def read[A](codec: Codec[A], bytes: Bytes): Either[DecodeError, A] =
    codec.decodeBytes(bytes, HaskellState.limits)

  private def readOk[A](codec: Codec[A], bytes: Bytes): A =
    read(codec, bytes).fold(e => fail(e.render), identity)

  /** The two laws of a codec, on its generator. */
  private def laws[A](name: String, gen: Gen[A], codec: Codec[A]): Unit =
    property(s"$name: decode after encode is the identity, and encode after decode is on bytes") {
      forAll(gen) { a =>
        val bytes = codec.encodeUnchecked(a)
        assertEquals(codec.check(a), None, "a generated value is writable")
        val back = readOk(codec, bytes)
        assertEquals(back, a)
        assertEquals(codec.encodeUnchecked(back), bytes)
      }
    }

  private def failure[A](codec: Codec[A], bytes: Bytes): DecodeError =
    read(codec, bytes).fold(identity, a => fail(s"decoded $a"))

  // ---- vocabulary

  laws("Coin", arb[Coin], coin)
  laws("DeltaCoin", arb[DeltaCoin], deltaCoin)
  laws("CompactCoin", arb[CompactCoin], compactCoin)
  laws("EpochNo", arb[EpochNo], epochNo)
  laws("Credential[Staking]", arb[Credential[Staking]], credential[Staking])
  laws("DRep", arb[DRep], drep)
  laws("NonZero[Word64]", arb[NonZero[Word64]], nonZero[Word64])
  laws("Anchor", StateGen.nodeAnchor, anchor)
  laws("Rational (plain)", StateGen.nonNegative, Wire.ratio)
  laws("UnitInterval", StateGen.unit, Wire.unitInterval)
  laws("ProtVer", arb[ProtVer], protVer)
  laws("Ptr", LedgerStateGen.ptr, ptr)

  test("Coin encoding matches every captured native signed and compact boundary") {
    val rows = java.nio.file.Files
      .readString(
        java.nio.file.Path.of("fixtures/reference/coin-arithmetic/cases.jsonl")
      )
      .linesIterator
      .filter(_.nonEmpty)
      .toVector
    assertEquals(rows.size, 11)
    rows.foreach { line =>
      val row = io.circe.parser.parse(line).fold(e => fail(e.message), identity).hcursor
      val amount = BigInt(row.get[String]("left").fold(e => fail(e.message), identity))
      val expected = hex(row.get[String]("coinBytes").fold(e => fail(e.message), identity))
      assertEquals(coin.encodeUnchecked(Coin(amount)), expected)
      if amount >= 0 && amount < (BigInt(1) << 64) then
        assertEquals(read(coin, expected), Right(Coin(amount)))
      else assert(read(coin, expected).isLeft)
    }
  }

  test("amounts: Coin is written as an Integer and read as a Word64 (Coin.hs:93,111)") {
    val big = (BigInt(1) << 64) - 1
    val c = Coin(big)
    assertEquals(coin.encodeUnchecked(c).hex, "1bffffffffffffffff")
    assertEquals(readOk(coin, coin.encodeUnchecked(c)), c)
    // above 2^64 - 1 the writer would emit a bignum, which the Word64 reader refuses
    val over = bytesOf(_.integer(BigInt(1) << 64))
    assert(failure(coin, over).kind.isInstanceOf[Kind.TypeMismatch], "a bignum is not a Word64")
    val negative = coin.encodeUnchecked(Coin(-1))
    assertEquals(negative.hex, "20")
    assert(failure(coin, negative).kind.isInstanceOf[Kind.TypeMismatch])
    // a DeltaCoin is an Integer both ways, bignums included
    val delta = DeltaCoin(-(BigInt(1) << 70))
    assertEquals(readOk(deltaCoin, deltaCoin.encodeUnchecked(delta)), delta)
  }

  test("hashes have the size of their type; a smart constructor's refusal is a located error") {
    val short = bytesOf(_.bytes(hex("0102")))
    val e = failure(keyHash[Staking], short)
    assert(e.kind.isInstanceOf[Kind.BadSize], e.render)
    assertEquals(
      failure(vrfVerKeyHash, bytesOf(_.bytes(hex(key28)))).kind.getClass,
      classOf[Kind.BadSize]
    )
    val zero = bytesOf(_.uint(0L))
    assertEquals(
      failure(nonZero[Word64], zero).kind,
      Kind.Domain(
        xyz.kd5ujc.scarab.ledger.state.VocabError.Zero(
          xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.NonZero
        )
      )
    )
  }

  test("pointers are strict from version 9 and normalised before (Credential.hs:360-364)") {
    val outOfRange = bytesOf { w =>
      w.arrayHead(3); w.uint(BigInt(1) << 40); w.uint(1L); w.uint(2L)
    }
    assert(failure(ptr(using version(9)), outOfRange).kind.isInstanceOf[Kind.OutOfRange])
    assertEquals(
      readOk(ptr(using version(7)), outOfRange),
      xyz.kd5ujc.scarab.ledger.Ptr(0, 0, 0)
    )
    val wide = bytesOf { w =>
      w.arrayHead(3); w.uint(1L); w.uint(70000L); w.uint(2L)
    }
    assert(failure(ptr(using version(9)), wide).kind.isInstanceOf[Kind.OutOfRange])
  }

  test("a protocol version has a major in 0..13 and a Word32 minor (Version.hs:55-110)") {
    assertEquals(
      protVer.encodeUnchecked(ProtVer(Word64.fromUnsigned(9), Word64.zero)).hex,
      "820900"
    )
    val tooBig = bytesOf { w =>
      w.arrayHead(2); w.uint(14L); w.uint(0L)
    }
    assert(failure(protVer, tooBig).kind.isInstanceOf[Kind.OutOfRange])
    val bigMinor = bytesOf { w =>
      w.arrayHead(2); w.uint(9L); w.uint(BigInt(1) << 32)
    }
    assert(failure(protVer, bigMinor).kind.isInstanceOf[Kind.OutOfRange])
    assertEquals(
      protVer.check(ProtVer(Word64.fromUnsigned(14), Word64.zero)).isDefined,
      true
    )
  }

  // ---- containers and versions

  private def setAt(v: ProtVer): Codec[SortedSet[Credential[Staking]]] =
    given ProtVer = v
    setOf(credential[Staking])

  private def mapAt(v: ProtVer): Codec[SortedMap[Credential[Staking], Int]] =
    given ProtVer = v
    mapOf(credential[Staking], Codec.uintInt(10))

  private val stakingKey: Credential[Staking] = Credential.KeyHashObj(keyHash28)
  private val otherKey: Credential[Staking] =
    Credential.KeyHashObj(KeyHash.fromBytes[Staking](Bytes(Vector.fill(28)(2.toByte))).toOption.get)

  test("sets: tag 258 from version 9, none before; either length form; the tag is optional") {
    val one = Sorted.set(Seq(stakingKey))
    val item = "8200581c" + key28
    assertEquals(setAt(version(9)).encodeUnchecked(one).hex, "d9010281" + item)
    assertEquals(setAt(version(7)).encodeUnchecked(one).hex, "81" + item)
    // read: at 9 the tag is optional, at 7 it is not permitted
    assertEquals(readOk(setAt(version(9)), hex("81" + item)), one)
    assertEquals(readOk(setAt(version(9)), hex("9f" + item + "ff")), one)
    assertEquals(readOk(setAt(version(9)), hex("d901029f" + item + "ff")), one)
    assert(failure(setAt(version(7)), hex("d9010281" + item)).kind.isInstanceOf[Kind.BadTag])
  }

  test(
    "sets and maps above 23 entries are indefinite, and either form reads (Encoder.hs:430-466)"
  ) {
    val credentials: Vector[Credential[Staking]] = (0 until 24).toVector.map(i =>
      Credential.KeyHashObj(
        KeyHash.fromBytes[Staking](Bytes(Vector.fill(28)(i.toByte))).toOption.get
      )
    )
    val set = Sorted.set(credentials)
    val codec = setAt(version(9))
    val bytes = codec.encodeUnchecked(set)
    assertEquals(bytes.hex.take(8), "d901029f")
    assertEquals(bytes.hex.takeRight(2), "ff")
    assertEquals(readOk(codec, bytes), set)
    val definite = bytesOf { w =>
      w.tag(258)
      w.arrayHead(24)
      credentials.foreach(credential[Staking].encode(_, w))
    }
    assertEquals(readOk(codec, definite), set)
    val map = Sorted.map(credentials.map(_ -> 1))
    val mapCodec = mapAt(version(9))
    assertEquals(mapCodec.encodeUnchecked(map).hex.take(2), "bf")
    assertEquals(readOk(mapCodec, mapCodec.encodeUnchecked(map)), map)
    // 23 is the longest definite one
    assertEquals(
      mapCodec.encodeUnchecked(Sorted.map(credentials.take(23).map(_ -> 1))).hex.take(2),
      "b7"
    )
  }

  test(
    "a repeated element or key is refused from version 9 and ignored before (Decoder.hs:815-962)"
  ) {
    val c = credential[Staking]
    val twice = bytesOf { w =>
      w.arrayHead(2)
      c.encode(stakingKey, w)
      c.encode(stakingKey, w)
    }
    assert(failure(setAt(version(9)), twice).kind.isInstanceOf[Kind.DuplicateElement])
    assertEquals(readOk(setAt(version(7)), twice).size, 1)
    val pairs = bytesOf { w =>
      w.mapHead(2)
      c.encode(stakingKey, w)
      w.uint(1L)
      c.encode(stakingKey, w)
      w.uint(2L)
    }
    assert(failure(mapAt(version(9)), pairs).kind.isInstanceOf[Kind.DuplicateKey])
    assertEquals(readOk(mapAt(version(7)), pairs).values.toList, List(2))
    // elements out of order are accepted, then written in order
    val unordered = bytesOf { w =>
      w.tag(258)
      w.arrayHead(2)
      c.encode(otherKey, w)
      c.encode(stakingKey, w)
    }
    val ordered = bytesOf { w =>
      w.tag(258)
      w.arrayHead(2)
      c.encode(stakingKey, w)
      c.encode(otherKey, w)
    }
    val codec = setAt(version(9))
    assertEquals(codec.encodeUnchecked(readOk(codec, unordered)), ordered)
  }

  test("ratios: plain ones are tagged from version 9, bounded ones always (Encoder.hs:301-306)") {
    val half = Rational(1, 2)
    assertEquals(Wire.ratio(using version(9)).encodeUnchecked(half).hex, "d81e820102")
    assertEquals(Wire.ratio(using version(7)).encodeUnchecked(half).hex, "820102")
    assertEquals(Wire.unitInterval.encodeUnchecked(half).hex, "d81e820102")
    assertEquals(readOk(Wire.ratio(using version(9)), hex("820102")), half)
    assert(failure(Wire.ratio(using version(7)), hex("d81e820102")).kind.isInstanceOf[Kind.BadTag])
    // a bounded ratio needs its tag, and the interval
    assert(failure(Wire.unitInterval, hex("820102")).kind.isInstanceOf[Kind.TypeMismatch])
    assert(failure(Wire.unitInterval, hex("d81e820302")).kind.isInstanceOf[Kind.OutOfRange])
    assert(failure(Wire.unitInterval, hex("d81e820100")).kind.isInstanceOf[Kind.OutOfRange])
    assertEquals(readOk(Wire.nonNegativeInterval, hex("d81e820302")), Rational(3, 2))
    // numerator and denominator are normalised
    assertEquals(readOk(Wire.unitInterval, hex("d81e820204")), half)
  }

  // ---- hand-worked bytes (the instance cited with each)

  test("golden: the small records, as the Haskell instances write them") {
    val cred = "82 00 581c " + key28
    def g(s: String): String = s.filterNot(_ == ' ')
    // ChainAccountState: array(2)[treasury, reserves] (State/ChainAccount.hs:46-48)
    assertEquals(
      chainAccountState.encodeUnchecked(ChainAccountState(coinOf(5), coinOf(10))).hex,
      "82050a"
    )
    // Credential, key: [0, hash] (Credential.hs:322-325)
    assertEquals(
      credential[Staking].encodeUnchecked(Credential.KeyHashObj(keyHash28)).hex,
      g(cred)
    )
    // ConwayAccountState: [balance, deposit, null, [2]] (Conway/State/Account.hs:127-134)
    assertEquals(
      conwayAccountState
        .encodeUnchecked(
          ConwayAccountState(
            CompactCoin.fromUnsigned(1),
            CompactCoin.fromUnsigned(2),
            None,
            Some(DRep.DRepAlwaysAbstain)
          )
        )
        .hex,
      "840102f68102"
    )
    // an empty PState and VState (State/CertState.hs:253-255, Conway/State/VState.hs:101-107)
    assertEquals(pState.encodeUnchecked(LedgerStateGen.emptyPState).hex, "84a0a0a0a0")
    assertEquals(vState.encodeUnchecked(LedgerStateGen.emptyVState).hex, "83a0a000")
    // the empty pool distribution is `def`: no pools and a total of 1 (State/PoolDistr.hs:108-122)
    assertEquals(poolDistr.encodeUnchecked(PoolDistr.empty).hex, "82a001")
    // the empty snapshot: [no stake, no pools] (State/SnapShots.hs:290-296)
    assertEquals(snapShot.encodeUnchecked(SnapShot.empty).hex, "82a0a0")
    // FuturePParams, no update: [0] (State/Governance.hs:148-159)
    assertEquals(
      futurePParams(Params.conwayCodec).encodeUnchecked(FuturePParams.NoPParamsUpdate).hex,
      "8100"
    )
    // a unit interval (BaseTypes.hs:384) and a pointer (Credential.hs:353-357)
    assertEquals(Wire.unitInterval.encodeUnchecked(Rational(3, 10)).hex, "d81e82030a")
    assertEquals(ptr.encodeUnchecked(xyz.kd5ujc.scarab.ledger.Ptr(1, 2, 3)).hex, "83010203")
  }

  // LEDGERS enumerates `TxIx` with `zip [minBound ..]` (cardano-ledger f649f975
  // `Shelley/Rules/Ledgers.hs:201-211`, `BaseTypes.hs:918-921`): no pointer past 65,535 is made
  test(
    "the pointer of the last transaction position is written and read back; one past is refused"
  ) {
    val last = xyz.kd5ujc.scarab.ledger.Ptr.ofCertificate(BigInt(12), TxIx.count - 1, 0)
    assertEquals(ptr.check(last), None)
    assertEquals(ptr.decodeBytes(ptr.encodeUnchecked(last)), Right(last))
    assert(ptr.check(xyz.kd5ujc.scarab.ledger.Ptr(12, TxIx.count, 0)).isDefined)
  }

  test("golden: a DRepState with a delegator is [expiry, [], deposit, #258[credential]]") {
    val d = DRepState(
      EpochNo.fromUnsigned(7),
      None,
      CompactCoin.fromUnsigned(500),
      Sorted.set(Seq(Credential.KeyHashObj[Staking](keyHash28)))
    )
    // DRep.hs:165-198: expiry 7, a StrictMaybe anchor (empty: array(0)), deposit 500, the set
    assertEquals(
      drepState.encodeUnchecked(d).hex,
      ("84 07 80 1901f4 d90102 81 82 00 581c" + key28).filterNot(_ == ' ')
    )
    assertEquals(readOk(drepState, drepState.encodeUnchecked(d)), d)
  }

  test("golden: a reward update writes deltaR and deltaF negated (RewardUpdate.hs:125-132)") {
    val u = RewardUpdate(
      DeltaCoin(BigInt(1)),
      DeltaCoin(BigInt(-3)),
      Sorted.empty,
      DeltaCoin(BigInt(-4)),
      NonMyopic.empty
    )
    // [1, 3, {}, 4, [{}, 0]]
    assertEquals(rewardUpdate.encodeUnchecked(u).hex, "850103a00482a000")
    assertEquals(readOk(rewardUpdate, rewardUpdate.encodeUnchecked(u)), u)
    // and a positive delta is written as a negative integer
    val up = u.copy(deltaR = DeltaCoin(BigInt(3)))
    assertEquals(rewardUpdate.encodeUnchecked(up).hex, "850122a00482a000")
    assertEquals(readOk(rewardUpdate, rewardUpdate.encodeUnchecked(up)), up)
  }

  test(
    "golden: likelihood weights are single-precision floats (PoolRank.hs:88; cborg encodeFloat)"
  ) {
    val pool = KeyHash.fromBytes[StakePool](hex(key28)).fold(e => fail(e.toString), identity)
    val nm = NonMyopic(
      Sorted.map(Seq(pool -> Likelihood(Vector(LogWeight(1.0f), LogWeight(0.5f))))),
      coinOf(5)
    )
    assertEquals(
      nonMyopic.encodeUnchecked(nm).hex,
      ("82 a1 581c" + key28 + "82 fa3f800000 fa3f000000 05").filterNot(_ == ' ')
    )
    assertEquals(readOk(nonMyopic, nonMyopic.encodeUnchecked(nm)), nm)
    // 100 samples, as the node computes them: an indefinite array, as the real Preview state has
    val hundred = NonMyopic(
      Sorted.map(Seq(pool -> Likelihood(Vector.fill(100)(LogWeight(0.25f))))),
      coinOf(0)
    )
    val bytes = nonMyopic.encodeUnchecked(hundred)
    assert(bytes.hex.startsWith(("82 a1 581c" + key28 + "9f fa3e800000").filterNot(_ == ' ')))
    assertEquals(readOk(nonMyopic, bytes), hundred)
    // a half-precision float is read, a double is not (decodeFloat)
    val half = bytesOf { w =>
      w.arrayHead(1); w.float16(1.5f)
    }
    assertEquals(readOk(likelihood, half), Likelihood(Vector(LogWeight(1.5f))))
    val double = bytesOf { w =>
      w.arrayHead(1); w.float64(1.5)
    }
    assert(failure(likelihood, double).kind.isInstanceOf[Kind.Unsupported])
    // and without the option to allow them, a float does not even parse
    assert(likelihood.decodeBytes(bytes).isLeft)
  }

  private def coinOf(n: Long): Coin =
    Coin(BigInt(n))

  // ---- what the decoders refuse, with a typed error

  test(
    "a block count is a Natural read as a Word64; a delegator count an Int read as non-negative"
  ) {
    val tooMany = bytesOf { w =>
      w.mapHead(1)
      w.bytes(hex(key28))
      w.integer(BigInt(1) << 64)
    }
    // a Natural beyond a Word64 is a bignum on the wire, which the Word64 reader does not take
    assert(failure(blocksMade, tooMany).kind.isInstanceOf[Kind.TypeMismatch])
    // a count above Int.maxBound cannot be a Haskell Int, and a negative one is not a mirror word
    val pool = StateGen.snapShots.sample.get.mark.pools.headOption.map(_._2)
    val snap = pool.getOrElse(
      StakePoolSnapShot(
        CompactCoin.fromUnsigned(1),
        Rational.one,
        Sorted.emptySet[KeyHash[Staking]],
        coinOf(0),
        VRFVerKeyHash.fromBytes(Bytes(Vector.fill(32)(1.toByte))).toOption.get,
        coinOf(0),
        coinOf(0),
        Rational.zero,
        Word64.zero,
        Credential.KeyHashObj(keyHash28)
      )
    )
    val c = stakePoolSnapShot
    val bytes = c.encodeUnchecked(snap.copy(numDelegators = Word64.fromUnsigned(Long.MaxValue)))
    assertEquals(readOk(c, bytes).numDelegators, Word64.fromUnsigned(Long.MaxValue))
    assertEquals(c.check(snap.copy(numDelegators = Word64.fromUnsigned(-1L))).isDefined, true)
    val encoded = c.encodeUnchecked(snap.copy(numDelegators = Word64.zero)).hex
    val tooBig = hex(encoded.dropRight(2 + 64) + "1b8000000000000000" + encoded.takeRight(64))
    assert(read(c, tooBig).left.exists(_.kind.isInstanceOf[Kind.OutOfRange]), tooBig.hex)
  }

  test("an epoch, a slot and an amount of 2^64 or more are refused") {
    val big = bytesOf(_.integer(BigInt(1) << 64))
    assert(failure(epochNo, big).kind.isInstanceOf[Kind.TypeMismatch])
    assert(failure(compactCoin, big).kind.isInstanceOf[Kind.TypeMismatch])
    assert(failure(epochNo, bytesOf(_.int(BigInt(-1)))).kind.isInstanceOf[Kind.TypeMismatch])
  }

  test("an alternative with a tag or arity the sum does not have is a typed error") {
    assert(failure(drep, hex("820500")).kind.isInstanceOf[Kind.LiteralMismatch])
    assert(
      failure(credential[Staking], hex("8202581c" + key28)).kind.isInstanceOf[Kind.LiteralMismatch]
    )
    assert(
      failure(futurePParams(Params.conwayCodec), hex("8105")).kind
        .isInstanceOf[Kind.LiteralMismatch]
    )
    assert(failure(rewardType, hex("02")).kind.isInstanceOf[Kind.OutOfRange])
    assert(failure(vote, hex("03")).kind.isInstanceOf[Kind.OutOfRange])
  }

  test("a typed mid-pulse reward update round trips and refuses invalid pulse sizes") {
    val state = StateGen.rewardUpdate.sample.getOrElse(fail("no update"))
    val written = pulsingRewUpdate.encodeUnchecked(state)
    assertEquals(readOk(pulsingRewUpdate, written), state)
    assert(rewardPulseSize.decodeBytes(hex("00")).isLeft)
    assert(rewardPulseSize.decodeBytes(hex("20")).isLeft)
    assert(rewardPulser.decodeBytes(hex("9f01a0ff")).isLeft)
  }

  test(
    "UTxO entries in a state file are refused (they are in the tables file); the handle writes map(0)"
  ) {
    assertEquals(
      utxoTables.encodeUnchecked(UtxoTables(xyz.kd5ujc.scarab.ledger.Utxo.empty)).hex,
      "a0"
    )
    val entries = bytesOf { w =>
      w.mapHead(1)
      w.uint(1L)
      w.uint(2L)
    }
    assert(failure(utxoTables, entries).kind.isInstanceOf[Kind.Unsupported])
    // the Shelley era's stashed AVVM addresses are a UTxO, the later eras' a null
    val unitBytes = bytesOf(_.nul())
    assertEquals(readOk(stashedAvvm[Allegra](using ShelleyBasedEra.SAllegra), unitBytes), ())
    assert(
      failure(stashedAvvm[Allegra](using ShelleyBasedEra.SAllegra), hex("a0")).kind
        .isInstanceOf[Kind.TypeMismatch]
    )
  }

  test(
    "the parameters of an era are an array of the size of its list, and decode under the right era only"
  ) {
    def size(c: Codec[xyz.kd5ujc.scarab.ledger.ProtocolParams]): Int =
      c.encodeUnchecked(Params.empty).value.head & 0x1f
    assertEquals(size(Params.codec(ShelleyBasedEra.SShelley)), 17)
    assertEquals(size(Params.codec(ShelleyBasedEra.SAllegra)), 17)
    assertEquals(size(Params.codec(ShelleyBasedEra.SMary)), 17)
    assertEquals(size(Params.codec(ShelleyBasedEra.SAlonzo)), 24)
    assertEquals(size(Params.codec(ShelleyBasedEra.SBabbage)), 22)
    // 31 does not fit in the five bits of a short head: 0x98 0x1f
    assertEquals(Params.conwayCodec.encodeUnchecked(Params.empty).hex.take(4), "981f")
    val conway = Params.conwayCodec.encodeUnchecked(Params.empty)
    assert(failure(Params.codec(ShelleyBasedEra.SBabbage), conway).kind.isInstanceOf[Kind.BadArity])
  }

  test("a parameter out of the range of its Haskell type is refused, with its name in the path") {
    val ok = Params.conwayCodec.encodeUnchecked(Params.empty)
    // the maximum header size is a Word16 (the fifth parameter)
    val params = Params.empty.copy(maxBlockHeaderSize = BigInt(70000))
    assert(Params.conwayCodec.check(params).exists(_.path == List("maxBHSize")))
    val bytes = Params.conwayCodec.encodeUnchecked(params)
    val e = failure(Params.conwayCodec, bytes)
    assert(e.kind.isInstanceOf[Kind.OutOfRange], e.render)
    assertEquals(e.path, List("maxBHSize"))
    assert(Params.conwayCodec.decodeBytes(ok).isRight)
  }

  test("cost models: languages are a Word8, parameters an Int64, and a language may not repeat") {
    val c = Params.codec(ShelleyBasedEra.SAlonzo)
    val withModel = Params.empty.copy(costModels = Map(1 -> Vector(BigInt(-1), BigInt(2))))
    assertEquals(readOk(c, c.encodeUnchecked(withModel)), withModel)
    assert(c.check(Params.empty.copy(costModels = Map(256 -> Vector.empty))).isDefined)
    assert(c.check(Params.empty.copy(costModels = Map(1 -> Vector(BigInt(1) << 64)))).isDefined)
  }

  test("an Alonzo update naming key 17 is the lovelace per word, and round-trips as it came") {
    // `Alonzo/PParams.hs:664`: key 17 of the update is `coinsPerUTxOWord`; from Babbage it is
    // `coinsPerUTxOByte`. The update is kept as written, so that either reading restores it.
    val genesis = KeyHash.fromBytes[Genesis](hex(key28)).fold(e => fail(e.toString), identity)
    val update = xyz.kd5ujc.scarab.ledger.ParamUpdate(Map(14 -> hex("820700"), 17 -> hex("199c40")))
    val params = Params.empty.copy(coinsPerUTxOWord = 34482, protocolMajor = 6)
    val future = params.copy(protocolMajor = 7)
    val state = ShelleyGovState(
      Sorted.map(Seq(genesis -> update)),
      Sorted.map(Seq.empty[(KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.ParamUpdate)]),
      params,
      params,
      FuturePParams.PotentialPParamsUpdate(Some(future))
    )
    val codec = shelleyGovState(Params.codec(ShelleyBasedEra.SAlonzo))(using version(6))
    val bytes = codec.encodeUnchecked(state)
    assertEquals(readOk(codec, bytes), state)
    assert(bytes.hex.contains("a20e82070011199c40"), bytes.hex)
    val alonzo = Params.codec(ShelleyBasedEra.SAlonzo)
    assertEquals(readOk(alonzo, alonzo.encodeUnchecked(future)), future)
  }

  test("a stored Conway parameter change reads to its typed update and writes back as it came") {
    // The writer before the typed update wrote the same map: each key once, in key order, and each
    // value in its original encoding (here 44 with a 4-byte head and 2/4 not in lowest terms).
    val stored = hex("8400 f6 a2 00 1a0000002c 09 d81e820204 f6")
    val codec = govAction(using version(10))
    val action = readOk(codec, stored)
    action match
      case xyz.kd5ujc.scarab.ledger.GovAction.ParameterChange(None, update, None) =>
        assertEquals(update.keys, Set(0, 9))
        assertEquals(
          update.collectFirst { case xyz.kd5ujc.scarab.ledger.ConwayParam.A0(r) => r },
          Some(Rational(1, 2))
        )
      case other => fail(s"not a parameter change: $other")
    assertEquals(codec.encodeUnchecked(action), stored)
  }

  test("a stored Conway parameter change with a key that is not a Conway field is refused") {
    // A state written before the update was typed could hold key 12 (d); the reference cannot
    // represent it, so reading such a state fails with the ledger's typed error.
    val stored = hex("8400 f6 a1 0c d81e820102 f6")
    read(govAction(using version(10)), stored).map(_ => ()).leftMap(_.kind) match
      case Left(Kind.Domain(xyz.kd5ujc.scarab.ledger.LedgerDecodeError.UnknownKey(_, key))) =>
        assertEquals(key, BigInt(12))
      case other => fail(s"expected the unknown key 12: $other")
  }

  test("a reward set cannot hold two rewards of one type and pool, as the reference's cannot") {
    val pool = KeyHash.fromBytes[StakePool](hex(key28)).fold(e => fail(e.toString), identity)
    val r1 = Reward(RewardType.MemberReward, pool, coinOf(1))
    val r2 = Reward(RewardType.MemberReward, pool, coinOf(2))
    val twice = bytesOf { w =>
      w.tag(258)
      w.arrayHead(2)
      reward.encode(r1, w)
      reward.encode(r2, w)
    }
    assert(failure(rewardSet, twice).kind.isInstanceOf[Kind.DuplicateElement])
    assertEquals(
      readOk(
        rewardSet(using version(7)),
        bytesOf { w =>
          w.arrayHead(2)
          reward.encode(r1, w)
          reward.encode(r2, w)
        }
      ).rewards.size,
      1
    )
  }

  test(
    "a state with trailing bytes, or truncated, is a syntax error; a wrong era is an arity error"
  ) {
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val state = StateGen.newEpochState[Conway].sample.get.copy[Conway](ru = None)
    val bytes = HaskellState.encode(state).toOption.get
    val trailing = Bytes(bytes.value :+ 0.toByte)
    assert(HaskellState.decode[Conway](trailing).left.exists(_.kind.isInstanceOf[Kind.Syntax]))
    val truncated = Bytes(bytes.value.dropRight(1))
    assert(HaskellState.decode[Conway](truncated).left.exists(_.kind.isInstanceOf[Kind.Syntax]))
    val wrong = HaskellState.decode[Babbage](bytes)(using ShelleyBasedEra.SBabbage)
    assert(wrong.isLeft)
    // the first thing wrong is located: the ledger state's certificate state has three fields
    assert(wrong.left.exists(_.path.nonEmpty), wrong.toString)
    assertEquals(HaskellState.decode[Conway](bytes), Right(state))
  }

  test("every era writes its state at its own lowest version (eraProtVerLow)") {
    val eras = List(
      (ShelleyBasedEra.SShelley, 2L),
      (ShelleyBasedEra.SAllegra, 3L),
      (ShelleyBasedEra.SMary, 4L),
      (ShelleyBasedEra.SAlonzo, 5L),
      (ShelleyBasedEra.SBabbage, 7L),
      (ShelleyBasedEra.SConway, 9L)
    )
    eras.foreach((era, major) => assertEquals(Versions.eraLow(era).major.toBigInt, BigInt(major)))
  }

  // ---- the types, in every era

  laws("StakePoolState", StateGen.stakePoolState, stakePoolState)
  laws("PState", StateGen.pState, pState)
  laws(
    "DState[ShelleyAccounts]",
    LedgerStateGen.dState(arb[ShelleyAccounts]),
    dState[ShelleyAccounts]
  )
  laws("DState[ConwayAccounts]", LedgerStateGen.dState(arb[ConwayAccounts]), dState[ConwayAccounts])
  laws("VState", arb[VState], vState)
  laws("ShelleyCertState", StateGen.certState[Babbage], shelleyCertState)
  laws("ConwayCertState", StateGen.certState[Conway], conwayCertState)
  laws("ShelleyInstantStake", arb[ShelleyInstantStake], shelleyInstantStake)
  laws("ConwayInstantStake", arb[ConwayInstantStake], conwayInstantStake)
  laws("SnapShots", StateGen.snapShots, snapShots)
  laws("PoolDistr", arb[PoolDistr], poolDistr)
  laws("NonMyopic", arb[NonMyopic], nonMyopic)
  laws("RewardUpdate", arb[RewardUpdate], rewardUpdate)
  laws("RewardSnapShot", arb[RewardSnapShot], rewardSnapShot)
  laws("RewardPulser", StateGen.rewardPulser, rewardPulser)
  laws("RewardFreeVars", StateGen.rewardFreeVars, rewardFreeVars)
  laws("PoolRewardInfo", StateGen.poolRewardInfo, poolRewardInfo)
  laws("RewardAns", arb[RewardAns], rewardAns)
  laws("BlocksMade", arb[BlocksMade], blocksMade)
  laws("ConwayGovState", StateGen.conwayGov, conwayGovState)
  laws("Proposals", StateGen.proposals, proposals)
  laws("PulsingSnapshot", StateGen.pulsingSnapshot, pulsingSnapshot)
  laws("RatifyState", StateGen.ratifyState, ratifyState)
  laws("PoolParams", StateGen.poolParams, poolParams)

  /** The two laws for the state of an era, at the version the reference writes it at. */
  private def era[E <: EraTag](name: String)(using ShelleyBasedEra[E]): Unit =
    val codec = HaskellStateCodecs.newEpochState[E]
    laws(s"ProtocolParams of $name", StateGen.paramsOf[E], Params.codec(summon[ShelleyBasedEra[E]]))
    laws(
      s"LedgerState[$name]",
      StateGen.ledgerState[E], {
        given ProtVer = Versions.eraLow(summon[ShelleyBasedEra[E]])
        HaskellStateCodecs.ledgerState[E]
      }
    )
    laws(s"NewEpochState[$name]", StateGen.newEpochState[E], codec)

  era[Shelley]("Shelley")
  era[Allegra]("Allegra")
  era[Mary]("Mary")
  era[Alonzo]("Alonzo")
  era[Babbage]("Babbage")
  era[Conway]("Conway")
