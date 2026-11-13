// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import org.scalacheck.Gen
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*

/** Small programs of every term and constant the Flat decoder reads, for round trips through
  * [[FlatWriter]].
  */
object FlatGen:
  private val bytes: Gen[Bytes] =
    Gen
      .frequency(
        5 -> Gen.choose(0, 40),
        1 -> Gen.oneOf(254, 255, 256, 600)
      )
      .flatMap(n =>
        Gen.listOfN(n, Gen.choose(0, 255).map(_.toByte)).map(xs => Bytes.wrap(xs.toArray))
      )

  private val integers: Gen[BigInt] = Gen.oneOf(
    Gen.long.map(BigInt(_)),
    Gen.choose(-3, 3).map(BigInt(_)),
    Gen.choose(60, 300).flatMap(bits => Gen.oneOf(BigInt(1) << bits, -(BigInt(1) << bits) - 1))
  )

  private val texts: Gen[Text] =
    Gen
      .listOf(Gen.oneOf(Gen.alphaNumChar.map(_.toInt), Gen.oneOf(0xe9, 0x4e2d, 0x1f600, 0)))
      .map(cps =>
        Text.fromCodePoints(cps).fold(e => throw IllegalStateException(e.toString), identity)
      )

  private def data(depth: Int): Gen[PlutusData] =
    val leaves = Gen.oneOf(
      integers.map(PlutusData.I(_)),
      Gen
        .choose(0, 64)
        .flatMap(n => Gen.listOfN(n, Gen.choose(0, 255).map(_.toByte)))
        .map(xs => PlutusData.B(Bytes.wrap(xs.toArray)))
    )
    if depth <= 0 then leaves
    else
      val inner = data(depth - 1)
      Gen.oneOf(
        leaves,
        Gen
          .zip(
            Gen.oneOf(Gen.choose(0L, 6L), Gen.choose(7L, 127L), Gen.choose(128L, 100000L)),
            Gen.listOfN(2, inner)
          )
          .map((i, fs) => PlutusData.Constr(i, fs.toVector)),
        Gen.listOfN(2, Gen.zip(inner, inner)).map(kvs => PlutusData.Map(kvs.toVector)),
        Gen.listOfN(3, inner).map(xs => PlutusData.List(xs.toVector))
      )

  private val values: Gen[Value] =
    val key = Gen
      .choose(0, 32)
      .flatMap(n => Gen.listOfN(n, Gen.choose(0, 255).map(_.toByte)))
      .map(xs => Value.Key(Bytes.wrap(xs.toArray)).getOrElse(throw IllegalStateException("key")))
    val amount = Gen
      .oneOf(Gen.choose(1L, 1000L), Gen.choose(-1000L, -1L))
      .map(BigInt(_))
      .map(a => Value.Quantity(a).getOrElse(throw IllegalStateException("amount")))
    Gen
      .listOfN(3, Gen.zip(key, Gen.nonEmptyListOf(Gen.zip(key, amount))))
      .map(entries => Value.pack(SortedMap.from(entries.map((c, ts) => c -> SortedMap.from(ts)))))

  private def types(depth: Int): Gen[DefaultUni] =
    val leaves = Gen.oneOf(
      DefaultUni.Integer,
      DefaultUni.ByteString,
      DefaultUni.String,
      DefaultUni.Unit,
      DefaultUni.Bool,
      DefaultUni.Data,
      DefaultUni.Value
    )
    if depth <= 0 then leaves
    else
      Gen.oneOf(
        leaves,
        types(depth - 1).map(DefaultUni.List(_)),
        types(depth - 1).map(DefaultUni.Array(_)),
        Gen.zip(types(depth - 1), types(depth - 1)).map(DefaultUni.Pair(_, _))
      )

  def constantOf(uni: DefaultUni): Gen[Constant] = uni match
    case DefaultUni.Integer    => integers.map(Constant.Integer(_))
    case DefaultUni.ByteString => bytes.map(Constant.ByteString(_))
    case DefaultUni.String     => texts.map(Constant.String(_))
    case DefaultUni.Unit       => Gen.const(Constant.Unit)
    case DefaultUni.Bool       => Gen.oneOf(true, false).map(Constant.Bool(_))
    case DefaultUni.Data       => data(2).map(Constant.Data(_))
    case DefaultUni.Value      => values.map(Constant.Value(_))
    case DefaultUni.List(e) =>
      Gen
        .choose(0, 3)
        .flatMap(Gen.listOfN(_, constantOf(e)))
        .map(xs =>
          Constant.List
            .of(e, xs.toVector)
            .fold(m => throw IllegalStateException(m.toString), identity)
        )
    case DefaultUni.Array(e) =>
      Gen
        .choose(0, 3)
        .flatMap(Gen.listOfN(_, constantOf(e)))
        .map(xs =>
          Constant.Array
            .of(e, xs.toVector)
            .fold(m => throw IllegalStateException(m.toString), identity)
        )
    case DefaultUni.Pair(a, b) => Gen.zip(constantOf(a), constantOf(b)).map(Constant.Pair(_, _))
    case other                 => throw IllegalArgumentException(s"no generator for $other")

  val constants: Gen[Constant] = types(2).flatMap(constantOf)

  private val words: Gen[Word64] =
    Gen.oneOf(Gen.choose(0L, 300L), Gen.long).map(Word64.fromBits)

  def terms(depth: Int): Gen[Term] =
    val leaves = Gen.oneOf(
      words.map(w => Term.variable(DeBruijn(w))),
      constants.map(Term.constant),
      Gen.oneOf(DefaultFun.values.toSeq).map(Term.builtin),
      Gen.const(Term.error)
    )
    if depth <= 0 then leaves
    else
      val sub = terms(depth - 1)
      Gen.frequency(
        2 -> leaves,
        1 -> sub.map(Term.lam),
        1 -> sub.map(Term.delay),
        1 -> sub.map(Term.force),
        2 -> Gen.zip(sub, sub).map(Term.app),
        1 -> Gen
          .zip(words, Gen.choose(0, 3).flatMap(Gen.listOfN(_, sub)))
          .map((t, fs) => Term.constr(t, fs.toVector)),
        1 -> Gen
          .zip(sub, Gen.choose(0, 3).flatMap(Gen.listOfN(_, sub)))
          .map((s, bs) => Term.caseOf(s, bs.toVector))
      )

  /** Programs of version 1.1.0, which may hold `constr` and `case`. */
  val programs: Gen[Program] = terms(4).map(Program(ProgramVersion.V1_1_0, _))
