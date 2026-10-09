// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync
import ReferenceJson.Json
import scala.concurrent.duration.*

/** Offline fork replay. Post-state oracles never seed the checked transitions. */
object ForkAuditCommand extends IOApp:
  private def get[E, A](e: Either[E, A]): A =
    e.fold(x => throw new IllegalArgumentException(x.toString), identity)
  private def checked[E, A](e: Either[E, A]): IO[A] = IO(get(e))
  private def str(s: String): Json = Json.Str(s)
  private def obj(fields: (String, Json)*): Json = Json.Obj(fields.toMap)
  private def num(n: BigInt): Json = str(n.toString)
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(v)                => v
    case Value.Tag(t, v) if t == 258 => arr(v)
    case _                           => throw new IllegalArgumentException("CBOR array required")
  private def field(n: Node, key: Int): Node = n.value match
    case Value.Map(v) =>
      v.collectFirst { case (k, v) if k.value == Value.UInt(BigInt(key)) => v }.get
    case _ => throw new IllegalArgumentException("CBOR map required")
  private def uint(n: Node): BigInt = n.value match
    case Value.UInt(v) => v
    case _             => throw new IllegalArgumentException("CBOR uint required")
  private def decode(b: Bytes): Node = get(Cbor.decode(b))
  private def hashNode(n: Node): String = n.value match
    case Value.ByteString(b) => b.hex
    case _                   => throw new IllegalArgumentException("CBOR bytes required")
  private def input(n: Node): String =
    val a = arr(n)
    require(a.size == 2, "two-field input required")
    hashNode(a.head) + "#" + uint(a(1))
  private def utxo(b: Bytes): Map[String, Bytes] = decode(b).value match
    case Value.Map(entries) =>
      val pairs = entries.map((k, v) => input(k) -> v.original)
      require(pairs.map(_._1).distinct.size == pairs.size, "unique UTxO keys required")
      pairs.toMap
    case _ => throw new IllegalArgumentException("UTxO map required")
  private[lab] final case class Transaction(
      body: Bytes,
      witness: Bytes,
      id: Bytes,
      spent: String,
      outputs: Set[String],
      fee: BigInt
  )
  private[lab] def transaction(bytes: Bytes): Transaction =
    val raw = get(Bytes.fromHex(new String(bytes.toArray, "UTF-8").trim))
    require(raw.size > 0 && raw.size <= 65536, "bounded submitted transaction")
    val a = arr(decode(raw))
    require(
      a.size == 4 && a(2).value == Value.Bool(true) && a(3).value == Value.Null,
      "supported four-field transaction required"
    )
    val ins = arr(field(a.head, 0)); val outs = arr(field(a.head, 1))
    require(ins.size == 1 && outs.nonEmpty, "exactly one input and nonempty outputs required")
    val id = get(TransactionId.fromEnvelope(raw))
    val fee = uint(field(a.head, 2)); require(fee > 0, "positive fee required")
    Transaction(
      a.head.original,
      a(1).original,
      id,
      input(ins.head),
      outs.indices.map(i => id.hex + "#" + i).toSet,
      fee
    )
  private[lab] def branchBounds(a: Int, b: Int): Unit =
    require(a >= 1 && a <= 2 && b > a && b <= 4, "1 <= nA <= 2 and nA < nB <= 4 required")
  private[lab] def originals(bytes: Bytes, maximum: Int): Vector[BoundedChainFollower.Original] =
    val result = NodeAuditCommand.records(bytes).flatMap { json =>
      val fields = json match
        case Json.Obj(fields) => fields
        case _                => throw new IllegalArgumentException("object record required")
      if fields.get("record").contains(str("transfer-range-block")) then
        def hex(key: String, max: Int): Bytes =
          val text = ReferenceJson.string(fields(key)); val b = get(Bytes.fromHex(text))
          require(text == b.hex && b.size > 0 && b.size <= max, "bounded canonical original hex")
          b
        Some(
          BoundedChainFollower
            .Original(hex("headerEnvelopeHex", 65535), hex("rawBlockHex", 1048576))
        )
      else None
    }
    require(result.nonEmpty && result.size <= maximum, "branch capture bound")
    result
  private[lab] def membership(
      memos: Vector[Bytes],
      wanted: Transaction,
      excluded: Transaction
  ): Unit =
    require(
      wanted.id != excluded.id && wanted.spent == excluded.spent,
      "distinct double-spend markers required"
    )
    val pairs = memos.map { m =>
      val a = arr(decode(m)); (a.head.original, a(1).original)
    }
    require(
      pairs == Vector((wanted.body, wanted.witness)),
      "exact single marker body/witness inclusion; other marker excluded"
    )
  private[lab] def checkStateEffects(
      c: Bytes,
      a: Bytes,
      rolled: Bytes,
      b: Bytes,
      cFees: BigInt,
      aFees: BigInt,
      rolledFees: BigInt,
      bFees: BigInt,
      ta: Transaction,
      tb: Transaction
  ): Unit =
    val initial = utxo(c); val left = utxo(a); val restored = utxo(rolled); val right = utxo(b)
    require(
      ta.id != tb.id && ta.spent == tb.spent && initial.contains(ta.spent),
      "same present C input required"
    )
    require(ta.outputs.intersect(tb.outputs).isEmpty, "distinct marker outputs required")
    require(
      restored == initial && rolled == c && rolledFees == cFees,
      "exact restored C input/whole UTxO and fees"
    )
    def branch(
        actual: Map[String, Bytes],
        own: Transaction,
        other: Transaction,
        fees: BigInt
    ): Unit =
      val survivors = initial - own.spent
      require(
        actual.keySet == survivors.keySet ++ own.outputs,
        "exact spent input and branch output keys"
      )
      require(
        survivors.forall((k, v) => actual.get(k).contains(v)),
        "unchanged C survivors required"
      )
      require(other.outputs.forall(k => !actual.contains(k)), "other branch outputs must be absent")
      require(fees == cFees + own.fee, "exact marker fee-pot effect")
    branch(left, ta, tb, aFees); branch(right, tb, ta, bFees)
    require(ta.outputs.forall(k => !restored.contains(k)), "rollback removes A outputs")
  private def applyAll(runtime: CoherentSequence.Runtime[IO], blocks: Vector[SequenceInput.Block]) =
    blocks.traverse { b =>
      for
        old <- runtime.snapshot
        prepared <- runtime.prepare(b).flatMap(checked)
        applied <- runtime.publish(prepared).flatMap(checked)
        _ <- checked(
          ValidatedRestartCapture.checkEffects(
            old.state.ledger.outputMap,
            old.state.ledger.fees,
            applied.state.ledger.outputMap,
            applied.state.ledger.fees,
            b.transactionMemos
          )
        )
      yield applied
    }
  private def point(p: ChainSync.Point): Json = p match
    case ChainSync.Point.Block(slot, hash) =>
      obj("slot" -> Json.Num(slot.value.toString), "hash" -> str(hash.hex))
    case _ => Json.Lit("null")
  private def state(s: CoherentSequence.State, generation: BigInt): Json = obj(
    "projection" -> ValidatedRestartCapture.projection(s),
    "revision" -> num(s.revision),
    "generation" -> num(generation),
    "depth" -> num(s.depth)
  )
  private[lab] def assess(
      context: SequenceInput.Context,
      oa: CoherentSequenceCommand.OwnedOracle,
      ob: CoherentSequenceCommand.OwnedOracle,
      stdoutA: Bytes,
      stdoutB: Bytes
  ): IO[Json] =
    for
      osA <- IO(originals(oa.originals("scala-sequence-capture.md"), 2))
      osB <- IO(originals(ob.originals("scala-sequence-capture.md"), 4))
      _ <- IO {
        branchBounds(osA.size, osB.size)
        require(
          originals(stdoutA, 2) == osA && originals(stdoutB, 4) == osB,
          "exact online branch original binding"
        )
        Vector("signed-transaction-0-cbor.md", "signed-transaction-1-cbor.md").foreach(n =>
          require(oa.originals(n) == ob.originals(n), "same independently pinned marker files")
        )
      }
      a <- osA.traverse(o => checked(SequenceInput.block(o)))
      b <- osB.traverse(o => checked(SequenceInput.block(o)))
      ta <- IO(transaction(oa.originals("signed-transaction-0-cbor.md")))
      tb <- IO(transaction(oa.originals("signed-transaction-1-cbor.md")))
      _ <- IO {
        membership(a.flatMap(_.transactionMemos), ta, tb);
        membership(b.flatMap(_.transactionMemos), tb, ta)
        require(
          (a ++ b).map(_.header.hash).distinct.size == a.size + b.size,
          "no shared child or mixed branch originals"
        )
        require(
          (a ++ b).forall(_.header.slot / context.nonces.context.epochLength == context.epoch),
          "same supplied epoch"
        )
      }
      runtime <- CoherentSequence.create[IO](context, 8).flatMap(checked)
      c <- runtime.snapshot
      appliedA <- applyAll(runtime, a)
      tipA <- runtime.snapshot
      rolled <- runtime.rollbackTo(tipA.fence, c.state.acquisition.anchor).flatMap(checked)
      _ <- IO {
        require(rolled.state.revision == 2 * a.size, "nonempty rollback revision arithmetic")
        require(
          ValidatedRestartCapture.projection(rolled.state) == ValidatedRestartCapture.projection(
            c.state
          ),
          "complete rollback anchor projection"
        )
      }
      appliedB <- applyAll(runtime, b)
      fresh <- CoherentSequence.create[IO](context, 8).flatMap(checked)
      freshB <- applyAll(fresh, b)
      endA = appliedA.last.state
      endB = appliedB.last.state
      _ <- IO {
        require(
          ValidatedRestartCapture.projection(endB) == ValidatedRestartCapture.projection(
            freshB.last.state
          ),
          "replacement equals fresh complete B replay"
        )
        require(endB.revision == 2 * a.size + b.size, "replacement revision arithmetic")
        checkStateEffects(
          c.state.ledger.outputMap,
          endA.ledger.outputMap,
          rolled.state.ledger.outputMap,
          endB.ledger.outputMap,
          c.state.ledger.fees,
          endA.ledger.fees,
          rolled.state.ledger.fees,
          endB.ledger.fees,
          ta,
          tb
        )
        NodeAuditCommand.checkProjection(NodeAuditCommand.records(stdoutA), endA)
        NodeAuditCommand.checkProjection(NodeAuditCommand.records(stdoutB), endB)
      }
      // Reference endpoint data is interpreted only after both branches and undo have checked.
      prevA <- checked(CoherentSequenceCommand.compareOracle(context, appliedA, oa))
      prevB <- checked(CoherentSequenceCommand.compareOracle(context, appliedB, ob))
    yield obj(
      "scope" -> str("fork-audit"),
      "passed" -> Json.Lit("true"),
      "capacity" -> Json.Num("8"),
      "nA" -> Json.Num(a.size.toString),
      "nB" -> Json.Num(b.size.toString),
      "contextId" -> str(context.id.hex),
      "anchor" -> point(c.state.acquisition.anchor),
      "offeredB" -> Json.Arr(tipA.state.acquisition.candidates.map(point)),
      "anchorState" -> state(c.state, 0),
      "aState" -> state(endA, a.size),
      "rollbackState" -> state(rolled.state, a.size + 1),
      "bState" -> state(endB, a.size + 1 + b.size),
      "aPrefixes" -> Json.Arr(appliedA.zipWithIndex.map((r, i) => state(r.state, i + 1))),
      "bPrefixes" -> Json.Arr(appliedB.zipWithIndex.map((r, i) => state(r.state, a.size + 2 + i))),
      "transactionA" -> str(ta.id.hex),
      "transactionB" -> str(tb.id.hex),
      "spentInput" -> str(ta.spent),
      "previousEpochNonceComparedA" -> Json.Lit(prevA.toString),
      "previousEpochNonceComparedB" -> Json.Lit(prevB.toString),
      "referencePostStateMatched" -> Json.Lit("true"),
      "followingPeerSelectedBranch" -> Json.Lit("true"),
      "independentChainSelection" -> Json.Lit("false"),
      "fullLedgerValidated" -> Json.Lit("false"),
      "consensusValidated" -> Json.Lit("false")
    )
  private def read(path: String): Bytes =
    val in = Files.newInputStream(Path.of(path))
    val b =
      try in.readNBytes(20 * 1024 * 1024 + 1)
      finally in.close()
    require(b.nonEmpty && b.length <= 20 * 1024 * 1024, "bounded nonempty stdout")
    Bytes.fromArray(b)
  def run(args: List[String]): IO[ExitCode] =
    val work = args match
      case List(context, a, b, stdoutA, stdoutB) =>
        for
          c <- IO.blocking(SequenceInput.load(Path.of(context))).flatMap(checked)
          oa <- IO.blocking(CoherentSequenceCommand.loadOracle(Path.of(a))).flatMap(checked)
          ob <- IO.blocking(CoherentSequenceCommand.loadOracle(Path.of(b))).flatMap(checked)
          sa <- IO.blocking(read(stdoutA)); sb <- IO.blocking(read(stdoutB))
          report <- assess(c, oa, ob, sa, sb)
          _ <- IO.println(ValidatedRestartCapture.canonical(report))
        yield ExitCode.Success
      case _ =>
        IO.raiseError(
          new IllegalArgumentException(
            "ForkAuditCommand CONTEXT A_ORACLE B_ORACLE A_STDOUT B_STDOUT"
          )
        )
    work.timeout(30.seconds).handleErrorWith { e =>
      IO.println(
        ValidatedRestartCapture.canonical(
          obj(
            "scope" -> str("fork-audit"),
            "passed" -> Json.Lit("false"),
            "detail" -> str(Option(e.getMessage).getOrElse("rejected"))
          )
        )
      ).as(ExitCode(2))
    }
