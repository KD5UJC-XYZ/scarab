// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, PlutusData, SmallStack}

import CoreTesting.hexBytes

/** `serialiseData` (`encodeData`, `PlutusCore/Data.hs:146-198`, plutus 1.70.0.0 `507cab8c`) against
  * the bytes the reference wrote (`fixtures/reference/plutus-serialise-data`) and accepted
  * (`fixtures/reference/plutus-data-chunks`), and on data nested deeper than a thread stack.
  */
final class SerialiseDataSuite extends munit.FunSuite:
  import PlutusData.{B, Constr, I, List as DList, Map as DMap}

  private def hex(d: PlutusData): String = DataSerialisation.serialise(d).hex

  private def rows(path: String): Vector[Vector[String]] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filterNot(_.startsWith("#"))
      .map(_.split("\t").toVector)

  // The heads of `encodeData` (`Data.hs:146-198`), the lists of `serialise`'s `defaultEncodeList`.
  test("the encoding of each constructor of Data") {
    assertEquals(hex(I(0)), "00")
    assertEquals(hex(I(23)), "17")
    assertEquals(hex(I(24)), "1818")
    assertEquals(hex(I(-1)), "20")
    assertEquals(hex(I(-25)), "3818")
    assertEquals(hex(I(BigInt(2).pow(64) - 1)), "1bffffffffffffffff")
    assertEquals(hex(I(-BigInt(2).pow(64))), "3bffffffffffffffff")
    assertEquals(hex(I(BigInt(2).pow(64))), "c249010000000000000000")
    assertEquals(hex(I(-BigInt(2).pow(64) - 1)), "c349010000000000000000")
    assertEquals(hex(B(Bytes.empty)), "40")
    assertEquals(hex(B(hexBytes("abcd"))), "42abcd")
    assertEquals(hex(DList(Vector.empty)), "80")
    assertEquals(hex(DList(Vector(I(1), I(2)))), "9f0102ff")
    assertEquals(hex(DMap(Vector.empty)), "a0")
    assertEquals(hex(DMap(Vector(I(1) -> I(2), I(1) -> I(3)))), "a201020103")
    assertEquals(hex(Constr(0, Vector.empty)), "d87980")
    assertEquals(hex(Constr(6, Vector(I(1)))), "d87f9f01ff")
    assertEquals(hex(Constr(7, Vector.empty)), "d9050080")
    assertEquals(hex(Constr(127, Vector.empty)), "d9057880")
    assertEquals(hex(Constr(128, Vector.empty)), "d86682188080")
    assertEquals(hex(Constr(-1, Vector.empty)), "d866822080")
  }

  test("a byte string longer than 64 bytes is written in chunks of 64") {
    val b = Bytes.wrap(Array.tabulate[Byte](129)(_.toByte))
    val encoded = DataSerialisation.serialise(B(b))
    val expected =
      "5f" + "5840" + b.slice(0, 64).hex + "5840" + b.slice(64, 128).hex + "41" + b
        .slice(128, 129)
        .hex + "ff"
    assertEquals(encoded.hex, expected)
    assertEquals(PlutusData.decode(encoded), Right(B(b)), "the reference's decoder reads it back")
  }

  // `indices.tsv`: the reference's bytes of `Constr i []` and `Constr i [I 1]`.
  test("constructors at the sign and Word64 boundaries of the index, as the reference wrote them") {
    val indices = rows("fixtures/reference/plutus-serialise-data/indices.tsv")
    assertEquals(indices.size, 5, "rows")
    for row <- indices do
      row match
        case Vector(index, empty, oneField) =>
          assertEquals(hex(Constr(BigInt(index), Vector.empty)), empty, s"Constr $index []")
          assertEquals(hex(Constr(BigInt(index), Vector(I(1)))), oneField, s"Constr $index [I 1]")
        case other => fail(s"malformed row $other")
  }

  // `vectors.tsv`: an index beyond 64 bits is one definite big number, which from 513 bits differs
  // from the chunked bytes `Data.I` would give (the last columns, not the reference's).
  test("wide constructor indices, as the reference wrote them") {
    val vectors = rows("fixtures/reference/plutus-serialise-data/vectors.tsv")
    assertEquals(vectors.size, 12, "rows")
    for row <- vectors do
      row match
        case Vector(bits, _, index, reference, _, chunked, _) =>
          val encoded = hex(Constr(BigInt(index), Vector.empty))
          assertEquals(encoded, reference, s"$bits bits")
          if bits.toInt >= 513 then assertNotEquals(encoded, chunked, s"$bits bits is not chunked")
        case other => fail(s"malformed row $other")
  }

  // `cases.tsv`: what the reference's decoder accepted is what `encodeBs` writes.
  test("the byte strings and big numbers the reference accepted at the 64-byte chunk boundary") {
    val cases = rows("fixtures/reference/plutus-data-chunks/cases.tsv")
    assertEquals(cases.size, 12, "rows")
    val accepted = cases.collect { case Vector(id, cbor, "true", _, _) => id -> cbor }
    assertEquals(accepted.size, 6, "accepted rows")
    for (id, cbor) <- accepted do
      val data = PlutusData.decode(hexBytes(cbor)).fold(e => fail(s"$id: $e"), identity)
      assertEquals(hex(data), cbor, id)
  }

  test("data nested a million levels deep is written on a small stack") {
    val depth = 1000000
    val deep = (1 to depth).foldLeft[PlutusData](I(1))((inner, _) => DList(Vector(inner)))
    val encoded = SmallStack.run(DataSerialisation.serialise(deep))
    assertEquals(encoded.size, 2 * depth + 1, "a 9f and an ff a level, and the integer")
    assertEquals(encoded.slice(0, 2).hex, "9f9f")
    assertEquals(encoded.slice(depth, depth + 1).hex, "01")
    val constrs = (1 to depth).foldLeft[PlutusData](I(1))((inner, _) => Constr(0, Vector(inner)))
    assertEquals(SmallStack.run(DataSerialisation.serialise(constrs)).size, 4 * depth + 1)
    val maps = (1 to depth).foldLeft[PlutusData](I(1))((inner, _) => DMap(Vector(inner -> I(0))))
    assertEquals(SmallStack.run(DataSerialisation.serialise(maps)).size, 2 * depth + 1)
  }

  test("equalsData compares data a million levels deep on a small stack") {
    val depth = 1000000
    def nest(bottom: PlutusData) =
      (1 to depth).foldLeft(bottom)((inner, _) => DMap(Vector(I(0) -> DList(Vector(inner)))))
    val ab = DMap(Vector(I(1) -> I(1), I(2) -> I(2)))
    val ba = DMap(Vector(I(2) -> I(2), I(1) -> I(1)))
    val (same, reordered) = SmallStack.run {
      val left = nest(ab)
      (
        CoreTesting(
          CoreVariant.E,
          DefaultFun.EqualsData,
          CoreTesting.data(left),
          CoreTesting.data(nest(ab))
        ),
        CoreTesting(
          CoreVariant.E,
          DefaultFun.EqualsData,
          CoreTesting.data(left),
          CoreTesting.data(nest(ba))
        )
      )
    }
    assertEquals(CoreTesting.result(same), Right(Constant.Bool(true)))
    assertEquals(CoreTesting.result(reordered), Right(Constant.Bool(false)))
  }
