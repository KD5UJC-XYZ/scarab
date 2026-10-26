// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import scala.collection.immutable.SortedMap
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Globals,
  InstantStakeOps,
  JudgedSts,
  Ledger,
  LedgerEnv,
  Ledgers,
  LedgersEnv,
  StAnnTx,
  Sts,
  Utxo,
  UtxoEnv,
  Utxow
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, Node}
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{Params, Wire}

/** Actual distinct STS judgments with original signed bytes. Accepted cases compare native state;
  * rejected cases establish typed failure membership, not accumulated-list or ordering equality.
  */
class UpstreamFreshLedgerConformanceSuite extends FunSuite:
  import UpstreamNativeStates.given
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val base = Path.of("node/src/test/resources/upstream/conway-pv11-generators")
  private val geometry =
    EpochGeometry(Instant.parse("2017-09-23T21:44:51Z"), 1000L, 4320L, 108, BigDecimal("0.2"))
  private val globals = Globals(
    LedgerGlobals(BigInt("45000000000000000"), 4320, Rational(1, 5), 0),
    geometry
  )
  private val manifest = parse(Files.readString(base.resolve("manifest.json")))
    .fold(e => fail(e.message), identity)
  private val cases =
    manifest.hcursor.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
  private def text(row: Json, key: String): String =
    row.hcursor.get[String](key).fold(e => fail(e.message), identity)
  private def array(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"expected array, got $other")
  private def nodeAt(p: Path): Node =
    Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(p)), Cbor.Limits(allowFloats = true))
      .fold(e => fail(s"$p: ${e.message}"), identity)
  private def node(id: String, name: String): Node =
    nodeAt(base.resolve(s"cases/$id/$name.cbor"))

  private def inputDigest(rule: String, directory: Path): String =
    val fingerprint = MessageDigest.getInstance("SHA-256")
    fingerprint.update((rule + "\u0000").getBytes(java.nio.charset.StandardCharsets.UTF_8))
    Vector("context", "environment", "state", "signal").foreach { part =>
      fingerprint.update(Files.readAllBytes(directory.resolve(s"$part.cbor")))
    }
    fingerprint.digest().map(b => f"${b & 255}%02x").mkString

  private def decode[A](n: Node, c: Codec[A]): A = c.decode(n).fold(e => fail(e.render), identity)
  private def tx(n: Node): Tx =
    val fs = array(n)
    assertEquals(fs.size, 4)
    val valid = fs(2).value match
      case Value.Bool(v) => v
      case other         => fail(s"expected validity flag, got $other")
    val auxiliary = fs(3).value match
      case Value.Null => None
      case _          => Some(fs(3).original)
    val result = Tx
      .fromBlock(Block.Transaction(fs(0).original, fs(1).original, auxiliary, valid), Era.Conway)
      .fold(e => fail(e.message), identity)
    assertEquals(result.body.raw, fs(0).original)
    assertEquals(result.witnesses.raw, Some(fs(1).original))
    result
  private def env(slot: BigInt, epoch: BigInt, index: Int = 0): TxEnv = TxEnv(
    slot,
    epoch,
    0,
    geometry.firstSlot(epoch + 1),
    geometry.stabilityWindow,
    txIndex = index,
    clock = Some(
      xyz.kd5ujc.scarab.ledger.PosixClock.Total(s =>
        BigInt(geometry.systemStart.toEpochMilli) + geometry.slotOffsetMillis(s)
      )
    )
  )
  private def sameUtxo(found: UTxOState[Conway], wanted: UTxOState[Conway]): Unit =
    def parts(s: UTxOState[Conway]) = s.utxo.utxo.toMap.toVector
      .traverse { (input, output) => TxOutMemPack.parts(output).map(input -> _) }
      .fold(e => fail(e.toString), _.toMap)
    assertEquals(parts(found), parts(wanted), "all native UTxO entries and MemPack fields")
    val a = found.utxo.utxo
    val b = wanted.utxo.utxo
    assertEquals(
      a.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap,
      b.toMap.view.mapValues(_.copy(raw = Bytes.empty)).toMap
    )
    assertEquals((a.count, a.coin, a.stake, a.ptrStake), (b.count, b.coin, b.stake, b.ptrStake))
    assertEquals(Diff[UTxOState[Conway]].diff(found.copy(utxo = wanted.utxo), wanted), None)
  private def sameLedger(found: LedgerState[Conway], wanted: LedgerState[Conway]): Unit =
    sameUtxo(found.utxo, wanted.utxo)
    assertEquals(
      Diff[LedgerState[Conway]].diff(found.copy[Conway](utxo = wanted.utxo), wanted),
      None
    )

  /** One complete typed Scala cause must occur in the native failure list; this does not compare
    * the accumulated list or its order.
    */
  private def rejection(rule: String, e: LedgerFailure, failures: Node): Unit =
    val predicate = e match
      case LedgerFailure.Rejected.Single(_, cause) => cause
      case other => fail(s"expected typed native refusal, got $other")
    val answer = Cbor
      .decode(PredicateFailureCbor.applyTxError(Vector(predicate)))
      .fold(e => fail(e.message), identity)
    val ledgerFailure = array(array(array(answer).head)(1)).head
    val encoded = rule match
      case "UTXO"    => array(array(ledgerFailure)(1))(1)
      case "UTXOW"   => array(ledgerFailure)(1)
      case "LEDGERS" => ledgerFailure
      case "LEDGER"  => ledgerFailure
      case other     => fail(s"unsupported native STS rule $other")
    assert(
      array(failures).exists(_.value == encoded.value),
      s"complete typed Scarab failure absent from native accumulated failures: $predicate"
    )

  private def cacheInvariant(state: UTxOState[Conway], incoherent: Boolean): Unit =
    val empty = ConwayInstantStake(
      SortedMap.empty(using
        summon[cats.Order[xyz.kd5ujc.scarab.ledger.state.Credential[Staking]]].toOrdering
      )
    )
    val computed = InstantStakeOps
      .update(empty, state.utxo.utxo.toMap.values, Vector.empty)
      .fold(e => fail(e.toString), identity)
    if incoherent then
      assertNotEquals(
        state.instantStake,
        computed,
        "original generated cache differs from the UTxO-derived cache"
      )
    else
      assertEquals(
        state.instantStake,
        computed,
        "native smartUTxOState cache matches the complete original UTxO"
      )

  private def replay(row: Json, directory: Path, incoherent: Boolean): Unit =
    def read(name: String): Node = nodeAt(directory.resolve(s"$name.cbor"))
    val rule = text(row, "rule")
    val fields = array(read("environment"))
    val result = text(row, "native")
    assertEquals(Files.readString(directory.resolve("result.txt")), text(row, "result"))
    if rule == "UTXO" || rule == "UTXOW" then
      assertEquals(fields.size, 3)
      val slot = decode(fields(0), summon[Codec[SlotNo]]).toBigInt
      val pp = decode(fields(1), Params.conwayCodec)
      val cert = decode(fields(2), summon[Codec[ConwayCertState]])
      val before = decode(read("state"), UpstreamNativeStates.nativeUtxoState)
      cacheInvariant(before, incoherent)
      val transaction = tx(read("signal"))
      val execution = UtxoEnv(env(slot, geometry.epochOf(slot)), pp, cert)
      val signal = StAnnTx(transaction, before.utxo.utxo)
      val actual =
        if rule == "UTXO" then
          JudgedSts.apply(Utxo)(LedgerFailure.Rejected(signal.id, _))(
            globals,
            execution,
            Counted(before, PlutusStats.zero),
            signal
          )
        else
          JudgedSts.apply(Utxow)(LedgerFailure.Rejected(signal.id, _))(
            globals,
            execution,
            Counted(before, PlutusStats.zero),
            signal
          )
      result match
        case "accepted" =>
          sameUtxo(
            actual.fold(e => fail(e.message), _.state),
            decode(read("implementation-state"), UpstreamNativeStates.nativeUtxoState)
          )
        case "rejected" =>
          rejection(
            rule,
            actual.swap.toOption.getOrElse(fail("native rejection accepted")),
            read("implementation-failures")
          )
        case other => fail(s"unknown observation $other")
    else
      val before = decode(read("state"), UpstreamNativeStates.nativeLedgerState)
      cacheInvariant(before.utxo, incoherent)
      val slot = decode(fields(0), summon[Codec[SlotNo]]).toBigInt
      val (epoch, index, pp, account) = if rule == "LEDGERS" then
        assertEquals(fields.size, 4)
        (
          decode(fields(1), summon[Codec[EpochNo]]).toBigInt,
          0,
          decode(fields(2), Params.conwayCodec),
          decode(fields(3), summon[Codec[ChainAccountState]])
        )
      else
        assertEquals(fields.size, 5)
        val epoch = array(fields(1)).headOption.fold(geometry.epochOf(slot))(n =>
          decode(n, summon[Codec[EpochNo]]).toBigInt
        )
        (
          epoch,
          decode(fields(2), Wire.word16).toInt,
          decode(fields(3), Params.conwayCodec),
          decode(fields(4), summon[Codec[ChainAccountState]])
        )
      assertEquals(pp, before.utxo.gov.curPParams)
      val actual =
        if rule == "LEDGERS" then
          Sts.apply(Ledgers)(
            globals,
            LedgersEnv(env(slot, epoch), account.treasury),
            Counted(before, PlutusStats.zero),
            array(read("signal")).map(tx)
          )
        else
          JudgedSts.apply(Ledger)(LedgerFailure.Rejected(Bytes.empty, _))(
            globals,
            LedgerEnv(env(slot, epoch, index), account.treasury),
            Counted(before, PlutusStats.zero),
            tx(read("signal"))
          )
      result match
        case "accepted" =>
          sameLedger(
            actual.fold(e => fail(e.message), _.state),
            decode(read("implementation-state"), UpstreamNativeStates.nativeLedgerState)
          )
        case "rejected" =>
          rejection(
            rule,
            actual.swap.toOption.getOrElse(fail("native rejection accepted")),
            read("implementation-failures")
          )
        case other => fail(s"unknown observation $other")

  cases.foreach { row =>
    val id = text(row, "id")
    val rule = text(row, "rule")
    val scope =
      if text(row, "native") == "rejected" then "typed native refusal membership"
      else "native accepted-state replay"
    test(s"$rule $scope: $id") {
      replay(row, base.resolve(s"cases/$id"), text(row, "replayScope") == "incoherent-cache")
    }
  }

  private val originalInputs = manifest.hcursor
    .get[Vector[Json]]("originalGeneratedArtifacts")
    .fold(e => fail(e.message), identity)
  private val originalRefusalIds = Set("ledger-274101") ++
    (274101 to 274107).map(seed => s"ledgers-$seed")
  private val originalRefusals = originalInputs.filter(row => originalRefusalIds(text(row, "id")))
  private def originalRefusal(row: Json): Json =
    val id = text(row, "id")
    cases.filter(one => text(one, "id") == id) match
      case Vector(one) =>
        one.mapObject(
          _.add("native", Json.fromString("rejected"))
            .add(
              "result",
              Json.fromString("implementation-rejected\nagda-rejected\noutputs-differ\n")
            )
        )
      case other => fail(s"expected one native rule binding for $id, found ${other.size}")

  originalRefusals.foreach { row =>
    val id = text(row, "id")
    test(s"original generated-cache native refusal membership: $id") {
      replay(originalRefusal(row), base.resolve(s"original-generated/$id"), incoherent = true)
    }
  }

  test("all seventeen original generated states execute with their original native outcomes") {
    val ids = originalInputs.map(row => text(row, "id"))
    assertEquals(ids.size, 17)
    assertEquals(ids.distinct.size, 17)
    assertEquals(originalRefusals.size, 8)
    assertEquals(originalRefusals.map(row => text(row, "id")).toSet, originalRefusalIds)
    val acceptedIds = cases
      .filter(row => text(row, "replayScope") == "incoherent-cache")
      .map(row => text(row, "id").stripPrefix("original-incoherent-"))
      .toSet
    assertEquals(acceptedIds.size, 9)
    assertEquals(acceptedIds ++ originalRefusalIds, ids.toSet)
    val originalTuples = originalRefusals.map { row =>
      val id = text(row, "id")
      inputDigest(text(originalRefusal(row), "rule"), base.resolve(s"original-generated/$id"))
    }
    val allTuples = cases.map(row => text(row, "nativeStsTupleSha256")) ++ originalTuples
    assertEquals(allTuples.size, 58)
    assertEquals(allTuples.distinct.size, 56)
  }

  test("native-only standalone UTXO refuses missing collateral without redeemers") {
    val supplement = Path.of("node/src/test/resources/upstream/conway-utxo-missing-collateral")
    val provenance = parse(Files.readString(supplement.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
    val files = provenance.hcursor
      .get[Map[String, Json]]("files")
      .fold(e => fail(e.message), identity)
    files.foreach { (name, binding) =>
      val bytes = Files.readAllBytes(supplement.resolve(name))
      assertEquals(
        bytes.length.toLong,
        binding.hcursor.get[Long]("bytes").fold(e => fail(e.message), identity)
      )
      val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
      assertEquals(hash, text(binding, "sha256"))
    }
    def observed(name: String): Node =
      Cbor
        .decode(Bytes.fromArray(Files.readAllBytes(supplement.resolve(s"$name.cbor"))))
        .fold(e => fail(e.message), identity)
    val fields = array(observed("environment"))
    val slot = decode(fields(0), summon[Codec[SlotNo]]).toBigInt
    val params = decode(fields(1), Params.conwayCodec)
    val cert = decode(fields(2), summon[Codec[ConwayCertState]])
    val before = decode(observed("state"), UpstreamNativeStates.nativeUtxoState)
    cacheInvariant(before, incoherent = false)
    val original = tx(observed("original-signal"))
    val transaction = tx(observed("signal"))
    assertEquals(
      transaction.body.copy(raw = original.body.raw, collateral = original.body.collateral),
      original.body
    )
    assertEquals(transaction.witnesses, original.witnesses)
    assertEquals(original.witnesses.redeemers, Vector.empty)
    assertEquals(transaction.witnesses.redeemers, Vector.empty)
    assertEquals(transaction.body.collateral.size, 1)
    assert(transaction.body.collateral.forall(i => !before.utxo.utxo.contains(i)))
    assertEquals(observed("environment").original, node("utxo-274101", "environment").original)
    assertEquals(observed("state").original, node("utxo-274101", "state").original)
    assertEquals(observed("original-signal").original, node("utxo-274101", "signal").original)
    val signal = StAnnTx(transaction, before.utxo.utxo)
    val actual = JudgedSts.apply(Utxo)(LedgerFailure.Rejected(signal.id, _))(
      globals,
      UtxoEnv(env(slot, geometry.epochOf(slot)), params, cert),
      Counted(before, PlutusStats.zero),
      signal
    )
    val cause = PredicateFailure.utxo(UtxoFailure.BadInputs(transaction.body.collateral.toSet))
    assertEquals(actual.swap.toOption, Some(LedgerFailure.Rejected(signal.id, cause)))
    val encoded = Cbor
      .decode(PredicateFailureCbor.applyTxError(Vector(cause)))
      .fold(e => fail(e.message), identity)
    val ledgerFailure = array(array(array(encoded).head)(1)).head
    val utxoFailure = array(array(ledgerFailure)(1))(1)
    assertEquals(array(observed("native-failures")).map(_.value), Vector(utxoFailure.value))
  }
  test("every original input, native output and independent formal verdict is pinned") {
    cases.foreach { row =>
      val id = text(row, "id")
      val tupleHash = inputDigest(text(row, "rule"), base.resolve(s"cases/$id"))
      assertEquals(text(row, "nativeStsTupleSha256"), tupleHash)
      val files = row.hcursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      files.foreach { (name, description) =>
        val data = Files.readAllBytes(base.resolve(s"cases/$id/$name"))
        val hash =
          MessageDigest.getInstance("SHA-256").digest(data).map(b => f"${b & 255}%02x").mkString
        assertEquals(description.hcursor.get[String]("sha256").toOption, Some(hash), s"$id/$name")
        assertEquals(
          description.hcursor.get[Int]("bytes").toOption,
          Some(data.length),
          s"$id/$name"
        )
      }
    }
    val originalGenerated = manifest.hcursor
      .get[Vector[Json]]("originalGeneratedArtifacts")
      .fold(e => fail(e.message), identity)
    assertEquals(originalGenerated.size, 17)
    originalGenerated.foreach { row =>
      val id = text(row, "id")
      val files = row.hcursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      files.foreach { (name, descriptor) =>
        val data = Files.readAllBytes(base.resolve(s"original-generated/$id/$name"))
        val hash =
          MessageDigest.getInstance("SHA-256").digest(data).map(b => f"${b & 255}%02x").mkString
        assertEquals(
          descriptor.hcursor.get[String]("sha256").toOption,
          Some(hash),
          s"original $id/$name"
        )
        assertEquals(
          descriptor.hcursor.get[Int]("bytes").toOption,
          Some(data.length),
          s"original $id/$name"
        )
      }
    }
  }

  test("nine original cache regressions retain native accepts and historical underflow evidence") {
    val originals = cases.filter(row => text(row, "replayScope") == "incoherent-cache")
    assertEquals(originals.size, 9)
    assertEquals(originals.map(row => text(row, "nativeStsTupleSha256")).distinct.size, 9)
    assertEquals(originals.count(row => text(row, "native") == "accepted"), 9)
    assertEquals(cases.map(row => text(row, "nativeStsTupleSha256")).distinct.size, 48)
    originals.foreach { row =>
      assert(row.hcursor.get[BigInt]("expectedUnderflow").exists(_ < 0))
      assertEquals(text(row, "result"), "implementation-accepted\nagda-accepted\noutputs-equal\n")
    }
    val historical = manifest.hcursor.downField("originalIncoherentScalaReplay")
    assertEquals(historical.get[Int]("failed"), Right(9))
    assertEquals(historical.get[Int]("passed"), Right(28))
  }

  test("unique lawful coverage excludes repeated hooks and retains the original formal failure") {
    val lawful = cases.filter(row => text(row, "replayScope") == "lawful-native")
    val unique = lawful.groupBy(row => text(row, "nativeStsTupleSha256"))
    assertEquals(lawful.size, 41)
    assertEquals(unique.size, 39)
    assertEquals(unique.values.count(rows => text(rows.head, "native") == "accepted"), 28)
    assertEquals(unique.values.count(rows => text(rows.head, "native") == "rejected"), 11)
    Vector("context", "environment", "state", "signal", "implementation-state").foreach { name =>
      assertEquals(
        Bytes.fromArray(Files.readAllBytes(base.resolve(s"original-formal-overlap/$name.cbor"))),
        Bytes.fromArray(
          Files.readAllBytes(base.resolve(s"cases/original-incoherent-utxow-274103/$name.cbor"))
        )
      )
    }
    assertEquals(
      Files.readString(base.resolve("original-formal-overlap/result.txt")),
      "implementation-accepted\nagda-rejected\noutputs-differ\n"
    )
    assertEquals(
      Files.readString(base.resolve("cases/original-incoherent-utxow-274103/result.txt")),
      "implementation-accepted\nagda-accepted\noutputs-equal\n"
    )
  }
