// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.PulsingRewUpdate

/** Direct native K1 projections against the era-capture goldens. The fixture README records the
  * J2.7/J2.9 history and #135's pending-only rewardUpdate change.
  */
class ProjectionSuite extends FunSuite:
  private val recorded: Vector[String] =
    Files.readAllLines(ProjectionGolden.file).asScala.toVector

  private lazy val shelleyBased = ErasStates.samples.zipWithIndex.filter(_._1.era != Era.Byron)

  test("the recorded digests cover every state of the capture") {
    assertEquals(
      ProjectionGolden.sha256(Files.readString(ProjectionGolden.file)),
      "6c3e409a85bfaf66d7e2fb8a2c40353ad1a10abf2e901dd3e9953680ad73d3a6",
      "preserve the original digest corpus, including all 50 archived Byron rows"
    )
    assertEquals(recorded.size, 731, "the complete original digest corpus is required")
    assertEquals(recorded.size, ErasStates.samples.size)
    recorded.zip(ErasStates.samples).zipWithIndex.foreach { case ((line, sample), index) =>
      line.split("\t", -1).toVector match
        case Vector(row, era, block, digest) =>
          assertEquals(row, index.toString)
          assertEquals(era, sample.era.toString)
          assertEquals(block, sample.block.header.blockNo.toString)
          assertEquals(digest.length, 64)
          assert(Bytes.fromHex(digest).isRight, s"invalid digest at row $index")
        case _ => fail(s"${ProjectionGolden.file}:${index + 1}: expected four TSV fields")
    }
  }

  test("the mirror projects to the recorded text, in every Shelley-based era") {
    shelleyBased.foreach { (s, i) =>
      assertEquals(
        ProjectionGolden.line(i, s),
        recorded(i),
        s"${s.era} block ${s.block.header.blockNo}"
      )
    }
  }

  private def array(node: Node, size: Int): Vector[Node] = node.value match
    case Value.Arr(fields) if fields.size == size => fields
    case other => fail(s"expected $size original CBOR fields, got $other")

  private def uint(node: Node): BigInt = node.value match
    case Value.UInt(n) => n
    case other         => fail(s"expected original unsigned integer, got $other")

  /** A local projection of the prepared Shelley state, not a native PBFT query answer. Consensus
    * 82ecba3, Cardano/CanHardFork.hs:405–417 seeds these three nonces from the Shelley genesis
    * hash, leaves the other nonces neutral and initializes no operational certificates.
    */
  private def byronProtocol(initialNonce: String): Json = Json.obj(
    "epochNonce" -> Json.fromString(initialNonce),
    "evolvingNonce" -> Json.fromString(initialNonce),
    "candidateNonce" -> Json.fromString(initialNonce),
    "labNonce" -> Json.Null,
    "lastEpochBlockNonce" -> Json.Null,
    "oCertCounters" -> Json.obj()
  )

  test(
    "all 50 Byron projections match original-header points and the prepared Shelley view"
  ) {
    val dir = Path.of("fixtures/eras")
    val genesis = parse(Files.readString(dir.resolve("genesis/byron-genesis.json")))
      .fold(e => fail(e.message), identity)
    val k = genesis.hcursor
      .downField("protocolConsts")
      .get[BigInt]("k")
      .fold(e => fail(e.message), identity)
    val epochSlots = 10 * k
    // Consensus 82ecba3, Cardano/Node/Protocol/Shelley.hs:59–80 hashes the original file bytes.
    val initialNonce = Blake2b.hash256
      .hash(
        Bytes.fromArray(
          Files.readAllBytes(dir.resolve("genesis/shelley-genesis.json"))
        )
      )
      .hex
    assertEquals(initialNonce, "87b08ba08e075a9cdf610b78cbaa03d7e6752b768c90403a1fd2179f7dc1ba78")
    assertEquals(epochSlots, BigInt(50), "bind slot interpretation to the original genesis")
    val originals = Files.readAllLines(dir.resolve("blocks.hex")).asScala.toVector.take(50)
    val samples = ErasStates.samples.filter(_.era == Era.Byron)
    assertEquals(samples.size, 50)
    assertEquals(originals.size, samples.size)
    originals.zip(samples).zipWithIndex.foreach { case ((hex, sample), index) =>
      val bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)
      val root = Cbor.decode(bytes).fold(e => fail(e.message), identity)
      val outer = array(root, 2)
      val tag = uint(outer(0))
      assert(tag == 0 || tag == 1, s"row $index must be an original Byron block")
      val header = array(outer(1), 3).head
      val fields = array(header, 5)
      val consensus = array(fields(3), if tag == 0 then 2 else 4)
      val slot =
        if tag == 0 then uint(consensus.head) * epochSlots
        else
          val position = array(consensus.head, 2)
          uint(position.head) * epochSlots + uint(position(1))
      // cardano-ledger f649f975, Cardano/Chain/Block/Header.hs:489–500,633–634:
      // hash the original header prefixed by [boundary/main tag, header], never a re-encoding.
      val prefix = Bytes(Vector(0x82.toByte, tag.toByte))
      val hash = Blake2b.hash256.hash(prefix ++ header.original)
      val expected = Json.obj(
        "point" -> Json.obj("slot" -> Json.fromBigInt(slot), "hash" -> Json.fromString(hash.hex)),
        "protocolState" -> byronProtocol(initialNonce)
      )
      assertEquals(ProjectionGolden.ofMirror(sample), expected, s"original Byron block row $index")
    }
  }

  test("candidate recording refuses the original destination and preserves archived Byron rows") {
    Vector(
      ProjectionGolden.file,
      ProjectionGolden.file.toAbsolutePath,
      ProjectionGolden.file.getParent.resolve("nested/../eras.tsv")
    ).foreach { destination =>
      assertEquals(
        ProjectionGolden.candidatePath(destination),
        Left(ProjectionGolden.RecordingError.OriginalDestination)
      )
    }
    val eras = ErasStates.samples.map(_.era)
    val candidate = ProjectionGolden
      .candidateRows(recorded, eras) { index =>
        assert(
          eras(index) != Era.Byron,
          "a current Byron projection must never replace its archive"
        )
        s"replacement-$index"
      }
      .fold(e => fail(e.toString), identity)
    assertEquals(candidate.take(50), recorded.take(50), "all original Byron bytes remain verbatim")
    assertEquals(candidate.drop(50), (50 until 731).map(i => s"replacement-$i").toVector)
    assertEquals(
      ProjectionGolden.candidateRows(recorded.drop(1), eras)(_ => "unused"),
      Left(ProjectionGolden.RecordingError.RowCount(731, 730))
    )
    assertEquals(
      ProjectionGolden.candidateRows(recorded.updated(0, "malformed"), eras)(_ => "unused"),
      Left(ProjectionGolden.RecordingError.ArchivedByronRow(0))
    )
  }

  test("a native pending reward update stays absent from the completed-reward projection") {
    val pending = shelleyBased
      .map(_._1)
      .filter(s =>
        MirrorStep.nesOf(s.mirror).exists { case i: EraNes.InEra[e] =>
          i.nes.ru.exists {
            case PulsingRewUpdate.Pulsing(_, _) => true
            case _                              => false
          }
        }
      )
    assertEquals(pending.size, 19)
    pending.foreach(s =>
      assertEquals(
        ProjectionGolden.ofMirror(s).hcursor.get[Json]("rewardUpdate").toOption,
        Some(Json.Null)
      )
    )
  }
