// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.Bytes
import ReferenceJson.Json

class NodeAuditCommandSuite extends munit.FunSuite:
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def get[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val original =
    """{"record":"transfer-range-block","headerEnvelopeHex":"00","rawBlockHex":"00"}"""
  test("capture limits remain eight unless explicitly bounded through twelve") {
    val nine = raw(Vector.fill(9)(original).mkString("\n"))
    assert(CoherentSequenceCommand.captures(nine).isLeft)
    assertEquals(get(CoherentSequenceCommand.captures(nine, 12)).size, 9)
    assert(
      CoherentSequenceCommand.captures(raw(Vector.fill(13)(original).mkString("\n")), 12).isLeft
    )
    Vector(0, 1, 13, Int.MaxValue).foreach(n =>
      assert(CoherentSequenceCommand.captures(nine, n).isLeft)
    )
  }
  test("stdout rejects malformed, duplicate, excessive and non UTF-8 records") {
    Vector(
      raw("{"),
      raw("{\"record\":1,\"record\":2}"),
      raw(Vector.fill(1025)("{}").mkString("\n")),
      Bytes.fromArray(Array(0xc3.toByte))
    ).foreach { bytes =>
      intercept[Exception](NodeAuditCommand.records(bytes))
    }
  }
  test("bad command bounds reject before file access") {
    List(
      Nil,
      List("missing", "missing", "missing", "4", "13"),
      List("missing", "missing", "missing", "04", "12")
    ).traverse_ { args =>
      NodeAuditCommand.run(args).map(code => assertEquals(code.code, 2))
    }.unsafeToFuture()
  }
  test(
    "fenced profile explicitly accepts sixteen and rejects seventeen without widening legacy bounds"
  ) {
    val sixteen = raw(Vector.fill(16)(original).mkString("\n"))
    assertEquals(get(CoherentSequenceCommand.fencedCaptures(sixteen)).size, 16)
    assert(
      CoherentSequenceCommand.fencedCaptures(raw(Vector.fill(17)(original).mkString("\n"))).isLeft
    )
    assert(CoherentSequenceCommand.captures(sixteen, 16).isLeft)
    (12 to 16).foreach(n => assertEquals(NodeAuditCommand.arguments("2", n.toString, true), (2, n)))
    Vector(("2", "11"), ("2", "17"), ("3", "12"), ("02", "12"), ("2", "012")).foreach {
      (capacity, target) =>
        intercept[IllegalArgumentException](NodeAuditCommand.arguments(capacity, target, true))
    }
    intercept[IllegalArgumentException](NodeAuditCommand.arguments("2", "13", false))
  }
  test("fenced command rejects input limits before opening missing files") {
    Vector(("2", "17"), ("1", "12"), ("2", "11"))
      .traverse_ { (capacity, target) =>
        NodeAuditCommand
          .runFenced(List("missing", "missing", "missing", capacity, target))
          .map(code => assertEquals(code.code, 2))
      }
      .unsafeToFuture()
  }
  sys.env.get("NODE_FENCE_AUDIT_EVIDENCE").foreach { location =>
    test("retained twelve original blocks independently replay and match entire supported oracle") {
      val dir = Path.of(location)
      val c = get(SequenceInput.load(dir)); val os = get(CoherentSequenceCommand.loadOracle(dir))
      val captured = os.originals("scala-sequence-capture.md")
      val bs =
        get(CoherentSequenceCommand.fencedCaptures(captured)).map(o => get(SequenceInput.block(o)))
      assertEquals(bs.size, 12)
      val files = Vector(
        os.originals("signed-transaction-0-cbor.md"),
        os.originals("signed-transaction-1-cbor.md")
      )
      val empty = bs.find(_.transactionMemos.isEmpty).get
      val pair = bs.find(_.transactionMemos.size == 2).get
      val sixteen = Vector.fill(15)(empty) :+ pair
      assert(CoherentSequenceCommand.fencedGrouping(sixteen, files).isRight)
      assert(CoherentSequenceCommand.fencedGrouping(sixteen :+ empty, files).isLeft)
      assert(CoherentSequenceCommand.checkGrouping(sixteen, files, 16).isLeft)
      val work = for
        receipts <- NodeAuditCommand.fencedReplay(c, bs)
        state = receipts.last.state
        stdout = raw(
          new String(captured.toArray, "UTF-8") + "\n" + NodeAuditCommand.stateRecord(state)
        )
        report <- NodeAuditCommand.fencedAssess(c, os, stdout, 12)
        duplicate <- NodeAuditCommand
          .fencedAssess(
            c,
            os,
            raw(new String(stdout.toArray, "UTF-8") + "\n" + NodeAuditCommand.stateRecord(state)),
            12
          )
          .attempt
      yield
        assertEquals(state.compactedBlocks, BigInt(10))
        assertEquals(ReferenceJson.field(report, "scope"), Json.Str("node-fence-audit"))
        Vector("completeProjectionMatched", "referencePostStateMatched")
          .foreach(k => assertEquals(ReferenceJson.field(report, k), Json.Lit("true")))
        Vector("fullLedgerValidated", "consensusValidated", "durableClaim")
          .foreach(k => assertEquals(ReferenceJson.field(report, k), Json.Lit("false")))
        assert(duplicate.isLeft)
      work.unsafeToFuture()
    }
  }
  sys.env.get("NODE_AUDIT_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def context = get(SequenceInput.load(dir))
    def oracle = get(CoherentSequenceCommand.loadOracle(dir))
    def captured = oracle.originals("scala-sequence-capture.md")
    def blocks =
      get(CoherentSequenceCommand.captures(captured)).map(o => get(SequenceInput.block(o)))
    test("retained four-block replay compacts at two and matches full reference projection") {
      val c = context; val os = oracle; val bs = blocks
      assertEquals(bs.size, 4)
      val work = for
        applied <- NodeAuditCommand.replay(c, bs, 2)
        state = applied.last.state
        stdout = raw(
          new String(captured.toArray, "UTF-8") + "\n" + NodeAuditCommand.stateRecord(state)
        )
        report <- NodeAuditCommand.assess(c, os, stdout, 2, 4)
      yield
        assertEquals(state.depth, BigInt(4))
        assertEquals(state.compactedBlocks, BigInt(2))
        assert(state.derivedAnchorId.nonEmpty)
        assertEquals(ReferenceJson.field(report, "completeProjectionMatched"), Json.Lit("true"))
        assertEquals(ReferenceJson.field(report, "referencePostStateMatched"), Json.Lit("true"))
      work.unsafeToFuture()
    }
    test("online projection requires unique exact content, revision and derived provenance") {
      NodeAuditCommand
        .replay(context, blocks, 2)
        .map { applied =>
          val state = applied.last.state
          val json = ReferenceJson.parse(raw(NodeAuditCommand.stateRecord(state)))
          NodeAuditCommand.checkProjection(Vector(json), state)
          intercept[IllegalArgumentException](NodeAuditCommand.checkProjection(Vector.empty, state))
          intercept[IllegalArgumentException](
            NodeAuditCommand.checkProjection(Vector(json, json), state)
          )
          val fields = json match
            case Json.Obj(fields) => fields
            case _                => fail("object required")
          Vector("revision", "depth", "compactedBlocks", "derivedAnchorId", "projection").foreach {
            key =>
              intercept[IllegalArgumentException](
                NodeAuditCommand.checkProjection(
                  Vector(Json.Obj(fields.updated(key, Json.Str("tampered")))),
                  state
                )
              )
          }
        }
        .unsafeToFuture()
    }
    test("grouping explicit twelve limit preserves default eight and rejects thirteen") {
      val bs = blocks
      val empty = bs.find(_.transactionMemos.isEmpty).get
      val pair = bs.find(_.transactionMemos.size == 2).get
      val files = Vector(
        oracle.originals("signed-transaction-0-cbor.md"),
        oracle.originals("signed-transaction-1-cbor.md")
      )
      // Grouping-only test; repeated empty originals are not claimed as a valid chain.
      val twelve = Vector.fill(11)(empty) :+ pair
      assert(CoherentSequenceCommand.checkGrouping(twelve, files).isLeft)
      assert(CoherentSequenceCommand.checkGrouping(twelve, files, 12).isRight)
      assert(CoherentSequenceCommand.checkGrouping(twelve :+ empty, files, 12).isLeft)
      assert(CoherentSequenceCommand.checkGrouping(twelve, files, 13).isLeft)
    }
    test("reordered or missing originals cannot certify online state") {
      val c = context; val os = oracle
      val work = for
        applied <- NodeAuditCommand.replay(c, blocks, 2)
        state = NodeAuditCommand.stateRecord(applied.last.state)
        result <- NodeAuditCommand.assess(c, os, raw(state + "\n" + original), 2, 4).attempt
      yield assert(result.isLeft)
      work.unsafeToFuture()
    }
  }
