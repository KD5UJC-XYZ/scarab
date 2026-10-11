// SPDX-License-Identifier: Apache-2.0
package lab.node

import lab.Blake2b
import lab.cbor.Bytes
import lab.network.Era
import org.scalacheck.Gen

/** Synthetic, internally consistent headers for chain-shape tests. Crypto fields are dummies. */
object Fixtures:
  private def filled(size: Int, seed: Int): Bytes = Bytes(Vector.fill(size)(seed.toByte))

  def header(blockNo: BigInt, slot: BigInt, prev: Option[Bytes], salt: Int = 0): Header =
    val raw = Bytes(s"header-$blockNo-$slot-${prev.map(_.hex)}-$salt".getBytes.toVector)
    Header.Shelley(
      era = Era.Conway,
      raw = raw,
      bodyRaw = raw,
      hash = Blake2b.hash256.hash(raw),
      blockNo = blockNo,
      slot = slot,
      prevHash = prev,
      issuerVkey = filled(32, 1),
      vrfVkey = filled(32, 2),
      vrf = HeaderVrf.Praos(VrfCert(filled(64, 3), filled(80, 4))),
      bodySize = 0,
      bodyHash = filled(32, 5),
      opCert = Header.OpCert(filled(32, 6), 0, 0, filled(64, 7)),
      protocolVersion = (BigInt(10), BigInt(0)),
      kesSignature = filled(448, 8)
    )

  /** A linked chain of `n` headers from genesis with strictly increasing slots. */
  def chain(n: Int, salt: Int = 0, from: Option[Header] = None): Vector[Header] =
    Iterator
      .iterate(Option.empty[Header] -> from) { case (_, prev) =>
        val next = header(
          prev.fold(BigInt(0))(_.blockNo + 1),
          prev.fold(BigInt(1))(_.slot + 1 + (salt % 3)),
          prev.map(_.hash),
          salt
        )
        Some(next) -> Some(next)
      }
      .drop(1)
      .map(_._1.get)
      .take(n)
      .toVector

  val chainGen: Gen[Vector[Header]] =
    Gen.choose(0, 30).flatMap(n => Gen.choose(0, 5).map(s => chain(n, s)))
