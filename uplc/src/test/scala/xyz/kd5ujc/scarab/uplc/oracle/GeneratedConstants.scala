// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import cats.data.State
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, Text, Value}
import xyz.kd5ujc.scarab.uplc.oracle.SplitRng.*

/** The constants of the term generator: values near the bounds the builtins and the costing care
  * about (small and word-sized integers, the edges of 64 bits, byte strings around 32 and 64 bytes,
  * `Data` of every shape and depth with chunked byte strings and big integers), and the compressed
  * encodings of BLS12-381 points, valid and not, for `uncompress`, since a BLS constant has no Flat
  * encoding.
  */
object GeneratedConstants:
  private def hex(s: String): Bytes =
    Bytes.fromHex(s).fold(e => throw IllegalArgumentException(e.message), identity)

  /** Compressed G1 points: the generator, the point at infinity, points of the upstream corpus (not
    * all of them on the curve or in the group), and a byte off.
    */
  val g1Encodings: Vector[Bytes] = Vector(
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb",
    "c0" + "00" * 47,
    "21e9a0c68985059bd25a5ef05b351ca22f7d7c19e37928583ae12a1f4939440ff754cfd85b23df4a54f66c7089db6deb",
    "8056aaf9b83cb4b3355ef95703832a03bcb9a6007bb1264908d4f27bc497aff4cacaed01c3e14c68914fb35327713bc5",
    "80a55cb41aa37175fd8ca54ba9ce6e0da6ad508827ef603756d331370bcdfb03c0b1e9e9d6af279283dd28cdce583bc9",
    "818ee62968e5c9ac5948f6a75fdf0a403b0abf47cffce962cc6350c35943c9127674ddfc35efa9898a6866546c554a95",
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bc"
  ).map(hex)

  /** Compressed G2 points, as for G1. */
  val g2Encodings: Vector[Bytes] = Vector(
    "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e" +
      "024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8",
    "c0" + "00" * 95,
    "28138ebea766d4d1aa64dd3b5826244c32ea3fe9351f9c8d584203716dae151d14bb5d06e245c24877955c79287682ba" +
      "082d077bbb2afdb1ad1d48d18e2f0c56b001bce207801adfa9fd451fc59d56f0433b02f921ba5a272c58c06536291d07",
    "40" + "00" * 95,
    "80bd6902a9d41c72c2d5e7a0b12e7a836551264e87aa0a4c21b8763461644f919345573e30401eee41364a29be7cb4b3" +
      "109b6590e9a1e0960cf64b7e8be8d71fbb7c27a45cc5b11c3435c6e6e30fc12ac4b4e644e668b6f90fb21c0a2d1842f2"
  ).map(hex)

  val integer: Draw[BigInt] = pick(
    Vector(
      8 -> between(-3, 20).map(BigInt(_)),
      3 -> between(0, 255).map(BigInt(_)),
      2 -> long.map(BigInt(_)),
      2 -> element(
        Vector(
          BigInt(Long.MaxValue),
          BigInt(Long.MinValue),
          (BigInt(1) << 64) - 1,
          BigInt(1) << 64,
          -(BigInt(1) << 64),
          BigInt(256),
          BigInt(-1),
          BigInt(8192),
          BigInt(8193),
          BigInt(Int.MaxValue),
          (BigInt(1) << 31)
        )
      ).map(_.getOrElse(BigInt(0))),
      1 -> (between(65, 520), chance(50)).mapN((bits, negative) =>
        val n = (BigInt(1) << bits) - 1
        if negative then -n else n
      )
    )
  )

  private def bytesOf(n: Int): Draw[Bytes] =
    listOf(n, long).map(ls => Bytes.wrap(ls.map(_.toByte).toArray))

  val byteString: Draw[Bytes] = pick(
    Vector(
      6 -> between(0, 16).flatMap(bytesOf),
      3 -> element(Vector(28, 31, 32, 33, 63, 64, 65)).map(_.getOrElse(32)).flatMap(bytesOf),
      1 -> element(g1Encodings ++ g2Encodings).map(_.getOrElse(Bytes.empty)),
      1 -> between(66, 160).flatMap(bytesOf),
      1 -> (oneOf(0x00, 0xff, 0x80), between(1, 40)).mapN((b, n) =>
        Bytes.wrap(Array.fill(n)(b.toByte))
      )
    )
  )

  val text: Draw[Text] =
    val codePoint = pick(
      Vector(
        8 -> between('a'.toInt, 'z'.toInt),
        2 -> between('0'.toInt, '9'.toInt),
        1 -> oneOf(0x0, 0xe9, 0x4e2d, 0x1f600, 0x10ffff, 0x7f)
      )
    )
    between(0, 12)
      .flatMap(listOf(_, codePoint))
      .map(cps => Text.fromCodePoints(cps).getOrElse(Text.fromString("")))

  /** `Data` of at most `depth` levels, of which one in nine is a chain of 20 to 150 lists. With
    * `wide`, a constructor's index may be outside `[0, 2^64)`, which `serialiseData` writes and the
    * reference's decoder refuses (`decodeData`, `PlutusCore/Data.hs`): such a value is a constant
    * the Flat decoder refuses, never an argument of the ledger.
    */
  def data(depth: Int, wide: Boolean = true): Draw[PlutusData] =
    val leaf: Draw[PlutusData] = pick(
      Vector(
        3 -> integer.map(PlutusData.I(_)),
        3 -> byteString.map(PlutusData.B(_))
      )
    )
    if depth <= 0 then leaf
    else
      val inner = data(depth - 1, wide)
      val fields = between(0, 3).flatMap(listOf(_, inner))
      pick(
        Vector(
          3 -> leaf,
          2 -> (
            pick(
              Vector(
                4 -> between(0, 6).map(BigInt(_)),
                1 -> between(7, 130).map(BigInt(_)),
                1 -> (if wide then
                        oneOf(BigInt(1) << 64, BigInt(-1), BigInt(1000000), (BigInt(1) << 64) - 1)
                      else oneOf(BigInt(1000000), (BigInt(1) << 64) - 1))
              )
            ),
            fields
          ).mapN(PlutusData.Constr(_, _)),
          1 -> between(0, 3)
            .flatMap(listOf(_, (inner, inner).tupled))
            .map(kvs => PlutusData.Map(kvs)),
          2 -> fields.map(PlutusData.List(_)),
          1 -> between(20, 150).map(n =>
            (1 to n).foldLeft(PlutusData.I(n): PlutusData)((d, _) => PlutusData.List(Vector(d)))
          )
        )
      )

  val value: Draw[Value] =
    def key(maxLength: Int): Draw[Value.Key] = pick(
      Vector(
        3 -> between(0, 4),
        1 -> State.pure[SplitRng, Int](28),
        1 -> State.pure[SplitRng, Int](32),
        1 -> between(0, maxLength)
      )
    ).flatMap(bytesOf)
      .map(b => Value.Key(b).getOrElse(throw IllegalStateException("key")))
    val quantity = pick(
      Vector(
        4 -> between(-5, 100).map(BigInt(_)),
        1 -> long.map(BigInt(_)),
        1 -> oneOf((BigInt(1) << 127) - 1, -(BigInt(1) << 127))
      )
    ).map(q => Value.Quantity(q).getOrElse(throw IllegalStateException("quantity")))
    val tokens = between(1, 3).flatMap(listOf(_, (key(32), quantity).tupled))
    between(0, 3)
      .flatMap(listOf(_, (key(32), tokens).tupled))
      .map(entries => Value.pack(SortedMap.from(entries.map((c, ts) => c -> SortedMap.from(ts)))))

  /** A constant of `uni`, which holds no BLS12-381 value. */
  def of(uni: DefaultUni): Draw[Constant] = uni match
    case DefaultUni.Integer    => integer.map(Constant.Integer(_))
    case DefaultUni.ByteString => byteString.map(Constant.ByteString(_))
    case DefaultUni.String     => text.map(Constant.String(_))
    case DefaultUni.Unit       => State.pure(Constant.Unit)
    case DefaultUni.Bool       => chance(50).map(Constant.Bool(_))
    case DefaultUni.Data       => between(0, 3).flatMap(data(_)).map(Constant.Data(_))
    case DefaultUni.Value      => value.map(Constant.Value(_))
    case DefaultUni.List(e) =>
      between(0, 4)
        .flatMap(listOf(_, of(e)))
        .map(xs => Constant.List.of(e, xs).getOrElse(Constant.List.empty(e)))
    case DefaultUni.Array(e) =>
      between(0, 4)
        .flatMap(listOf(_, of(e)))
        .map(xs => Constant.Array.of(e, xs).getOrElse(Constant.List.empty(e).toArray))
    case DefaultUni.Pair(a, b) => (of(a), of(b)).mapN(Constant.Pair(_, _))
    case other                 => throw IllegalArgumentException(s"no Flat constant of $other")

  /** Whether a type holds a BLS12-381 value, which has no Flat encoding. */
  def hasBls(uni: DefaultUni): Boolean = uni match
    case DefaultUni.Bls12_381_G1_Element | DefaultUni.Bls12_381_G2_Element |
        DefaultUni.Bls12_381_MlResult =>
      true
    case DefaultUni.List(e)    => hasBls(e)
    case DefaultUni.Array(e)   => hasBls(e)
    case DefaultUni.Pair(a, b) => hasBls(a) || hasBls(b)
    case _                     => false
