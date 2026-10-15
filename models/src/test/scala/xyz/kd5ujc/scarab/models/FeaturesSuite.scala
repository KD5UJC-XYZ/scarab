// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import scala.util.Random
import xyz.kd5ujc.scarab.models.features.*
import xyz.kd5ujc.scarab.models.gen.{CddlSampler, Generator, QName}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}
import DecodeError.Kind

/** The generator features that the two bounded targets do not need, on `features.cddl`: integer-
  * and text-keyed maps with optional entries, tag-258 sets, group choices, sockets, embedded CBOR,
  * nested multi-assets and recursion.
  */
class FeaturesSuite extends munit.FunSuite:
  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e), identity)
  private val h32 = "58 20" + "11" * 32
  private val h28 = "58 1c" + "22" * 28

  // Sampled (semantic round trip: sets may arrive untagged and are written tagged) ---------------------------

  private val cddlDir = Path.of("models/src/main/cddl")
  private val schema =
    Generator.configFrom(cddlDir).flatMap(Generator.load(cddlDir, _)).toOption.get
  private val sampler = new CddlSampler(schema)

  private def semantic[A](rule: String, codec: Codec[A]): Unit =
    test(
      s"features:$rule: 400 random items decode, re-encode canonically and decode to the same value"
    ) {
      val rnd = new Random(rule.hashCode.toLong)
      (0 until 400).foreach { _ =>
        val bytes = Bytes.fromArray(sampler.sample(QName("features", rule), rnd))
        val value =
          codec.decodeBytes(bytes).fold(e => fail(s"${e.render} for ${bytes.hex}"), identity)
        val again = codec.encodeBytes(value)
        assertEquals(codec.decodeBytes(again), Right(value))
        // Canonical encoding is a fixed point.
        assertEquals(codec.encodeBytes(codec.decodeBytes(again).toOption.get), again)
      }
    }

  semantic("transaction", Transaction)
  semantic("auxiliary_data", AuxiliaryData)
  semantic("person", Person)
  semantic("key_descriptor", KeyDescriptor)
  semantic("certificate", Certificate)
  semantic("value", Value)
  semantic("prelude_use", PreludeUse)

  test(
    "RFC 8610 prelude names (bigint, encoded-cbor, tdate, unsigned, integer) resolve and round trip"
  ) {
    // Regression for a gap the cuddle cross-check found: the Byron CDDL uses `bigint` and `encoded-cbor`
    // without defining them. [#6.2(h'01'), #6.24(h'02'), #6.0("2020"), 5, #6.3(h'ff')]
    val bytes = hex("85 c2 41 01  d8 18 41 02  c0 64 32303230  05  c3 41 ff")
    val value = PreludeUse.decodeBytes(bytes).fold(e => fail(e.render), identity)
    assertEquals(value.amount, PreludeUseAmount.Tag2(hex("01")))
    assertEquals(value.embedded, hex("02"))
    assertEquals(value.when, "2020")
    assertEquals(value.total, PreludeUseTotal.Tag3(hex("ff")))
    assertEquals(PreludeUse.encodeBytes(value), bytes)
  }

  // Integer-keyed maps, preserved bytes ----------------------------------------------------------------------

  // {0: 258([[h32, 0]]), 1: 9f ff (indefinite empty outputs), 2: 5}
  private val oddBody = hex(s"a3 00 d9 0102 81 82 $h32 00  01 9f ff  02 05")

  test(
    "a map with integer keys: required and optional entries; the body keeps its original bytes"
  ) {
    val body = TransactionBody.decodeBytes(oddBody).fold(e => fail(e.render), identity)
    assertEquals(body.inputs.size, 1)
    assertEquals(body.outputs, Vector.empty)
    assertEquals(body.fee.value, BigInt(5))
    assertEquals(body.ttl, None)
    // The original encoding (indefinite array, tagged set) is what a transaction id is hashed from.
    assertEquals(body.raw, oddBody)
    assertEquals(TransactionBody.encodeBytes(body), oddBody)
    // Canonical encoding of the same members differs: definite empty array.
    val canonical = TransactionBody.canonical(
      body.inputs,
      body.outputs,
      body.fee,
      body.ttl,
      body.certificates,
      body.auxiliaryDataHash,
      body.mint,
      body.donation
    )
    assertNotEquals(canonical.raw, oddBody)
    assertEquals(TransactionBody.decodeBytes(canonical.raw).map(identity), Right(body))
  }

  test(
    "map errors are typed: missing, duplicate and unknown keys, with the key in the path or kind"
  ) {
    val missing = TransactionBody.decodeBytes(hex("a1 02 05")).left.toOption.get
    assertEquals(missing.kind, Kind.MissingKey("0"))
    val duplicate = TransactionBody.decodeBytes(hex("a4 02 05 02 06 00 80 01 80")).left.toOption.get
    assert(duplicate.kind.isInstanceOf[Kind.DuplicateKey], duplicate.render)
    val unknown =
      TransactionBody.decodeBytes(hex(s"a4 00 80 01 80 02 05 18 63 00")).left.toOption.get
    assert(unknown.kind.isInstanceOf[Kind.UnexpectedKey], unknown.render)
    val wrongType = TransactionBody.decodeBytes(hex("a3 00 80 01 80 02 61 78")).left.toOption.get
    assertEquals(wrongType.path, List("2"))
    assert(wrongType.kind.isInstanceOf[Kind.TypeMismatch])
    assertEquals(wrongType.offset, 6)
  }

  test("a map with text keys, optional members and a recursive, nullable member") {
    // {"name": "a", "active": true, "parent": {"name": "b", "active": false, "age": 7}}
    val p = Person.decodeBytes(
      hex(
        "a3 64 6e616d65 61 61  66 616374697665 f5  66 706172656e74 a3 64 6e616d65 61 62 66 616374697665 f4 63 616765 07"
      )
    )
    val person = p.fold(e => fail(e.render), identity)
    assertEquals(person.name, "a")
    assertEquals(person.parent.flatten.map(_.age), Some(Some(7)))
    // "parent": null is present-and-null, which is different from absent.
    val withNull = Person
      .decodeBytes(hex("a3 64 6e616d65 61 61  66 616374697665 f5  66 706172656e74 f6"))
      .toOption
      .get
    assertEquals(withNull.parent, Some(None))
    assertEquals(Person.decodeBytes(Person.encodeBytes(withNull)), Right(withNull))
    // 300 does not fit `uint .size 1`.
    val big = Person
      .decodeBytes(hex("a3 64 6e616d65 61 61 66 616374697665 f5 63 616765 19 012c"))
      .left
      .toOption
      .get
    assert(big.kind.isInstanceOf[Kind.OutOfRange], big.render)
    assertEquals(big.path, List("\"age\""))
  }

  // Choices, groups, sockets, ranges --------------------------------------------------------------------------

  test("auxiliary data: the three serialisation styles decode to three cases") {
    assert(
      AuxiliaryData
        .decodeBytes(hex("a1 01 61 78"))
        .toOption
        .get
        .isInstanceOf[AuxiliaryData.MetadataCase]
    )
    assert(
      AuxiliaryData
        .decodeBytes(hex("82 a0 80"))
        .toOption
        .get
        .isInstanceOf[AuxiliaryData.AuxiliaryDataArray]
    )
    assert(
      AuxiliaryData
        .decodeBytes(hex("d9 0103 a0"))
        .toOption
        .get
        .isInstanceOf[AuxiliaryData.AuxiliaryDataMapCase]
    )
    val bad = AuxiliaryData.decodeBytes(hex("f5")).left.toOption.get
    val tried = bad.kind match
      case Kind.NoAlternative(cs) => cs.size
      case _                      => -1
    assertEquals(tried, 3)
  }

  test("a choice of groups: certificates splice their members into the array") {
    val delegation = Certificate.decodeBytes(hex(s"83 02 82 00 $h28 $h28"))
    // The group `stake_delegation = (2, stake_credential, pool_keyhash)` makes a 3-element array.
    assert(
      delegation.toOption.exists(_.isInstanceOf[Certificate.StakeDelegationCase]),
      delegation.toString
    )
    val registration = Certificate.decodeBytes(hex(s"82 00 82 01 $h28")).toOption.get
    assert(registration.isInstanceOf[Certificate.StakeRegistrationCase])
    assert(Certificate.decodeBytes(hex(s"82 03 82 01 $h28")).isLeft)
  }

  test(
    "sockets and literal choices become a checked opaque Int; ranges become checked opaque types"
  ) {
    assert(KeyDescriptor.decodeBytes(hex(s"82 02 58 20 ${"00" * 32}")).isRight)
    val err = KeyDescriptor.decodeBytes(hex(s"82 03 58 20 ${"00" * 32}")).left.toOption.get
    assertEquals(err.path, List("$crypto_alg"))
    assert(CryptoAlg.from(2).isRight && CryptoAlg.from(3).isLeft)
    assert(RedeemerTag.from(5).isRight && RedeemerTag.from(6).isLeft)
    assert(PositiveCoin.from(BigInt(0)).isLeft && PositiveCoin.from(BigInt(1)).isRight)
    assert(PolicyId.from(Bytes.fromArray(new Array[Byte](27))).isLeft)
  }

  test("nested multi-assets keep wire order and duplicates are not silently merged") {
    val value = Value
      .decodeBytes(hex(s"82 05 a1 58 1c ${"aa" * 28} a2 41 01 01 41 01 02"))
      .fold(e => fail(e.render), identity)
    value match
      case Value.WithAssets(coin, assets) =>
        assertEquals(coin.value, BigInt(5))
        assertEquals(assets.head._2.size, 2)
      case other => fail(s"unexpected $other")
  }
