// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Value}
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry, LedgerGlobals, PlutusStats, Tx, TxEnv}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Globals,
  Ledger,
  LedgerEnv,
  Ledgers,
  LedgersEnv,
  NewEpoch,
  StAnnTx,
  Sts,
  Utxo,
  UtxoEnv,
  Utxow
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{EpochStateCodecs, Params, Record, UtxoCodecs, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given
import xyz.kd5ujc.scarab.storage.scls.{SclsKey, SclsRecord}

/** Native integration-hook traces retain coherent context and original transaction bytes. */
class UpstreamNativeLedgerConformanceSuite extends FunSuite:
  private enum TraceError extends DomainError:
    case MemPack(cause: xyz.kd5ujc.scarab.ledger.mempack.MemPack.Failure)

  // Ordinary state-file codecs deliberately read an empty UTxO plus an external table. The
  // upstream integration hook writes its actual map inline, using ordinary little-endian
  // MemPack keys and Conway MemPack values. Reuse those production codecs without dropping it.
  private object FullStates extends EpochStateCodecs:
    val inlineUtxoTables: Codec[UtxoTables] = new Codec[UtxoTables]:
      def decode(n: Node): Decoded[UtxoTables] =
        Wire.sortedMapOf(Codec.bytes, Codec.bytes).decode(n).flatMap { entries =>
          entries.toVector
            .traverse { (key, value) =>
              for
                decoded <- SclsKey
                  .decode(SclsRecord.Namespace.Utxo, key)
                  .leftMap(e => DecodeError.at(n, DecodeError.Kind.Domain(e)))
                input <- decoded match
                  case SclsKey.Key.Utxo(input) => Right(input)
                  case other                   => Left(Codec.mismatch(s"UTxO key ($other)", n))
                output <- TxOutMemPack
                  .decode[Conway](value)
                  .leftMap(e => DecodeError.at(n, DecodeError.Kind.Domain(TraceError.MemPack(e))))
              yield input -> output
            }
            .map(entries => UtxoTables(xyz.kd5ujc.scarab.ledger.Utxo.fromMap(entries.toMap)))
        }
      def encode(a: UtxoTables, out: Writer): Unit =
        val entries = a.utxo.iterator.toVector.map { (input, output) =>
          val key = SclsKey.encode(SclsKey.Key.Utxo(input)).fold(e => fail(e.toString), identity)
          val value = TxOutMemPack.encode[Conway](output).fold(e => fail(e.toString), identity)
          key -> value
        }
        out.mapHead(entries.size)
        entries.foreach { (key, value) =>
          out.bytes(key); out.bytes(value)
        }

    def nativeUtxoState(using ProtVer): Codec[UTxOState[Conway]] =
      Record.of[UTxOState[Conway]](
        (inlineUtxoTables, coin, coin, conwayGovState, conwayInstantStake, coin)
      )

    def nativeLedgerState(using ProtVer): Codec[LedgerState[Conway]] =
      Record.of[LedgerState[Conway]]((conwayCertState, nativeUtxoState))

    def nativeNewEpochState(using ProtVer): Codec[NewEpochState[Conway]] =
      val epoch =
        Record.of[EpochState[Conway]]((chainAccountState, nativeLedgerState, snapShots, nonMyopic))
      Record.of[NewEpochState[Conway]](
        (
          epochNo,
          blocksMade,
          blocksMade,
          epoch,
          Wire.strictMaybe(pulsingRewUpdate),
          poolDistr,
          stashedAvvm[Conway]
        )
      )

  import FullStates.given
  private val base = Path.of("node/src/test/resources/upstream/conway-native-refund/cases")
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val geometry =
    EpochGeometry(Instant.parse("2017-09-23T21:44:51Z"), 1000L, 4320L, 108, BigDecimal("0.2"))
  private val globals = Globals(
    LedgerGlobals(BigInt("45000000000000000"), 4320, xyz.kd5ujc.scarab.codec.Rational(1, 5), 0),
    geometry
  )

  private def node(id: String, name: String) =
    val path = base.resolve(s"$id/$name.cbor")
    Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(path)), Cbor.Limits(allowFloats = true))
      .fold(e => fail(s"$path: $e"), identity)

  private def decode[A](id: String, name: String, codec: Codec[A]): A =
    codec.decode(node(id, name)).fold(e => fail(s"$id/$name: ${e.render}"), identity)

  private def assertUtxo(actual: UTxOState[Conway], expected: UTxOState[Conway]): Unit =
    val found = actual.utxo.utxo
    val wanted = expected.utxo.utxo
    // Native MemPack holds every output field, but has neither our change-history bookkeeping
    // nor TxOut.raw. Compare all input/output parts before comparing the remaining typed state.
    def parts(utxo: xyz.kd5ujc.scarab.ledger.Utxo) =
      utxo.toMap.toVector
        .traverse { (input, output) =>
          TxOutMemPack.parts(output).map(input -> _)
        }
        .fold(e => fail(e.toString), _.toMap)
    assertEquals(parts(found), parts(wanted), "every UTxO entry and output field")
    assertEquals(
      found.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap,
      wanted.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap,
      "all decoded output fields and caches except original encoding"
    )
    assertEquals(
      (found.count, found.coin, found.stake, found.ptrStake),
      (wanted.count, wanted.coin, wanted.stake, wanted.ptrStake),
      "derived UTxO accounting"
    )
    assertEquals(Diff[UTxOState[Conway]].diff(actual.copy(utxo = expected.utxo), expected), None)

  private def assertLedger(actual: LedgerState[Conway], expected: LedgerState[Conway]): Unit =
    assertUtxo(actual.utxo, expected.utxo)
    assertEquals(
      Diff[LedgerState[Conway]].diff(actual.copy[Conway](utxo = expected.utxo), expected),
      None
    )

  private def transaction(n: Node): Tx =
    val parts = n.value match
      case Value.Arr(xs) if xs.size == 4 => xs
      case other => fail(s"expected complete original transaction, got $other")
    val valid = parts(2).value match
      case Value.Bool(v) => v
      case other         => fail(s"expected transaction validity flag, got $other")
    val auxiliary = parts(3).value match
      case Value.Null => None
      case _          => Some(parts(3).original)
    Tx.fromBlock(
      Block.Transaction(parts(0).original, parts(1).original, auxiliary, valid),
      Era.Conway
    ).fold(e => fail(e.message), identity)

  private def txEnv(slot: BigInt, epoch: BigInt, index: Int = 0): TxEnv =
    TxEnv(
      slot,
      epoch,
      0,
      geometry.firstSlot(epoch + 1),
      geometry.stabilityWindow,
      txIndex = index,
      clock = Some(s => BigInt(geometry.systemStart.toEpochMilli) + geometry.slotOffsetMillis(s))
    )

  private def paymentContext: Vector[Node] = node("LEDGER-2", "context").value match
    case Value.Arr(xs) if xs.size == 3 => xs
    case other                         => fail(s"expected original payment context, got $other")

  private def paymentUtxoState(name: String): Node = node("LEDGER-2", name).value match
    case Value.Arr(Vector(_, utxo)) => utxo
    case other                      => fail(s"expected original payment ledger state, got $other")

  Vector("UTXO-3" -> Utxo, "UTXOW-4" -> Utxow).foreach { (id, rule) =>
    test(s"genuine supplemental $id matches the complete native UTxO state") {
      assertEquals(node(id, "context").original, paymentContext(2).original)
      assertEquals(node(id, "state").original, paymentUtxoState("state").original)
      assertEquals(
        node(id, "implementation-state").original,
        paymentUtxoState("implementation-state").original
      )
      val before = decode(id, "state", FullStates.nativeUtxoState)
      val expected = decode(id, "implementation-state", FullStates.nativeUtxoState)
      val env = node(id, "environment").value match
        case Value.Arr(xs) if xs.size == 3 => xs
        case other                         => fail(s"expected native UTxO environment, got $other")
      val slot = summon[Codec[SlotNo]].decode(env(0)).fold(e => fail(e.render), identity)
      val params = Params.conwayCodec.decode(env(1)).fold(e => fail(e.render), identity)
      val cert = summon[Codec[ConwayCertState]].decode(env(2)).fold(e => fail(e.render), identity)
      val tx = transaction(node(id, "signal"))
      assert(tx.body.certificates.isEmpty && tx.body.proposals.isEmpty)
      assertEquals(node(id, "signal").original, node("LEDGER-2", "signal").original)
      val actual = Sts
        .apply(rule)(
          globals,
          UtxoEnv(txEnv(slot.toBigInt, geometry.epochOf(slot.toBigInt)), params, cert),
          Counted(before, PlutusStats.zero),
          StAnnTx(tx, before.utxo.utxo)
        )
        .fold(e => fail(e.message), identity)
      assertUtxo(actual.state, expected)
    }
  }

  test("native LEDGERS matches while its upstream formal process failure remains explicit") {
    val id = "LEDGERS-5"
    assertEquals(node(id, "context").original, paymentContext(1).original)
    assertEquals(node(id, "state").original, node("LEDGER-2", "state").original)
    assertEquals(
      node(id, "implementation-state").original,
      node("LEDGER-2", "implementation-state").original
    )
    assertEquals(
      Files.readString(base.resolve(s"$id/result.txt")),
      "implementation-accepted\nagda-not-yet-observed\n"
    )
    val before = decode(id, "state", FullStates.nativeLedgerState)
    val expected = decode(id, "implementation-state", FullStates.nativeLedgerState)
    val env = node(id, "environment").value match
      case Value.Arr(xs) if xs.size == 4 => xs
      case other => fail(s"expected full native LEDGERS environment, got $other")
    val slot = summon[Codec[SlotNo]].decode(env(0)).fold(e => fail(e.render), identity)
    val epoch = summon[Codec[EpochNo]].decode(env(1)).fold(e => fail(e.render), identity)
    val params = Params.conwayCodec.decode(env(2)).fold(e => fail(e.render), identity)
    assertEquals(params, before.utxo.gov.curPParams)
    val account =
      summon[Codec[ChainAccountState]].decode(env(3)).fold(e => fail(e.render), identity)
    val txs = node(id, "signal").value match
      case Value.Arr(Vector(tx)) =>
        assertEquals(tx.original, node("LEDGER-2", "signal").original)
        Vector(transaction(tx))
      case other => fail(s"expected original singleton payment batch, got $other")
    val actual = Sts
      .apply(Ledgers)(
        globals,
        LedgersEnv(txEnv(slot.toBigInt, epoch.toBigInt), account.treasury),
        Counted(before, PlutusStats.zero),
        txs
      )
      .fold(e => fail(e.message), identity)
    assertLedger(actual.state, expected)
  }

  test("every original native hook file and formal verdict is retained") {
    val manifest = parse(Files.readString(base.getParent.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[Int]("upstreamExitCode").toOption, Some(0))
    val rows = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    val ids = rows.map(_.hcursor.get[String]("id").fold(e => fail(e.message), identity))
    assertEquals(
      ids.toSet,
      Set("LEDGER-1", "LEDGER-2", "LEDGER-3", "NEWEPOCH-4", "NEWEPOCH-5", "NEWEPOCH-6")
    )
    assertEquals(rows.size, ids.toSet.size)
    rows.zip(ids).foreach { (row, id) =>
      val files = row.hcursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      assertEquals(
        files.keySet,
        Set(
          "context.cbor",
          "environment.cbor",
          "state.cbor",
          "signal.cbor",
          "implementation-state.cbor",
          "result.txt"
        )
      )
      files.foreach { (name, descriptor) =>
        val bytes = Files.readAllBytes(base.resolve(s"$id/$name"))
        val sha = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha), s"$id/$name")
        assertEquals(
          descriptor.hcursor.get[Int]("bytes").toOption,
          Some(bytes.length),
          s"$id/$name"
        )
      }
      assertEquals(
        Files.readString(base.resolve(s"$id/result.txt")),
        "implementation-accepted\nagda-accepted\noutputs-equal\n"
      )
    }
  }

  test("supplemental native judgments retain every byte and the formal process defect") {
    val manifest = parse(Files.readString(base.getParent.resolve("derived-manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[Int]("runnerExitCode").toOption, Some(139))
    assertEquals(manifest.get[Int]("newNativeJudgments").toOption, Some(3))
    val rows = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    val ids = rows.map(_.hcursor.get[String]("id").fold(e => fail(e.message), identity))
    assertEquals(ids.toSet, Set("LEDGER-1", "LEDGER-2", "UTXO-3", "UTXOW-4", "LEDGERS-5"))
    assertEquals(rows.size, 5)
    rows.zip(ids).foreach { (row, id) =>
      val files = row.hcursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      assertEquals(
        files.keySet,
        Set(
          "context.cbor",
          "environment.cbor",
          "state.cbor",
          "signal.cbor",
          "implementation-state.cbor",
          "result.txt"
        )
      )
      files.foreach { (name, descriptor) =>
        val bytes = Files.readAllBytes(base.resolve(s"$id/$name"))
        val sha = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha), s"$id/$name")
        assertEquals(
          descriptor.hcursor.get[Int]("bytes").toOption,
          Some(bytes.length),
          s"$id/$name"
        )
      }
    }
    Vector("UTXO-3", "UTXOW-4").foreach { id =>
      assertEquals(
        Files.readString(base.resolve(s"$id/result.txt")),
        "implementation-accepted\nagda-accepted\noutputs-equal\n"
      )
    }
  }

  (4 to 6).foreach { ordinal =>
    test(s"actual NEWEPOCH-$ordinal boundary matches complete native state") {
      val id = s"NEWEPOCH-$ordinal"
      assertEquals(node(id, "environment").value, Value.Null)
      assertEquals(node(id, "context").value, Value.Null)
      val codec = FullStates.nativeNewEpochState
      val before = decode(id, "state", codec)
      val expected = decode(id, "implementation-state", codec)
      val epoch = decode(id, "signal", summon[Codec[EpochNo]])
      assert(before.epoch.next.contains(epoch))
      val actual = Sts
        .apply(NewEpoch.sts[Conway])(globals, (), before, epoch)
        .fold(e => fail(s"native-accepted $id refused: $e"), identity)
      assertEquals(Diff[NewEpochState[Conway]].diff(actual, expected), None)
    }
  }

  (1 to 3).foreach { ordinal =>
    test(s"actual LEDGER-$ordinal transaction matches complete native state") {
      val id = s"LEDGER-$ordinal"
      val codec = FullStates.nativeLedgerState
      val before = decode(id, "state", codec)
      val expected = decode(id, "implementation-state", codec)
      val fields = node(id, "environment").value match
        case Value.Arr(xs) if xs.size == 5 => xs
        case other => fail(s"expected all five native LEDGER environment fields, got $other")
      val slot = summon[Codec[SlotNo]].decode(fields(0)).fold(e => fail(e.render), identity)
      val epoch = Wire
        .strictMaybe(summon[Codec[EpochNo]])
        .decode(fields(1))
        .fold(e => fail(e.render), identity)
        .map(_.toBigInt)
        .getOrElse(geometry.epochOf(slot.toBigInt))
      val index = Wire.word16.decode(fields(2)).fold(e => fail(e.render), identity)
      val params = Params.conwayCodec.decode(fields(3)).fold(e => fail(e.render), identity)
      assertEquals(params, before.utxo.gov.curPParams)
      val account = summon[Codec[ChainAccountState]]
        .decode(fields(4))
        .fold(e => fail(e.render), identity)
      val parts = node(id, "signal").value match
        case Value.Arr(xs) if xs.size == 4 => xs
        case other => fail(s"expected full original transaction signal, got $other")
      val valid = parts(2).value match
        case Value.Bool(v) => v
        case other         => fail(s"expected transaction validity flag, got $other")
      val auxiliary = parts(3).value match
        case Value.Null => None
        case _          => Some(parts(3).original)
      val tx = Tx
        .fromBlock(
          Block.Transaction(parts(0).original, parts(1).original, auxiliary, valid),
          Era.Conway
        )
        .fold(e => fail(e.message), identity)
      val context = node(id, "context").value match
        case Value.Arr(Vector(policy, enact, utxo)) => (policy, enact, utxo)
        case other => fail(s"expected full native LEDGER execution context, got $other")
      val policy =
        Wire.strictMaybe(Codec.bytes).decode(context._1).fold(e => fail(e.render), identity)
      assertEquals(policy, before.utxo.gov.constitution.script)
      val enact = summon[Codec[xyz.kd5ujc.scarab.ledger.EnactState]]
        .decode(context._2)
        .fold(e => fail(e.render), identity)
      val gov = before.utxo.gov
      assertEquals(
        enact,
        xyz.kd5ujc.scarab.ledger.EnactState(
          gov.committee,
          gov.constitution,
          gov.curPParams,
          gov.prevPParams,
          0,
          Map.empty,
          gov.proposals.roots
        )
      )
      val utxoContext = context._3.value match
        case Value.Arr(Vector(originalTx, utxo, env)) => (originalTx, utxo, env)
        case other => fail(s"expected original UTxO execution context, got $other")
      assertEquals(utxoContext._1.original, node(id, "signal").original)
      val contextUtxo = UtxoCodecs.utxo
        .decode(utxoContext._2)
        .fold(e => fail(e.render), identity)
      assertEquals(
        contextUtxo.view.mapValues(_.copy(raw = Bytes.empty)).toMap,
        before.utxo.utxo.utxo.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap
      )
      val utxoEnv = utxoContext._3.value match
        case Value.Arr(xs) if xs.size == 3 => xs
        case other                         => fail(s"expected full UTxO environment, got $other")
      assertEquals(utxoEnv(0).original, fields(0).original)
      assertEquals(
        Params.conwayCodec.decode(utxoEnv(1)).fold(e => fail(e.render), identity),
        params
      )
      assertEquals(
        summon[Codec[ConwayCertState]]
          .decode(utxoEnv(2))
          .fold(e => fail(e.render), identity),
        before.cert
      )
      val ctx = TxEnv(
        slot.toBigInt,
        epoch,
        0,
        geometry.firstSlot(epoch + 1),
        geometry.stabilityWindow,
        txIndex = index.toInt,
        clock = Some(s => BigInt(geometry.systemStart.toEpochMilli) + geometry.slotOffsetMillis(s))
      )
      val actual = Sts
        .apply(Ledger)(
          globals,
          LedgerEnv(ctx, account.treasury),
          Counted(before, PlutusStats.zero),
          tx
        )
        .fold(e => fail(e.message), identity)
      assertLedger(actual.state, expected)
    }
  }
