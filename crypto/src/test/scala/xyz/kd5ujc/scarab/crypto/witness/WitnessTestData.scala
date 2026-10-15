// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes

/** Only published public keys, signatures, and messages are consumed by these tests. */
private[witness] object WitnessTestData:
  def hex(value: String): Bytes = Bytes.fromHex(value).toOption.get

  def rows(name: String): Vector[Vector[String]] =
    val local = Path.of("fixtures/witness", name)
    val path = if Files.exists(local) then local else Path.of("../fixtures/witness", name)
    Files
      .readString(path)
      .linesIterator
      .filter(line => !line.isBlank && !line.startsWith("#"))
      .map(_.split("\t", -1).toVector)
      .toVector

  final case class Witness(publicKey: PublicKey32, signature: Signature64)

  final case class PublicVector(id: String, key: Bytes, signature: Bytes, message: Bytes):
    def witness: Witness = Witness(
      PublicKey32.create(key).toOption.get,
      Signature64.create(signature).toOption.get
    )

  private lazy val vectors: Map[String, PublicVector] =
    rows("vectors.tsv").map { fields =>
      require(fields.size == 4, "public vector must have exactly four fields")
      val vector = PublicVector(fields(0), hex(fields(1)), hex(fields(2)), hex(fields(3)))
      vector.id -> vector
    }.toMap

  def vector(id: String): PublicVector = vectors(id)

  def flip(bytes: Bytes, index: Int): Bytes =
    Bytes(bytes.value.updated(index, (bytes.value(index) ^ 1).toByte))
