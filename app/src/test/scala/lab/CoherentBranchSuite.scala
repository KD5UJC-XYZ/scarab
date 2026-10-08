// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.ledger.ClusterTransition

class CoherentBranchSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(f => fail(f.toString), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-branch-input-v1\n" + BranchInput.sources.toVector
      .sortBy(_._1)
      .map((k, n) => k + "\t" + ClusterHeaderObservation.sha256(files(n)).hex)
      .mkString("\n") + "\n"
  )
  test("composite state and candidate cannot be copied or manufactured") {
    assert(compileErrors("val s: lab.CoherentBranch.State = null; s.copy()").nonEmpty)
    assert(compileErrors("new lab.CoherentBranch.Candidate(null, null, null, null)").nonEmpty)
    assert(compileErrors("new lab.CoherentBranch.ScopedSuccess(null, null, null)").nonEmpty)
  }
  sys.env.get("COHERENT_BRANCH_EVIDENCE").foreach { location =>
    def retained = get(BranchInput.load(Path.of(location)))
    // Synthetic unit state only: replace unspent Byron output addresses with a dummy enterprise
    // address. Never persist or describe this altered state as the retained reference snapshot.
    // The production loader/transition never performs this transformation.
    def input =
      val original = retained
      val decoded = get(Cbor.decode(original.preUtxoCbor))
      def replace(node: Node): Node = node.value match
        case Value.ByteString(b) if b.size > 0 && ((b.value.head & 255) >>> 4) == 8 =>
          Node(
            Value.ByteString(Bytes(Vector(0x60.toByte) ++ Vector.fill(28)(0.toByte))),
            Bytes.empty
          )
        case other => node
      val values = decoded.value.asInstanceOf[Value.Map].value.map { (key, out) =>
        val v = out.value match
          case Value.Arr(xs) => Value.Arr(xs.updated(0, replace(xs.head)))
          case Value.Map(xs) =>
            Value.Map(xs.map { (k, v) => (k, if k.value == Value.UInt(0) then replace(v) else v) })
          case _ => fail("fixture output")
        (key, Node(v, Bytes.empty))
      }
      val synthetic = get(Cbor.encode(Value.Map(values)))
      val files = original.originals.updated("pre-utxo-cbor.md", raw(synthetic.hex))
      get(BranchInput.bind(manifest(files), files))
    test("unaltered retained whole checkpoint is explicitly Unsupported, never silently filtered") {
      CoherentBranch
        .create[IO](retained)
        .map { result =>
          assertEquals(
            result.left.toOption,
            Some(
              CoherentBranch.Failure
                .Unsupported("ledger", "unsupported payment address kind/network/length")
            )
          )
        }
        .unsafeToFuture()
    }
    def rebound(change: Map[String, Bytes] => Map[String, Bytes]) =
      val files = change(input.originals)
      get(BranchInput.bind(manifest(files), files))
    test(
      "synthetic supplied state: whole tuple publishes once, rolls back with a new revision, and replays identically"
    ) {
      val in = input
      (for
        runtime <- CoherentBranch.create[IO](in).map(get(_))
        before <- runtime.snapshot
        a <- runtime.prepare(in).map(get(_))
        b <- runtime.prepare(in).map(get(_))
        afterPrepare <- runtime.snapshot
        first <- runtime.publish(a).map(get(_))
        stale <- runtime.publish(b)
        restored <- runtime.rollback(first).map(get(_))
        oldCandidate <- runtime.publish(a)
        oldUndo <- runtime.rollback(first)
        again <- runtime.prepare(in).map(get(_)).flatMap(runtime.publish).map(get(_))
      yield
        assertEquals(afterPrepare.id, before.id)
        assertEquals(before.acquisition.size, 0)
        assert(before.eligibility.isEmpty)
        assertEquals(first.state.acquisition.tip, in.certificates.acquisition.tip)
        assertEquals(first.state.certificates.state.tip.slot, first.state.ledger.slot)
        assertEquals(first.state.eligibility.get.headers.head.headerHash, in.header.hash)
        assertEquals(first.state.ledger.fees, before.ledger.fees + 200000)
        assertEquals(first.state.revision, BigInt(1))
        assertEquals(stale, Left(CoherentBranch.Failure.StaleCandidate))
        assertEquals(restored.id, before.id)
        assertEquals(restored.ledger.outputMap, before.ledger.outputMap)
        assertEquals(restored.certificates.state.id, before.certificates.state.id)
        assertEquals(restored.acquisition.size, 0)
        assert(restored.eligibility.isEmpty)
        assertEquals(restored.revision, BigInt(2))
        assert(oldCandidate.isLeft && oldUndo.isLeft)
        assertEquals(again.state.id, first.state.id)
        assertEquals(again.state.revision, BigInt(3))
        assert(
          !again.fullLedgerValidated && !again.consensusValidated && !again.authenticatedSnapshot
        )
      ).unsafeToFuture()
    }
    test("synthetic supplied state: concurrent publishers cannot both replace the same revision") {
      val in = input
      (for
        runtime <- CoherentBranch.create[IO](in).map(get(_))
        a <- runtime.prepare(in).map(get(_))
        results <- (runtime.publish(a), runtime.publish(a)).parTupled
        state <- runtime.snapshot
      yield
        assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
        assertEquals(state.revision, BigInt(1))
        assertEquals(state.acquisition.size, 1)
      ).unsafeToFuture()
    }
    test("synthetic supplied state: cancellation before publish leaves the whole tuple unchanged") {
      val in = input
      (for
        runtime <- CoherentBranch.create[IO](in).map(get(_))
        before <- runtime.snapshot
        ready <- Deferred[IO, Unit]
        fiber <- (runtime.prepare(in).flatMap(c => IO(get(c))) *> ready.complete(
          ()
        ) *> IO.never).start
        _ <- ready.get
        _ <- fiber.cancel
        after <- runtime.snapshot
      yield
        assertEquals(after.id, before.id)
        assertEquals(after.revision, BigInt(0))
        assertEquals(after.acquisition.size, 0)
        assert(after.eligibility.isEmpty)
      ).unsafeToFuture()
    }
    test(
      "synthetic supplied state: rebound source attribution cannot replace the current context"
    ) {
      val in = input
      val changed = rebound(files =>
        files.updated("pre-utxo.md", raw(new String(files("pre-utxo.md").toArray, "UTF-8") + " "))
      )
      (for
        runtime <- CoherentBranch.create[IO](in).map(get(_))
        before <- runtime.snapshot
        result <- runtime.prepare(changed)
        after <- runtime.snapshot
      yield
        assertEquals(result.left.toOption, Some(CoherentBranch.Failure.StaleCandidate))
        assertEquals(after.id, before.id)
        assertEquals(after.revision, before.revision)
      ).unsafeToFuture()
    }
    test(
      "synthetic supplied state: eligibility failure publishes none of the certificate or ledger intermediates"
    ) {
      val bad = rebound { files =>
        val old = files("pre-protocol-state.md")
        val nonce =
          ReferenceJson.string(ReferenceJson.field(ReferenceJson.parse(old), "epochNonce"))
        files.updated(
          "pre-protocol-state.md",
          raw(new String(old.toArray, "UTF-8").replace(nonce, "00" * 32))
        )
      }
      (for
        runtime <- CoherentBranch.create[IO](bad).map(get(_))
        before <- runtime.snapshot
        rejected <- runtime.prepare(bad)
        after <- runtime.snapshot
      yield
        rejected match
          case Left(CoherentBranch.Failure.Rejected("eligibility", _)) => ()
          case other                                                   => fail(other.toString)
        assertEquals(after.id, before.id)
        assertEquals(after.revision, before.revision)
        assertEquals(after.acquisition.size, 0)
      ).unsafeToFuture()
    }
    test(
      "synthetic supplied state: ledger rejection leaves all earlier successful checks unpublished"
    ) {
      val bad = rebound { files =>
        val p = files("pre-parameters.md")

        files.updated(
          "pre-parameters.md",
          raw(
            new String(p.toArray, "UTF-8")
              .replaceAll("""("txFeeFixed"\s*:\s*)[0-9]+""", "$1 999999999")
          )
        )
      }
      (for
        runtime <- CoherentBranch.create[IO](bad).map(get(_))
        before <- runtime.snapshot
        rejected <- runtime.prepare(bad)
        after <- runtime.snapshot
      yield
        rejected match
          case Left(CoherentBranch.Failure.LedgerRejected(ClusterTransition.Failure.Rejected(_))) =>
            ()
          case other => fail(other.toString)
        assertEquals(after.id, before.id)
        assertEquals(after.revision, BigInt(0))
        assertEquals(after.acquisition.size, 0)
      ).unsafeToFuture()
    }
  }
