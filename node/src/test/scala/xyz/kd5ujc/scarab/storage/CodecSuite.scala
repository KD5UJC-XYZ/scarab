// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Eq
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  EraLedgerState,
  MirrorExtras,
  ProtocolExtras
}
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
          case Left(error) => Prop.falsified :| error.message
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
  // Governance actions and proposals are persisted by the Haskell-section codec only
  // (`NodeTypeCodecs`), whose laws `HaskellStateCodecSuite` checks on the proposals.
  roundTrip[ChainDepState]("ChainDepState")
  roundTrip[ProtocolExtras]("ProtocolExtras")
  roundTrip[LiteLedger]("LiteLedger")
  roundTrip[BootstrapState]("BootstrapState")
  roundTrip[ByronState]("ByronState")
  roundTrip[BlockRef]("BlockRef")
  roundTrip[ConsensusExtras]("ConsensusExtras")

  property("a strict prefix of an encoding never decodes") {
    forAll(Gens[ConsensusExtras], Gen.choose(0.0, 1.0)) { (s, fraction) =>
      val bytes = Codec.encode(s)
      val cut = (bytes.length * fraction).toInt.min(bytes.length - 1)
      Codec.decode[ConsensusExtras](bytes.take(cut)).isLeft
    }
  }

  property("an encoding with trailing bytes does not decode") {
    forAll(Gens[BlockRef]) { r =>
      Codec.decode[BlockRef](Codec.encode(r) :+ 0.toByte).isLeft
    }
  }

  property("decoding arbitrary bytes never throws") {
    forAll(Gen.listOf(Gen.choose(-128, 127).map(_.toByte)).map(_.toArray)) { bytes =>
      Codec.decode[ConsensusExtras](bytes)
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
      assertEquals(summon[Codec[Bytes]].read(in), Right(value))
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

  test("Byron versions retain the previous three-BigInt layout and schema") {
    import xyz.kd5ujc.scarab.ledger.byron.ByronVersion
    val oldBytes = Codec.encode(BigInt(65535)) ++ Codec.encode(BigInt(65535)) ++
      Codec.encode(BigInt(255))
    val expected = ByronVersion.from(65535, 65535, 255).fold(e => fail(e.message), identity)
    assertEquals(Codec.decode[ByronVersion](oldBytes), Right(expected))
    assertEquals(Codec.encode(expected).toVector, oldBytes.toVector)
    assertEquals(Codec[ByronVersion].shape, "ByronVersion(major:BigInt,minor:BigInt,alt:BigInt)")

  }

  test("Byron version snapshot readers reject values outside the reference widths") {
    import xyz.kd5ujc.scarab.ledger.byron.ByronVersion
    Vector((65536, 0, 0), (0, 65536, 0), (0, 0, 256), (-1, 0, 0)).foreach { (major, minor, alt) =>
      val bytes =
        Codec.encode(BigInt(major)) ++ Codec.encode(BigInt(minor)) ++ Codec.encode(BigInt(alt))
      assert(Codec.decode[ByronVersion](bytes).isLeft)

    }
  }

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

  private lazy val initial =
    NodeSetup
      .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), _.mirrorStart)

  test("golden: the schema fingerprint of the sidecar of the V5 body (V9 and V10)") {
    assertEquals(hex(SnapshotSidecar.fingerprint), GoldenSidecarFingerprint)
  }

  test("golden: the frozen sidecar schema of V5 and V7 files keeps its fingerprint") {
    assertEquals(hex(SnapshotSidecarV7.fingerprint), GoldenPreviousSidecarFingerprint)
  }

  test("golden: the frozen sidecar schema of V8 files keeps its fingerprint") {
    assertEquals(hex(SnapshotSidecarV8.fingerprint), GoldenV8SidecarFingerprint)
  }

  test("golden: the schema fingerprint and encodings of the V7 checkpoint binding") {
    assertEquals(hex(CheckpointBinding.fingerprint), GoldenBindingFingerprint)
    def encoded(b: CheckpointBinding) = hex(Codec.encode(b)(using CheckpointBinding.codec))
    assertEquals(encoded(CheckpointBinding.Legacy), "00")
    assertEquals(encoded(CheckpointBinding.Unbacked), "01")
    assertEquals(
      encoded(
        CheckpointBinding.Bound(
          xyz.kd5ujc.scarab.ledger.UtxoKeyLayout.TxIdIndex16,
          xyz.kd5ujc.scarab.codec.Bytes.wrap(Array.tabulate[Byte](32)(_.toByte))
        )
      ),
      "020020" + (0 until 32).map(i => f"$i%02x").mkString
    )
  }

  // V10 (#826) records the reference build in its header, outside the fingerprinted binding and
  // sidecar schemas, whose goldens above are unchanged from V9: a length byte and the flag string
  test("golden: the reference build a V10 file records after its magic, before the binding") {
    assertEquals(new String(SnapshotV10.Magic, "US-ASCII"), "SCRBSNPA")
    assertEquals(hex(SnapshotV10.buildBytes(ReferenceBuild.Release11_1)), "0431312e31")
    assertEquals(
      hex(SnapshotV10.buildBytes(ReferenceBuild.LeiosB004fe0c)),
      "0e6c65696f732d6230303466653063"
    )
  }

  test("golden: the sidecar of the initial state of the eras fixture encodes to a fixed digest") {
    val (pending, extras) = (initial.extras, initial.ext.ledgerState.state) match
      case (MirrorExtras.InByron(pending, boot, protocol), EraLedgerState.ByronCurrent(b)) =>
        (pending, ConsensusExtras(boot, b, protocol))
      case _ => fail("the initial state is not Byron")
    val sidecar = SnapshotSidecar(
      None,
      Map.empty,
      Set.empty,
      SnapshotSidecar.Chain.InByron(pending, extras)
    )
    val encoded = Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
    assertEquals(hex(digest) -> encoded.length, GoldenSidecar)
  }

  // A deliberate persisted-layout change needs a new FormatVersion, migration and previous-version
  // read test (ADR 0006). These are the native V5 writer's current sidecar goldens; complete-file
  // fixture hashes and their historical conversion provenance are pinned in SnapshotGoldenSuite.
  // Retired V1–V4 readers and fingerprints remain in pinned historical source and conversion evidence.
  // Last regenerated for the previous epoch nonce of PraosState, the PlutusV2 cost model that the
  // Alonzo genesis carries in `extraConfig` (part of the fixture's initial protocol parameters) and
  // the Byron ledger state (UTxO, delegation, update system) carried in NodeState, and the idle
  // pools of a stake snapshot (K1: the pool distribution lists a pool whose delegators have no stake).
  // and the installer metadata of the Byron update state's software proposals and application
  // versions (V8, #726), and the Dijkstra era among the eras and the fields Dijkstra adds to the
  // protocol parameters (V9, #54).
  private val GoldenSidecarFingerprint = "7650a759442924ec"
  // The sidecar schema of V5 and V7 files (`SnapshotSidecarV7`), frozen at V8: never regenerated.
  private val GoldenPreviousSidecarFingerprint = "7c483457b06802d6"
  // The sidecar schema of V8 files (`SnapshotSidecarV8`), frozen at V9: never regenerated.
  private val GoldenV8SidecarFingerprint = "31718eaf81232a77"
  // The V7 binding in front of the V5 body (#395): a change to it is a new version too.
  private val GoldenBindingFingerprint = "bd43aa08b4fae886"
  private val GoldenSidecar =
    ("577b9a786e5562dd44cf11e09fe8255ac01dcc276042751cc9e8aef67690a9b2", 1214)
