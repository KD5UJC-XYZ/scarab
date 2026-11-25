// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.era.{EraTx, EraTxBody, TxLevel}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, Node}
import xyz.kd5ujc.scarab.storage.haskell.state.Params

/** Independent native LEDGERS observations of original signed, dependent transactions. Refusals
  * compare every accumulated predicate failure, in native order, as complete CBOR bytes.
  */
class UpstreamMultiLedgerConformanceSuite extends FunSuite:
  import UpstreamNativeStates.given
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val base = Path.of("node/src/test/resources/upstream/conway-native-ledgers")
  private val originals = Path.of("node/src/test/resources/upstream/conway-native-refund/cases")
  private val geometry =
    EpochGeometry(
      Instant.parse("2017-09-23T21:44:51Z"),
      1000L,
      4320L,
      TestGeometry.k(108),
      TestGeometry.f("0.2")
    )
  private val globals = Globals(
    LedgerGlobals(
      BigInt("45000000000000000"),
      4320,
      Rational(1, 5),
      Network.Testnet,
      systemStart = xyz.kd5ujc.scarab.ledger.SystemStart(geometry.systemStart),
      kes = xyz.kd5ujc.scarab.ledger.KesParameters.Mainnet
    ),
    geometry
  )

  private def node(path: Path): Node =
    Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(path)), Cbor.Limits(allowFloats = true))
      .fold(e => fail(s"$path: $e"), identity)

  private def item(id: String, name: String): Node = node(base.resolve(s"cases/$id/$name.cbor"))
  private def array(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"expected array, got $other")
  private def decode[A](n: Node, codec: Codec[A]): A =
    codec.decode(n).fold(e => fail(e.render), identity)

  private def assertLedger(actual: LedgerState[Conway], expected: LedgerState[Conway]): Unit =
    def parts(state: LedgerState[Conway]) = state.utxo.utxo.utxo.toMap.toVector
      .traverse { (input, output) => TxOutMemPack.parts(output).map(input -> _) }
      .fold(e => fail(e.toString), _.toMap)
    assertEquals(parts(actual), parts(expected), "every input and every MemPack output field")
    val found = actual.utxo.utxo.utxo
    val wanted = expected.utxo.utxo.utxo
    assertEquals(
      found.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap,
      wanted.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap
    )
    assertEquals(
      (found.count, found.coin, found.stake, found.ptrStake),
      (wanted.count, wanted.coin, wanted.stake, wanted.ptrStake)
    )
    assertEquals(
      Diff[LedgerState[Conway]]
        .diff(actual.copy[Conway](utxo = actual.utxo.copy(utxo = expected.utxo.utxo)), expected),
      None
    )

  private val cases =
    Vector("ordered-three", "dependent-two", "reversed-dependency", "double-spend")
  test("all independent native batch artifacts and their original inputs are pinned") {
    val manifest = parse(Files.readString(base.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[Int]("runnerExitCode").toOption, Some(0))
    assertEquals(
      manifest.get[String]("sourceCommit").toOption,
      Some("f649f9751074d2ab3de033fc3912f29c9862c1f5")
    )
    def sha256(bytes: Array[Byte]): String = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(
      manifest.get[String]("originalManifestSha256").toOption,
      Some(sha256(Files.readAllBytes(originals.getParent.resolve("manifest.json"))))
    )
    val patches =
      manifest.get[Map[String, String]]("observerPatches").fold(e => fail(e.message), identity)
    patches.foreach { (path, expected) =>
      assertEquals(sha256(Files.readAllBytes(Path.of(path))), expected, path)
    }
    val rows = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    assertEquals(
      rows.map(_.hcursor.get[String]("id").toOption).toSet,
      cases.map(id => Option(id)).toSet
    )
    rows.foreach { row =>
      val id = row.hcursor.get[String]("id").fold(e => fail(e.message), identity)
      val files = row.hcursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      files.foreach { (name, metadata) =>
        val bytes = Files.readAllBytes(base.resolve(s"cases/$id/$name"))
        val sha = sha256(bytes)
        assertEquals(metadata.hcursor.get[String]("sha256").toOption, Some(sha), s"$id/$name")
        assertEquals(metadata.hcursor.get[Int]("bytes").toOption, Some(bytes.length), s"$id/$name")
      }
    }
  }

  // the era-typed LEDGERS on each batch, each transaction read by Conway's decoder from its
  // original parts: an accepted batch leaves the reference's post-state, a refused one's failures,
  // in their order, encode to the reference's bytes
  cases.foreach { id =>
    test(s"the era-typed LEDGERS gives the native state or complete ordered refusal: $id") {
      val before = decode(item(id, "state"), UpstreamNativeStates.nativeLedgerState)
      val env = array(item(id, "environment"))
      assertEquals(env.size, 4)
      assertEquals(decode(env(2), Params.conwayCodec), before.utxo.gov.curPParams)
      val signals = array(item(id, "signal"))
      val originalIndices = id match
        case "ordered-three"       => Vector(1, 2, 3)
        case "dependent-two"       => Vector(2, 3)
        case "reversed-dependency" => Vector(3, 2)
        case "double-spend"        => Vector(2, 2)
      signals.zip(originalIndices).foreach { (signal, ordinal) =>
        assertEquals(
          signal.original,
          node(originals.resolve(s"LEDGER-$ordinal/signal.cbor")).original
        )
      }
      // the original parts as signed, retained whole by the decoder
      signals.foreach { signal =>
        val fields = array(signal)
        assertEquals(fields.size, 4)
        val valid = fields(2).value match
          case Value.Bool(v) => v
          case other         => fail(s"expected validity flag, got $other")
        val auxiliary = fields(3).value match
          case Value.Null => None
          case _          => Some(fields(3).original)
        val flat = Tx
          .fromBlock(
            Block.Transaction(fields(0).original, fields(1).original, auxiliary, valid),
            xyz.kd5ujc.scarab.protocol.Era.Conway
          )
          .fold(e => fail(e.message), identity)
        assertEquals(flat.body.raw, fields(0).original, "retain signed body bytes")
        assertEquals(
          flat.witnesses.raw,
          Some(fields(1).original),
          "retain original signed witnesses"
        )
      }
      val txs = signals.map(
        UpstreamEraLedger.transaction(_).fold(e => fail(s"$id transaction: $e"), identity)
      )
      assert(txs.size > 1)
      val t = EraTx.conway
      val b = EraTxBody.conway
      val bodies = txs.map(tx => t.body[TxLevel.Top](tx)(using t.bodyOf.top))
      val ids = bodies.map(body => b.id[TxLevel.Top](body)(using b.top))
      // each body read from its signed bytes
      assertEquals(
        ids,
        signals.map(signal =>
          xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(array(signal)(0).original)
        ),
        "retain signed body bytes"
      )
      if id != "double-spend" then assertEquals(ids.distinct.size, txs.size)
      val outcome = UpstreamEraLedger
        .ledgers(
          globals,
          decode(env(0), summon[Codec[SlotNo]]).toBigInt,
          decode(env(1), summon[Codec[EpochNo]]),
          decode(env(2), Params.conwayCodec),
          decode(env(3), summon[Codec[ChainAccountState]]),
          before,
          txs
        )
        .fold(e => fail(s"$id era inputs: $e"), identity)
      UpstreamEraLedger.ofLedgers(outcome).fold(e => fail(s"$id defect: $e"), identity) match
        case UpstreamEraLedger.Outcome.Admitted(state, _) =>
          assertEquals(
            Files.readString(base.resolve(s"cases/$id/result.txt")),
            "implementation-accepted\nformal-not-invoked\n"
          )
          assert(
            bodies.zip(ids).sliding(2).exists { pair =>
              b.inputs[TxLevel.Top](pair(1)._1)(using b.top).exists(_.id == pair(0)._2)
            },
            "a later transaction actually spends an earlier transaction's output"
          )
          assertLedger(
            state,
            decode(item(id, "implementation-state"), UpstreamNativeStates.nativeLedgerState)
          )
        case UpstreamEraLedger.Outcome.Refused(failures) =>
          assertEquals(
            Files.readString(base.resolve(s"cases/$id/result.txt")),
            "implementation-rejected\nformal-not-invoked\n"
          )
          // the era failures written by their own encoder
          val wire =
            Cbor.decode(UpstreamEraLedger.refusal(failures)).fold(e => fail(e.message), identity)
          assertEquals(
            array(array(wire).head)(1).original.hex,
            item(id, "implementation-failure").original.hex
          )
    }
  }
