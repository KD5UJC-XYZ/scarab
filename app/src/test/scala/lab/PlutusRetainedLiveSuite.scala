// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, LinkOption, Path}
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.ledger.{PlutusOutput, TxIn}
import lab.submission.{AdmissionProfile, SignedTransaction}

/** Offline replay of independently pinned private local evidence; no node, keys or submission. */
class PlutusRetainedLiveSuite extends munit.FunSuite:
  import NativeLiveBoundaryMain.{hash, obj, point, read, sha}
  import ReferenceJson.{field, string, uint}
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)

  sys.env.get("PLUTUS_LIVE_BUNDLE").foreach { directory =>
    val root = Path.of(directory).toAbsolutePath.normalize()
    lazy val pinned: Map[String, Bytes] =
      val manifest =
        read(Path.of(sys.env.getOrElse("PLUTUS_LIVE_MANIFEST", fail("manifest required"))), 65536)
      assertEquals(
        sha(manifest).hex,
        sys.env.getOrElse("PLUTUS_LIVE_MANIFEST_SHA256", fail("independent manifest pin required"))
      )
      val parsed = ReferenceJson.parse(manifest)
      assertEquals(string(field(parsed, "schema")), "plutus-retained-live-inputs-v1")
      val rows = obj(field(parsed, "inputs"))
      assert(rows.nonEmpty && rows.size <= 128)
      rows.map { (name, row) =>
        val relative = Path.of(name)
        assert(
          !relative.isAbsolute && !name.contains("\\") && relative.normalize().toString == name
        )
        val path = root.resolve(relative).normalize()
        assert(
          path.startsWith(root) && path != root && Files
            .isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        )
        assert(path.toRealPath().startsWith(root.toRealPath()))
        val bytes = read(path, 1048576)
        assertEquals(BigInt(bytes.size), uint(field(row, "bytes")), name)
        assertEquals(sha(bytes), hash(field(row, "sha256")), name)
        name -> bytes
      }
    def json(name: String) =
      ReferenceJson.parse(pinned.getOrElse(name, fail("unpinned input: " + name)))
    lazy val joined =
      val inputs = json("initial/adapter-inputs.json")
      obj(field(inputs, "inputs")).keys.foreach(n => assert(pinned.contains("initial/" + n)))
      NativeLiveBoundaryMain.initial(
        root.resolve("initial"),
        sha(pinned("initial/adapter-inputs.json")).hex,
        AdmissionProfile.PlutusV3
      )
    lazy val descriptor = json("submission/descriptor.json")
    lazy val result = json("result.json")
    lazy val request = json("endpoint-request.json")
    lazy val terminal =
      val p = point(field(result, "finalPoint"))
      assertEquals(p, point(field(request, "point")))
      assertEquals(hash(field(request, "sourceJoinId")), joined.id)
      assertEquals(point(field(descriptor, "initialPoint")), joined.acquisition.anchor)
      assertEquals(uint(field(request, "epoch")), BigInt(0))
      assert(p.slot < 1000)
      p
    lazy val submitted =
      val raw = pinned("submission/transaction.cbor")
      val tx = get(SignedTransaction.checked(raw))
      assertEquals(tx.transactionId, hash(field(descriptor, "transactionId")))
      assertEquals(sha(raw), hash(field(descriptor, "envelopeSHA256")))
      assertEquals(sha(tx.originalBody), hash(field(descriptor, "bodySHA256")))
      assertEquals(sha(tx.originalWitnesses), hash(field(descriptor, "witnessesSHA256")))
      tx
    lazy val spent = get(PlutusEndpointLedger.input(string(field(descriptor, "spentInput"))))
    lazy val collateral =
      get(PlutusEndpointLedger.input(string(field(descriptor, "collateralInput"))))
    lazy val fee = uint(field(descriptor, "fee"))
    lazy val acquired =
      val inputs = json("endpoint/endpoint-inputs.json")
      obj(field(inputs, "inputs")).keys.foreach(n => assert(pinned.contains("endpoint/" + n)))
      assert(pinned.contains("acquisition-result.json"))
      val a = NativeLiveBoundaryMain.endpoint(root, json("endpoint-ready.json"), terminal, joined)
      assertEquals(a.id, hash(field(result, "endpointAcquisitionId")))
      a
    lazy val blocks =
      val names = pinned.keys.filter(_.matches("originals/block-[0-9]{4}\\.cbor")).toVector.sorted
      assert(names.nonEmpty && names.size <= 16)
      names.zipWithIndex.map { (name, index) =>
        assertEquals(name, f"originals/block-$index%04d.cbor")
        get(
          SequenceInput.block(
            BoundedChainFollower
              .Original(pinned(name.stripSuffix(".cbor") + "-header.cbor"), pinned(name))
          )
        )
      }
    def replay: IO[(CoherentSequence.State, CoherentSequence.State)] = for
      context <- IO(get(SequenceInput.fromNativeDiagnostic(joined, joined.id)))
      runtime <- CoherentSequence
        .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
        .map(get(_))
      before <- runtime.snapshot
      _ <- blocks.traverse_ { block =>
        runtime.prepare(block).map(get(_)).flatMap(c => runtime.publish(c).map(get(_))).void
      }
      after <- runtime.snapshot
      _ = assertEquals(after.state.certificates.state.tip, terminal)
      _ = assertEquals(after.state.id, hash(field(request, "networkAppliedStateId")))
    yield (before.state, after.state)

    def compare(
        state: CoherentSequence.State,
        selectedFee: BigInt = fee,
        selectedSpent: TxIn = spent,
        selectedCollateral: TxIn = collateral,
        selectedId: Bytes = acquired.id,
        selectedPoint: Point = terminal
    ) =
      PlutusEndpointLedger.compare(
        acquired,
        selectedPoint,
        selectedId,
        state,
        joined,
        submitted,
        selectedSpent,
        selectedCollateral,
        selectedFee
      )
    def rejected(value: Either[String, PlutusEndpointLedger.Report], message: String): Unit =
      value match
        case Left(error) => assert(error.contains(message), error)
        case Right(_)    => fail("endpoint mismatch was accepted")

    test("real inline-datum bootstrap replays original blocks and matches whole endpoint proof") {
      replay
        .map { (before, after) =>
          val initialOutputs = get(PlutusOutput.snapshot(before.ledger.outputMap, 0)).outputs
          assertEquals(
            initialOutputs.collect { case (ref, out) if out.datum.nonEmpty => ref }.toSet,
            Set(spent)
          )
          val included = blocks
            .flatMap(_.transactionMemos)
            .map(m => get(SignedTransaction.checked(m)))
            .filter(_.transactionId == submitted.transactionId)
          assertEquals(included.size, 1)
          assertEquals(included.head.originalBody, submitted.originalBody)
          assertEquals(included.head.originalWitnesses, submitted.originalWitnesses)
          val proof = get(compare(after))
          assert(proof.wholeUtxoEqual && proof.instantaneousStakeEqual && proof.collateralPreserved)
          assert(!proof.fullLedgerValidated)
          assertEquals(proof.feesAfter - proof.feesBefore, fee)
          assertEquals(proof.wholeUtxoSHA256, sha(pinned("endpoint/original-whole-utxo.cbor")))
        }
        .unsafeToFuture()
    }
    test("endpoint comparison rejects a fee one lovelace above the submitted body") {
      replay
        .map((_, after) => rejected(compare(after, selectedFee = fee + 1), "descriptor/body"))
        .unsafeToFuture()
    }
    test("endpoint comparison rejects substituted collateral reference") {
      replay
        .map((_, after) => rejected(compare(after, selectedCollateral = spent), "descriptor/body"))
        .unsafeToFuture()
    }
    test("endpoint comparison rejects substituted spent reference") {
      replay
        .map((_, after) =>
          rejected(compare(after, selectedSpent = collateral), "one consumed inline datum")
        )
        .unsafeToFuture()
    }
    test("endpoint comparison rejects a different acquisition identity") {
      replay
        .map((_, after) =>
          rejected(compare(after, selectedId = sha(acquired.id)), "acquisition/fullpoint/network")
        )
        .unsafeToFuture()
    }
    test("endpoint comparison binds block number as well as slot and hash") {
      replay
        .map((_, after) =>
          rejected(
            compare(
              after,
              selectedPoint = Point(terminal.hash, terminal.slot, terminal.blockNo + 1)
            ),
            "acquisition/fullpoint/network"
          )
        )
        .unsafeToFuture()
    }
    test("endpoint comparison rejects bootstrap state in place of replayed inclusion") {
      replay
        .map((before, _) => rejected(compare(before), "same-epoch terminal state"))
        .unsafeToFuture()
    }
  }
