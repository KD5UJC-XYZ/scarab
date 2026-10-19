// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack

class SclsCommitmentSuite extends FunSuite:
  import SclsCommitment.*
  import SclsCommitment.Digest.*

  private val directory = Path.of("fixtures/scls/conway-4593")
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.message), identity)
  private def utf8(value: String): Bytes = Bytes.wrap(value.getBytes(UTF_8))
  private def lines(file: String): Vector[Vector[String]] =
    Files.readString(directory.resolve(file)).linesIterator.map(_.split("\t", -1).toVector).toVector

  private val entries = lines("entries.tsv").map {
    case Vector(namespace, key, value) => (namespace, hex(key), hex(value))
    case row                           => fail(s"invalid entry fixture $row")
  }
  private val roots = lines("roots.tsv").map {
    case Vector(namespace, count, root) => (namespace, count.toInt, hex(root))
    case row                            => fail(s"invalid root fixture $row")
  }

  test("all twelve actual Haskell namespace commitments and global root match") {
    val namespaces = roots.map { case (namespace, count, expected) =>
      val selected = entries.filter(_._1 == namespace)
      assertEquals(selected.size, count, namespace)
      val tree = selected.foldLeft(empty) { case (tree, (_, key, value)) =>
        tree.addEntry(utf8(namespace), key, value)
      }
      assertEquals(tree.root.bytes, expected, namespace)
      tree.root
    }
    val global = namespaces.foldLeft(empty)((tree, root) => tree.addNamespace(root)).root
    assertEquals(
      global.bytes,
      hex("fb12b75ed9b1abcbd341bd751b0c45606e060fd714e6bb97ce5dcb0a")
    )
  }

  test("namespace, key, value, order and the namespace-root leaf prefix are committed") {
    val selected = entries.filter(_._1 == "utxo/v0")
    def tree(rows: Vector[(String, Bytes, Bytes)]): Bytes =
      rows
        .foldLeft(empty) { case (tree, (namespace, key, value)) =>
          tree.addEntry(utf8(namespace), key, value)
        }
        .root
        .bytes
    val expected = tree(selected)
    assertNotEquals(tree(selected.reverse), expected)
    selected.indices.foreach { index =>
      val (namespace, key, value) = selected(index)
      Vector(
        (namespace + "x", key, value),
        (namespace, key ++ hex("00"), value),
        (namespace, key, value ++ hex("00"))
      ).foreach(changed => assertNotEquals(tree(selected.updated(index, changed)), expected))
    }
    val direct = empty.addEntry(utf8("utxo/v0"), hex("00"), hex("01")).root
    assertNotEquals(empty.addNamespace(direct).root.bytes, direct.bytes)
  }

  test("large odd trees are stack safe and retained prefixes remain immutable") {
    onSmallStack {
      val namespace = utf8("utxo/v0")
      val prefix = empty.addEntry(namespace, hex("01"), hex("02"))
      val before = prefix.root.bytes
      val extended = (0 until 100001).foldLeft(prefix) { (tree, index) =>
        tree.addEntry(namespace, Bytes.wrap(BigInt(index).toByteArray), hex("03"))
      }
      assertEquals(prefix.root.bytes, before)
      assertNotEquals(extended.root.bytes, before)
      assertEquals(
        empty.root.bytes,
        hex("836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07")
      )
    }
  }
