// SPDX-License-Identifier: Apache-2.0
package lab.bls

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

/** Loading of the committed vectors and construction of test keys. */
object BlsFixtures:
  val dir: Path = Path.of("fixtures", "leios", "bls")

  def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => throw new AssertionError(e), identity)

  def right[A](e: Either[BlsError, A]): A =
    e.fold(err => throw new AssertionError(err.toString), identity)

  /** Throwaway keys from the Leios proto-devnet (public upstream): name, key, key bytes, proof. */
  final case class DevnetPool(
      name: String,
      sk: SecretKey,
      vk: VerificationKey,
      pop: ProofOfPossession
  )

  lazy val devnetPools: Vector[DevnetPool] =
    Files
      .readString(dir.resolve("devnet-pools.tsv"))
      .linesIterator
      .filterNot(_.startsWith("#"))
      .filter(_.nonEmpty)
      .map { line =>
        val p = line.split("\t", -1)
        DevnetPool(
          p(0),
          right(SecretKey.fromBytes(hex(p(1)))),
          right(VerificationKey.fromBytes(hex(p(2)))),
          right(ProofOfPossession.fromBytes(hex(p(3))))
        )
      }
      .toVector

  /** Eight lines of cardano-base's serde vectors, in the order of its README. */
  lazy val serde: Vector[Bytes] =
    Files
      .readString(dir.resolve("cardano-base-serde-test-vectors"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(hex)
      .toVector

  def seed(n: Int): Seed =
    right(Seed.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(i => (n + i * 7).toByte))))

  def key(n: Int): (SecretKey, VerificationKey) =
    val sk = right(SecretKey.generate(seed(n)))
    (sk, right(VerificationKey.derive(sk)))

  def rbHash(n: Int): RbHash =
    RbHash.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(i => (n * 31 + i).toByte))).get
end BlsFixtures
