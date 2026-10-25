// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

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
import xyz.kd5ujc.scarab.ledger.rules.{Counted, Globals, Ledgers, LedgersEnv, Sts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, Node}
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.Params

/** Independent native LEDGERS observations of original signed, dependent transactions. Refusals
  * compare one complete typed cause with native list membership, not full failure-list equality.
  */
class UpstreamMultiLedgerConformanceSuite extends FunSuite:
  import UpstreamNativeStates.given
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val base = Path.of("node/src/test/resources/upstream/conway-native-ledgers")
  private val originals = Path.of("node/src/test/resources/upstream/conway-native-refund/cases")
  private val geometry =
    EpochGeometry(Instant.parse("2017-09-23T21:44:51Z"), 1000L, 4320L, 108, BigDecimal("0.2"))
  private val globals = Globals(
    LedgerGlobals(BigInt("45000000000000000"), 4320, Rational(1, 5), 0),
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

  private def transaction(n: Node): Tx =
    val fields = array(n)
    assertEquals(fields.size, 4)
    val valid = fields(2).value match
      case Value.Bool(v) => v
      case other         => fail(s"expected validity flag, got $other")
    val auxiliary = fields(3).value match
      case Value.Null => None
      case _          => Some(fields(3).original)
    val tx = Tx
      .fromBlock(
        Block.Transaction(fields(0).original, fields(1).original, auxiliary, valid),
        Era.Conway
      )
      .fold(e => fail(e.message), identity)
    assertEquals(tx.body.raw, fields(0).original, "retain signed body bytes")
    assertEquals(tx.witnesses.raw, Some(fields(1).original), "retain original signed witnesses")
    tx

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
  cases.foreach { id =>
    test(s"native multi-transaction LEDGERS state / typed-failure membership: $id") {
      val before = decode(item(id, "state"), UpstreamNativeStates.nativeLedgerState)
      val env = array(item(id, "environment"))
      assertEquals(env.size, 4)
      val slot = decode(env(0), summon[Codec[SlotNo]]).toBigInt
      val epoch = decode(env(1), summon[Codec[EpochNo]]).toBigInt
      assertEquals(decode(env(2), Params.conwayCodec), before.utxo.gov.curPParams)
      val account = decode(env(3), summon[Codec[ChainAccountState]])
      val signals = array(item(id, "signal"))
      val txs = signals.map(transaction)
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
      assert(txs.size > 1)
      if id != "double-spend" then assertEquals(txs.map(_.body.id).distinct.size, txs.size)
      val actual = Sts.apply(Ledgers)(
        globals,
        LedgersEnv(
          TxEnv(
            slot,
            epoch,
            0,
            geometry.firstSlot(epoch + 1),
            geometry.stabilityWindow,
            clock =
              Some(s => BigInt(geometry.systemStart.toEpochMilli) + geometry.slotOffsetMillis(s))
          ),
          account.treasury
        ),
        Counted(before, PlutusStats.zero),
        txs
      )
      id match
        case "ordered-three" | "dependent-two" =>
          assertEquals(
            Files.readString(base.resolve(s"cases/$id/result.txt")),
            "implementation-accepted\nformal-not-invoked\n"
          )
          assert(
            txs.sliding(2).exists { pair =>
              pair(1).body.inputs.exists(_.id == pair(0).body.id)
            },
            "a later transaction actually spends an earlier transaction's output"
          )
          val expected =
            decode(item(id, "implementation-state"), UpstreamNativeStates.nativeLedgerState)
          assertLedger(actual.fold(e => fail(e.message), _.state), expected)
        case _ =>
          assertEquals(
            Files.readString(base.resolve(s"cases/$id/result.txt")),
            "implementation-rejected\nformal-not-invoked\n"
          )
          val failure = actual.swap.toOption.getOrElse(fail("native-rejected batch accepted"))
          val predicate = failure match
            case LedgerFailure.Rejected.Single(_, cause) => cause
            case other => fail(s"expected a ledger refusal, got $other")
          // Native STS accumulates failures; Scarab fails fast. Its typed cause and complete
          // payload must be an independently observed native failure, regardless of ordering.
          val wire = Cbor
            .decode(PredicateFailureCbor.applyTxError(Vector(predicate)))
            .fold(e => fail(e.message), identity)
          val encoded = array(array(array(wire).head)(1)).head.value
          assert(
            array(item(id, "implementation-failure")).map(_.value).contains(encoded),
            s"Scarab failure absent from native accumulated failures: $predicate"
          )
    }
  }

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
