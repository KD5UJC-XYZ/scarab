// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
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
import xyz.kd5ujc.scarab.ledger.rules.{Globals, InstantStakeOps}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, Node}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{Params, Wire}

/** Actual distinct STS judgments with original signed bytes. Accepted cases compare native state;
  * rejected cases compare complete accumulated failure bytes in native order.
  */
class UpstreamFreshLedgerConformanceSuite extends FunSuite:
  import UpstreamNativeStates.given
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val base = Path.of("node/src/test/resources/upstream/conway-pv11-generators")
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

  /** Compare the rule's failures, embedded as LEDGER's, written by the era's encoder
    * (`UpstreamEraLedger.refusal`), with the unchanged native encoding of the rule's own, byte for
    * byte as written, never re-encoded: LEDGER's and LEDGERS's list whole, and UTXO's and UTXOW's
    * failures each as LEDGER's embeds it, in order and with their multiplicity.
    */
  private def rejected(
      rule: String,
      failures: Vector[xyz.kd5ujc.scarab.ledger.era.rules.LedgerPredFailure[Conway]],
      native: Node
  ): Unit =
    val answer = Cbor
      .decode(UpstreamEraLedger.refusal(failures))
      .fold(e => fail(e.message), identity)
    val list = array(array(answer).head)(1)
    val written = array(list).map { ledgerFailure =>
      rule match
        case "UTXO"    => array(array(ledgerFailure)(1))(1)
        case "UTXOW"   => array(ledgerFailure)(1)
        case "LEDGERS" => ledgerFailure
        case "LEDGER"  => ledgerFailure
        case other     => fail(s"unsupported native STS rule $other")
    }
    assertEquals(
      written.map(_.original.hex),
      array(native).map(_.original.hex),
      "each native failure's bytes, in order"
    )
    if rule == "LEDGER" || rule == "LEDGERS" then
      assertEquals(list.original.hex, native.original.hex, "the complete native failure list")

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

  /** The era-typed UTXO or UTXOW on a UTXO or UTXOW row's inputs, the transaction read by Conway's
    * decoder from its original parts, the root of its judgment as the hook ran it: an accepted
    * judgment leaves the reference's post-state, a refused one's failures encode to its bytes in
    * their order.
    */
  private def eraUtxoReplay(row: Json, directory: Path, incoherent: Boolean): Unit =
    def read(name: String): Node = nodeAt(directory.resolve(s"$name.cbor"))
    val rule = text(row, "rule")
    val fields = array(read("environment"))
    assertEquals(Files.readString(directory.resolve("result.txt")), text(row, "result"))
    assertEquals(fields.size, 3)
    val slot = decode(fields(0), summon[Codec[SlotNo]]).toBigInt
    val pp = decode(fields(1), Params.conwayCodec)
    val cert = decode(fields(2), summon[Codec[ConwayCertState]])
    val before = decode(read("state"), UpstreamNativeStates.nativeUtxoState)
    cacheInvariant(before, incoherent)
    val transaction =
      UpstreamEraLedger
        .transaction(read("signal"))
        .fold(e => fail(s"era transaction: $e"), identity)
    // the original parts as signed, retained whole by Conway's decoder
    assertEquals(array(read("signal")).size, 4)
    assert(UpstreamEraLedger.retains(read("signal"), transaction), "retain the signed parts")
    val judged = rule match
      case "UTXO" =>
        UpstreamEraLedger
          .utxo(globals, slot, pp, cert, before, transaction)
          .fold(e => fail(s"era inputs: $e"), identity)
          .fold(e => fail(s"era defect: $e"), identity)
          .leftMap(fs => UpstreamEraLedger.ofUtxo(fs.toChain.toVector))
      case "UTXOW" =>
        UpstreamEraLedger
          .utxow(globals, slot, pp, cert, before, transaction)
          .fold(e => fail(s"era inputs: $e"), identity)
          .fold(e => fail(s"era defect: $e"), identity)
          .leftMap(fs => UpstreamEraLedger.ofUtxow(fs.toChain.toVector))
      case other => fail(s"not a UTXO or UTXOW row: $other")
    (text(row, "native"), judged) match
      case ("accepted", Right(state)) =>
        sameUtxo(
          state,
          decode(read("implementation-state"), UpstreamNativeStates.nativeUtxoState)
        )
      case ("rejected", Left(failures)) =>
        rejected(rule, failures, read("implementation-failures"))
      case (native, other) => fail(s"native $native, era $other")

  /** The era-typed LEDGER or LEDGERS on a LEDGER or LEDGERS row's inputs, each transaction read by
    * Conway's decoder from its original parts, the root of its judgment as the hook ran it: an
    * accepted judgment leaves the reference's post-state, a refused one's failures encode to its
    * bytes in their order. The era judgment's outcome is returned.
    */
  private def eraReplay(
      row: Json,
      directory: Path,
      incoherent: Boolean
  ): UpstreamEraLedger.Outcome =
    def read(name: String): Node = nodeAt(directory.resolve(s"$name.cbor"))
    def eraTx(n: Node) =
      val read = UpstreamEraLedger.transaction(n).fold(e => fail(s"era transaction: $e"), identity)
      // the original parts as signed, retained whole by Conway's decoder
      assertEquals(array(n).size, 4)
      assert(UpstreamEraLedger.retains(n, read), "retain the signed body and witnesses")
      read
    val rule = text(row, "rule")
    val fields = array(read("environment"))
    assertEquals(Files.readString(directory.resolve("result.txt")), text(row, "result"))
    val before = decode(read("state"), UpstreamNativeStates.nativeLedgerState)
    cacheInvariant(before.utxo, incoherent)
    val slot = decode(fields(0), summon[Codec[SlotNo]]).toBigInt
    val judged = rule match
      case "LEDGERS" =>
        assertEquals(fields.size, 4)
        val txs = array(read("signal")).map(eraTx)
        UpstreamEraLedger
          .ledgers(
            globals,
            slot,
            decode(fields(1), summon[Codec[EpochNo]]),
            decode(fields(2), Params.conwayCodec),
            decode(fields(3), summon[Codec[ChainAccountState]]),
            before,
            txs
          )
          .map(UpstreamEraLedger.ofLedgers)
      case "LEDGER" =>
        assertEquals(fields.size, 5)
        val tx = eraTx(read("signal"))
        UpstreamEraLedger
          .ledger(
            globals,
            slot,
            array(fields(1)).headOption.map(n => decode(n, summon[Codec[EpochNo]])),
            decode(fields(2), Wire.word16).toInt,
            decode(fields(3), Params.conwayCodec),
            decode(fields(4), summon[Codec[ChainAccountState]]),
            before,
            tx
          )
          .map(UpstreamEraLedger.ofLedger)
      case other => fail(s"not a LEDGER or LEDGERS row: $other")
    assertEquals(
      decode(fields(if rule == "LEDGERS" then 2 else 3), Params.conwayCodec),
      before.utxo.gov.curPParams
    )
    val outcome = judged
      .fold(e => fail(s"era inputs: $e"), identity)
      .fold(e => fail(s"era defect: $e"), identity)
    (text(row, "native"), outcome) match
      case ("accepted", UpstreamEraLedger.Outcome.Admitted(state, _)) =>
        sameLedger(
          state,
          decode(read("implementation-state"), UpstreamNativeStates.nativeLedgerState)
        )
        outcome
      case ("rejected", UpstreamEraLedger.Outcome.Refused(failures)) =>
        rejected(rule, failures, read("implementation-failures"))
        outcome
      case (native, other) => fail(s"native $native, era $other")

  private val ledgerRules = Set("LEDGER", "LEDGERS")

  cases.foreach { row =>
    val id = text(row, "id")
    val incoherent = text(row, "replayScope") == "incoherent-cache"
    test(
      s"the era-typed ${text(row, "rule")} gives the native ${text(row, "native")} outcome: $id"
    ) {
      if ledgerRules(text(row, "rule")) then
        val _ = eraReplay(row, base.resolve(s"cases/$id"), incoherent)
      else eraUtxoReplay(row, base.resolve(s"cases/$id"), incoherent)
    }
  }

  test("LEDGER tells CERTS's pool registration and GOV's removed votes, embedded in its events") {
    // the pool the reference's post-state registers, and the DRep it drops, each told by the
    // sub-rule and wrapped by LEDGER's embeddings (`Conway/Rules/Ledger.hs:543-560` and `:589-601`,
    // cardano-ledger f649f975)
    def judged(id: String) =
      val row = cases.find(r => text(r, "id") == id).getOrElse(fail(s"no case $id"))
      val dir = base.resolve(s"cases/$id")
      val states = Vector("state", "implementation-state").map(n =>
        decode(nodeAt(dir.resolve(s"$n.cbor")), UpstreamNativeStates.nativeLedgerState)
      )
      eraReplay(row, dir, incoherent = false) match
        case UpstreamEraLedger.Outcome.Admitted(_, events) => (states(0), states(1), events)
        case other                                         => fail(s"$id: $other")
    import xyz.kd5ujc.scarab.ledger.era.rules.{
      ConwayCertEvent,
      ConwayCertsEvent,
      ConwayGovEvent,
      ConwayLedgerEvent,
      PoolEvent
    }
    val (poolBefore, poolAfter, poolEvents) = judged("ledger-274103")
    val pools = LedgerState.pState[Conway].get(poolAfter).stakePools.keySet --
      LedgerState.pState[Conway].get(poolBefore).stakePools.keySet
    assertEquals(pools.size, 1)
    assertEquals(
      poolEvents.collect { case e: ConwayLedgerEvent.CertsEvent[Conway] => e },
      pools.toVector.map(pool =>
        ConwayLedgerEvent.CertsEvent[Conway](
          ConwayCertsEvent.CertEvent(ConwayCertEvent.PoolEvent(PoolEvent.RegisterPool(pool)))
        )
      )
    )
    val (drepBefore, drepAfter, drepEvents) = judged("drep-delegation-unregistration-ledger-4")
    val dropped = drepBefore.cert.vState.dreps.keySet -- drepAfter.cert.vState.dreps.keySet
    assertEquals(dropped.size, 1)
    val drepTx = UpstreamEraLedger
      .transaction(
        nodeAt(base.resolve("cases/drep-delegation-unregistration-ledger-4/signal.cbor"))
      )
      .fold(e => fail(s"transaction: $e"), identity)
    val txId =
      xyz.kd5ujc.scarab.ledger.era.EraTxBody.conway.id[xyz.kd5ujc.scarab.ledger.era.TxLevel.Top](
        xyz.kd5ujc.scarab.ledger.era.EraTx.conway
          .body[xyz.kd5ujc.scarab.ledger.era.TxLevel.Top](drepTx)(using
            xyz.kd5ujc.scarab.ledger.era.EraTx.conway.bodyOf.top
          )
      )(using xyz.kd5ujc.scarab.ledger.era.EraTxBody.conway.top)
    assertEquals(
      drepEvents.collect {
        case ConwayLedgerEvent.GovEvent(ConwayGovEvent.GovRemovedVotes(id, votes, dreps)) =>
          (id.bytes, votes.toVector, dreps.toVector)
      },
      Vector((txId, Vector.empty, dropped.toVector))
    )
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
    test(s"the era-typed rule gives the original generated-cache native refusal: $id") {
      eraReplay(originalRefusal(row), base.resolve(s"original-generated/$id"), incoherent = true)
    }
  }

  test("the era-typed LEDGER and LEDGERS run every LEDGER and LEDGERS judgment of the corpus") {
    // 38 rows of the cases (29 LEDGER, 9 LEDGERS) and the 8 original generated refusals
    val rows = cases.filter(row => ledgerRules(text(row, "rule")))
    assertEquals(rows.count(row => text(row, "rule") == "LEDGER"), 29)
    assertEquals(rows.count(row => text(row, "rule") == "LEDGERS"), 9)
    assertEquals(originalRefusals.map(row => text(originalRefusal(row), "rule")).toSet, ledgerRules)
    assertEquals(rows.size + originalRefusals.size, 46)
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

  test("the era-typed standalone UTXO refuses missing collateral without redeemers as the native") {
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
    // the era-typed UTXO at the root, as the hook ran it: the collateral the state does not hold,
    // as UTXO's `BadInputsUTxO` (`Babbage/Rules/Utxo.hs:375`, the collateral among all the
    // inputs, cardano-ledger f649f975)
    val eraTransaction = UpstreamEraLedger
      .transaction(observed("signal"))
      .fold(e => fail(s"era transaction: $e"), identity)
    val failures = UpstreamEraLedger
      .utxo(globals, slot, params, cert, before, eraTransaction)
      .fold(e => fail(s"era inputs: $e"), identity)
      .fold(e => fail(s"era defect: $e"), identity)
      .fold(_.toChain.toVector, state => fail(s"admitted: $state"))
    failures match
      case Vector(xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxoPredFailure.BadInputsUTxO(inputs)) =>
        assertEquals(inputs.toSortedSet.toSet, transaction.body.collateral.toSet)
      case other => fail(s"expected the collateral as bad inputs, got $other")
    val encoded = Cbor
      .decode(UpstreamEraLedger.refusal(UpstreamEraLedger.ofUtxo(failures)))
      .fold(e => fail(e.message), identity)
    val ledgerFailure = array(array(array(encoded).head)(1)).head
    val utxoFailure = array(array(ledgerFailure)(1))(1)
    assertEquals(
      array(observed("native-failures")).map(_.original.hex),
      Vector(utxoFailure.original.hex)
    )
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
