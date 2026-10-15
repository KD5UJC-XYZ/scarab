// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Eq
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

import NodeCodecs.given
import Gens.given

/** Round-trip properties for every persisted type, truncation detection and golden encodings. */
class CodecSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(60)

  private def roundTrip[A](name: String)(using c: Codec[A], g: Gens[A]): Unit =
    property(s"round trip: $name") {
      forAll(g.gen) { a =>
        Codec.decode[A](Codec.encode(a)) match
          case Right(back) => Eq.fromUniversalEquals[A].eqv(a, back) :| s"decoded $back"
          case Left(error) => Prop.falsified :| error
      }
    }

  roundTrip[BigInt]("BigInt")
  roundTrip[Bytes]("Bytes")
  roundTrip[Rational]("Rational")
  roundTrip[Map[Bytes, BigInt]]("Map")
  roundTrip[NativeScript]("NativeScript")
  roundTrip[Credential]("Credential")
  roundTrip[Address]("Address")
  roundTrip[Value]("Value")
  roundTrip[TxOut]("TxOut")
  roundTrip[TxIn]("TxIn")
  roundTrip[PoolParams]("PoolParams")
  roundTrip[ProtocolParams]("ProtocolParams")
  roundTrip[Nonce]("Nonce")
  roundTrip[Era]("Era")
  roundTrip[Account]("Account")
  roundTrip[Snapshot]("stake Snapshot")
  roundTrip[RewardUpdate]("RewardUpdate")
  roundTrip[GovAction]("GovAction")
  roundTrip[Proposal]("Proposal")
  roundTrip[GovState]("GovState")
  roundTrip[LedgerState]("LedgerState")
  roundTrip[PraosState]("PraosState")
  roundTrip[LiteLedger]("LiteLedger")
  roundTrip[BootstrapState]("BootstrapState")
  roundTrip[ByronState]("ByronState")
  roundTrip[BlockRef]("BlockRef")
  roundTrip[NodeState]("NodeState")
  roundTrip[StateSnapshot[NodeState]]("StateSnapshot[NodeState]")

  property("a strict prefix of an encoding never decodes") {
    forAll(Gens[StateSnapshot[NodeState]], Gen.choose(0.0, 1.0)) { (s, fraction) =>
      val bytes = Codec.encode(s)
      val cut = (bytes.length * fraction).toInt.min(bytes.length - 1)
      Codec.decode[StateSnapshot[NodeState]](bytes.take(cut)).isLeft
    }
  }

  property("an encoding with trailing bytes does not decode") {
    forAll(Gens[BlockRef]) { r =>
      Codec.decode[BlockRef](Codec.encode(r) :+ 0.toByte).isLeft
    }
  }

  property("decoding arbitrary bytes never throws") {
    forAll(Gen.listOf(Gen.choose(-128, 127).map(_.toByte)).map(_.toArray)) { bytes =>
      Codec.decode[StateSnapshot[NodeState]](bytes)
      Codec.decode[LedgerState](bytes)
      true
    }
  }

  test("the reader never reads past its limit, wherever a value straddles its buffer") {
    // A value that spans a refill used to over-read into the bytes after the limit (the checksum).
    (65400 to 65700).foreach { size =>
      val value = Bytes(Vector.tabulate(size)(_.toByte))
      val payload = Codec.encode(value)
      val tail = Array.fill[Byte](40)(0x5a)
      val stream = java.io.ByteArrayInputStream(payload ++ tail)
      val in = In(stream, payload.length.toLong)
      assertEquals(summon[Codec[Bytes]].read(in), value)
      assertEquals(stream.available(), tail.length, s"size $size")
    }
  }

  test("a huge collection count is rejected without allocating") {
    val bytes = Array[Byte](-1, -1, -1, -1, -1, -1, -1, -1, 127)
    assert(Codec.decode[Map[Bytes, BigInt]](bytes).isLeft)
    assert(Codec.decode[Bytes](bytes).isLeft)
  }

  // ------------------------------------------------------------------ golden encodings

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  private val hash32 = Bytes(Vector.tabulate(32)(i => (i + 1).toByte))

  test("golden: primitives and containers") {
    assertEquals(hex(Codec.encode(BigInt(0))), "0100")
    assertEquals(hex(Codec.encode(BigInt(-1))), "01ff")
    assertEquals(hex(Codec.encode(BigInt(256))), "020100")
    assertEquals(hex(Codec.encode(Bytes(Vector(1, 2, 3)))), "03010203")
    assertEquals(hex(Codec.encode(Option.empty[Int])), "00")
    assertEquals(hex(Codec.encode(Option(300))), "01ac02")
    assertEquals(hex(Codec.encode(Vector(true, false, true))), "03010001")
    assertEquals(hex(Codec.encode(Rational(3, 6))), "01010102")
    assertEquals(hex(Codec.encode(Map(Bytes(Vector(9)) -> BigInt(5)))), "0101090105")
  }

  test("golden: sums are a variant index then the variant") {
    assertEquals(hex(Codec.encode[Nonce](Nonce.Neutral)), "00")
    assertEquals(hex(Codec.encode[Nonce](Nonce.Hash(Bytes(Vector(7))))), "010107")
    assertEquals(hex(Codec.encode[Era](Era.Byron)), "00")
    assertEquals(hex(Codec.encode[Era](Era.Conway)), "06")
    assertEquals(hex(Codec.encode[Credential](Credential.Script(Bytes(Vector(1))))), "010101")
  }

  test("golden: BlockRef") {
    assertEquals(
      hex(Codec.encode(BlockRef(86400, hash32, 4321, boundary = true))),
      "03015180" + "20" + hex(hash32.toArray) + "0210e1" + "01"
    )
  }

  private lazy val initial: NodeState =
    NodeSetup
      .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), _.start)

  test("golden: the initial state of the eras fixture encodes to a fixed digest") {
    val encoded = Codec.encode(StateSnapshot(Option.empty[BlockRef], initial))
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
    assertEquals(hex(digest) -> encoded.length, GoldenInitialState)
  }

  test("golden: the schema fingerprint of the node snapshot") {
    val shape = summon[Codec[StateSnapshot[NodeState]]].shape
    assertEquals(hex(SnapshotV2.fingerprint(shape)), GoldenFingerprint)
  }

  test("golden: the schema fingerprint of the V3 sidecar") {
    assertEquals(hex(SnapshotSidecar.fingerprint), GoldenSidecarFingerprint)
  }

  test("golden: the sidecar of the initial state of the eras fixture encodes to a fixed digest") {
    val sidecar = SnapshotSidecar(
      None,
      Map.empty,
      Set.empty,
      SnapshotSidecar.Chain.InByron(initial)
    )
    val encoded = Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
    assertEquals(hex(digest) -> encoded.length, GoldenSidecar)
  }

  // Regenerate (and add a FormatVersion with its migration if the layout itself changed) only when
  // a persisted type changes on purpose: old snapshots are then rejected and recovery replays.
  // GoldenFingerprint is the V2 reader's (frozen with the node state of its time, see
  // SnapshotV2); the sidecar goldens are the V3 writer's.
  // Last regenerated for the previous epoch nonce of PraosState, the PlutusV2 cost model that the
  // Alonzo genesis carries in `extraConfig` (part of the fixture's initial protocol parameters) and
  // the Byron ledger state (UTxO, delegation, update system) carried in NodeState, and the idle
  // pools of a stake snapshot (K1: the pool distribution lists a pool whose delegators have no stake).
  private val GoldenInitialState =
    ("228a41672061e4a01954a3a41c706932aa13f0e89185fc5c6636796b56428a4a", 3158)
  private val GoldenFingerprint = "2293c5cf7bb77492"
  private val GoldenSidecarFingerprint = "65d0043c049b7b7a"
  private val GoldenSidecar =
    ("7bc7dc357f2b64267b3bb7c8bb5b79e4275dca9bd84693d1db02a32d872242a4", 3161)
