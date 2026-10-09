// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.ConwayStake as Stake

class CoherentStakeSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def bounded(path: Path): Bytes =
    val in = Files.newInputStream(path)
    try
      val bytes = in.readNBytes(4194305)
      assert(bytes.nonEmpty && bytes.length <= 4194304)
      Bytes.fromArray(bytes)
    finally in.close()
  private val pin = Bytes(Vector.fill(32)(1.toByte))
  private def check(s: CoherentSequence.State): Unit =
    val stake = s.stake.get
    assertEquals(stake.ledgerId, s.ledger.id)
    assertEquals(stake.revision, s.revision)
    assertEquals(stake.slot, s.ledger.slot)
    assertEquals(stake.instantaneous, get(Stake.recompute(s.ledger.outputMap)))
    assertEquals(stake.utxo, get(Stake.decodeUtxo(s.ledger.outputMap)))
    assert(!s.fullLedgerValidated && !s.consensusValidated)

  sys.env.get("STAKE_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def read(n: String) = bounded(dir.resolve(n))
    def context = get(SequenceInput.load(dir))
    def prepared =
      val c = context
      val utxo = get(Bytes.fromHex(new String(read("pre-utxo-cbor.md").toArray, "UTF-8").trim))
      get(
        ConwayStakeSeed.decode(
          read("pre-ledger-state.md"),
          c.sourcePins("preLedgerSha256"),
          utxo,
          ClusterHeaderObservation.sha256(utxo),
          c.nonces.context.epochLength
        )
      )
    def blocks = get(
      ClusterHeaderObservation.capturesBounded(dir.resolve("scala-sequence-capture.md"), 16)
    )
      .take(4)
      .map(c => get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block))))
    def runtime = CoherentSequence.createWithStake[IO](context, prepared, 8).map(get(_))
    def publish(r: CoherentSequence.Runtime[IO], b: SequenceInput.Block) =
      r.prepare(b).map(get(_)).flatMap(r.publish).map(get(_))

    test("seed refuses alternate ledger bytes even when the semantic seed attaches") {
      val p = prepared
      val different = Bytes(read("pre-ledger-state.md").value :+ 32.toByte)
      val alternative = get(
        ConwayStakeSeed.decode(
          different,
          ClusterHeaderObservation.sha256(different),
          p.utxo,
          ClusterHeaderObservation.sha256(p.utxo),
          p.context.epochLength
        )
      )
      assert(alternative.attach(Stake.owner(), context.ledger).isRight)
      CoherentSequence
        .createWithStake[IO](context, alternative, 8)
        .map { result =>
          assert(result.isLeft)
          assert(result.swap.toOption.get.toString.contains("stake-source"))
        }
        .unsafeToFuture()
    }

    test("atomic stake publication keeps exact ledger/revision and full-recompute parity") {
      (for
        r <- runtime
        bare <- CoherentSequence.create[IO](context, 8).map(get(_))
        seed <- r.snapshot
        without <- bare.snapshot
        _ = assert(seed.state.id != without.state.id)
        _ = check(seed.state)
        _ <- blocks.traverse_ { block =>
          for
            before <- r.snapshot
            candidate <- r.prepare(block).map(get(_))
            unchanged <- r.snapshot
            _ = assertEquals(unchanged.state.id, before.state.id)
            results <- (r.publish(candidate), r.publish(candidate)).parTupled
            _ = assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
            next <- r.snapshot
            _ = check(next.state)
            _ = assertEquals(next.state.stake.get.context.accounts, prepared.context.accounts)
            _ = assertEquals(next.state.stake.get.snapshots, seed.state.stake.get.snapshots)
          yield ()
        }
        next <- r.snapshot
        _ = assert(next.state.ledger.outputMap != seed.state.ledger.outputMap)
      yield ()).unsafeToFuture()
    }

    test(
      "rollback restores exact stake content with fresh revision and rejects pre-rollback candidates"
    ) {
      (for
        r <- runtime
        seed <- r.snapshot
        first <- publish(r, blocks.head)
        pending <- r.prepare(blocks(1)).map(get(_))
        oldFence <- r.snapshot
        _ <- publish(r, blocks(1))
        before <- r.snapshot
        restored <- r.rollbackTo(before.fence, seed.state.acquisition.tip).map(get(_))
        _ = check(restored.state)
        _ = assertEquals(restored.state.id, seed.state.id)
        _ = assertEquals(restored.state.stake.get.id, seed.state.stake.get.id)
        _ = assert(restored.state.revision > before.state.revision)
        _ <- publish(r, blocks.head)
        stale <- r.publish(pending)
        _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleCandidate))
        staleFence <- r.rollbackTo(oldFence.fence, seed.state.acquisition.tip)
        _ = assertEquals(staleFence, Left(CoherentSequence.Failure.StaleFence))
        replay <- publish(r, blocks(1))
        _ = check(replay.state)
        _ = assertEquals(replay.state.id, before.state.id)
      yield ()).unsafeToFuture()
    }

    test("synthetic signed spend publishes nonzero instantaneous stake loss and undo restores it") {
      // Explicit supplied synthetic anchor, not native monetary/reference evidence. Reuse the
      // existing signature and payment credential; only the spent output's stake credential changes.
      def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
      val input = context
      val signed = blocks.find(_.transactionMemos.nonEmpty).get
      def arr(n: Node) = n.value.asInstanceOf[V.Arr].value
      val body =
        arr(get(Cbor.decode(signed.transactionMemos.head))).head.value.asInstanceOf[V.Map].value
      val inputs = body.find(_._1.value == V.UInt(0)).get._2
      val refs = inputs.value match
        case V.Tag(_, inner) => arr(inner)
        case _               => arr(inputs)
      val target = arr(refs.head).map(_.value)
      val credential = Stake.Credential(false, Bytes(Vector.fill(28)(0xab.toByte)))
      val entries = get(Cbor.decode(input.ledger.outputMap)).value.asInstanceOf[V.Map].value
      val changed = entries.map { (key, out) =>
        if arr(key).map(_.value) != target then key -> out
        else
          def replace(a: Node): Node =
            val bytes = a.value.asInstanceOf[V.ByteString].value
            val kind = (bytes.value.head & 255) >>> 4
            assert(kind == 0 || kind == 6)
            Node(
              V.ByteString(
                Bytes(Vector(0.toByte) ++ bytes.value.slice(1, 29) ++ credential.hash.value)
              ),
              Bytes.empty
            )
          val value = out.value match
            case V.Arr(xs) => V.Arr(xs.updated(0, replace(xs.head)))
            case V.Map(xs) =>
              V.Map(xs.map((k, v) => (k, if k.value == V.UInt(0) then replace(v) else v)))
            case _ => fail("output shape")
          key -> Node(value, Bytes.empty)
      }
      val utxo = get(Cbor.encode(V.Map(changed)))
      val totals = get(Stake.recompute(utxo))
      assert(totals(credential) > 0)
      import ReferenceJson.Json
      def replaceJson(j: Json, path: List[String], value: Json): Json = path match
        case Nil => value
        case key :: rest =>
          val fields = j.asInstanceOf[Json.Obj].fields
          Json.Obj(fields.updated(key, replaceJson(fields(key), rest, value)))
      def quote(s: String): String = "\"" + s.map(c => f"\\u${c.toInt}%04x").mkString + "\""
      def render(j: Json): String = j match
        case Json.Obj(xs) =>
          xs.toVector.sortBy(_._1).map((k, v) => quote(k) + ":" + render(v)).mkString("{", ",", "}")
        case Json.Arr(xs) => xs.map(render).mkString("[", ",", "]")
        case Json.Str(s)  => quote(s)
        case Json.Num(s)  => s
        case Json.Lit(s)  => s
      val json = replaceJson(
        ReferenceJson.parse(input.originals("pre-ledger-state.md")),
        List("stateBefore", "esLState", "utxoState", "stake", "credentials"),
        Json.Obj(totals.map((c, n) => c.key -> Json.Num(n.toString)))
      )
      val ledgerBytes = raw(render(json))
      val files = input.originals
        .updated("pre-ledger-state.md", ledgerBytes)
        .updated("pre-utxo-cbor.md", raw(utxo.hex))
      val manifest = raw(
        "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.toVector
          .sortBy(_._1)
          .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
          .mkString("\n") + "\n"
      )
      val synthetic = get(SequenceInput.bind(manifest, files))
      val stakeSeed = get(
        ConwayStakeSeed.decode(
          ledgerBytes,
          synthetic.sourcePins("preLedgerSha256"),
          utxo,
          ClusterHeaderObservation.sha256(utxo),
          synthetic.nonces.context.epochLength
        )
      )
      (for
        r <- CoherentSequence.createWithStake[IO](synthetic, stakeSeed, 8).map(get(_))
        before <- r.snapshot
        _ = assertEquals(before.state.stake.get.instantaneous(credential), totals(credential))
        _ <- blocks.takeWhile(_.header.hash != signed.header.hash).traverse_(b => publish(r, b))
        accepted <- publish(r, signed)
        _ = check(accepted.state)
        _ = assert(!accepted.state.stake.get.instantaneous.contains(credential))
        _ = assert(accepted.state.stake.get.instantaneous != before.state.stake.get.instantaneous)
        applied <- r.snapshot
        restored <- r.rollbackTo(applied.fence, before.state.acquisition.tip).map(get(_))
        _ = check(restored.state)
        _ = assertEquals(restored.state.id, before.state.id)
        _ = assertEquals(
          restored.state.stake.get.instantaneous,
          before.state.stake.get.instantaneous
        )
        _ = assertEquals(restored.state.stake.get.id, before.state.stake.get.id)
        _ = assert(restored.state.revision > applied.state.revision)
      yield ()).unsafeToFuture()
    }

    test("foreign candidates and fences cannot publish stake or ledger") {
      (for
        a <- runtime
        b <- runtime
        before <- b.snapshot
        candidate <- a.prepare(blocks.head).map(get(_))
        foreign <- b.publish(candidate)
        _ = assertEquals(foreign, Left(CoherentSequence.Failure.ForeignCandidate))
        fence <- a.snapshot
        refused <- b.rollbackTo(fence.fence, before.state.acquisition.tip)
        _ = assertEquals(refused, Left(CoherentSequence.Failure.ForeignFence))
        after <- b.snapshot
        _ = assertEquals(after.state.id, before.state.id)
        _ = check(after.state)
      yield ()).unsafeToFuture()
    }

    test("compaction retains exact stake undo and both incomplete durable codecs refuse export") {
      val window = Path.of(sys.env("STAKE_WINDOW_EVIDENCE"))
      def readWindow(n: String) = bounded(window.resolve(n))
      val context = get(SequenceInput.load(window))
      val utxo =
        get(Bytes.fromHex(new String(readWindow("pre-utxo-cbor.md").toArray, "UTF-8").trim))
      val seed = get(
        ConwayStakeSeed.decode(
          readWindow("pre-ledger-state.md"),
          context.sourcePins("preLedgerSha256"),
          utxo,
          ClusterHeaderObservation.sha256(utxo),
          context.nonces.context.epochLength
        )
      )
      val runtime = CoherentSequence.createWithStake[IO](context, seed, 8).map(get(_))
      val blocks = get(
        ClusterHeaderObservation.capturesBounded(window.resolve("scala-sequence-capture.md"), 16)
      )
        .take(3)
        .map(c =>
          get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block)))
        )
      assertEquals(blocks.size, 3)
      (for
        r <- runtime
        seed <- r.snapshot
        v1 = ValidatedCheckpoint.encode(context, seed, pin, 0, 8)
        _ = assert(v1.swap.toOption.get.contains("stake"))
        first <- publish(r, blocks.head)
        _ <- publish(r, blocks(1))
        third <- publish(r, blocks(2))
        before <- r.snapshot
        compact <- r.advanceAnchor(before.fence, first.state.acquisition.tip).map(get(_))
        _ = check(compact.state)
        _ = assertEquals(compact.state.stake.get.id, third.state.stake.get.id)
        v2 <- r.exportLocalCheckpoint(pin, pin, 0)
        _ = assert(v2.swap.toOption.get.contains("stake"))
        restored <- r.rollbackTo(compact.fence, first.state.acquisition.tip).map(get(_))
        _ = check(restored.state)
        _ = assertEquals(restored.state.stake.get.id, first.state.stake.get.id)
        unavailable <- r.rollbackTo(restored.fence, seed.state.acquisition.tip)
        _ = assertEquals(unavailable, Left(CoherentSequence.Failure.OutsideRetainedWindow))
        _ <- publish(r, blocks(1))
        replay <- publish(r, blocks(2))
        _ = check(replay.state)
        _ = assertEquals(replay.state.id, compact.state.id)
      yield ()).unsafeToFuture()
    }

    test(
      "cancellation before publication leaves tuple unchanged; racing publication remains whole"
    ) {
      (for
        r <- runtime
        before <- r.snapshot
        candidate <- r.prepare(blocks.head).map(get(_))
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        stopped <- (entered.complete(()) *> release.get *> r.publish(candidate)).start
        _ <- entered.get
        _ <- stopped.cancel
        unchanged <- r.snapshot
        _ = assertEquals(unchanged.state.id, before.state.id)
        racing <- r.publish(candidate).start
        _ <- racing.cancel
        settled <- r.snapshot
        _ = check(settled.state)
        _ = assert(
          settled.state.revision == before.state.revision || settled.state.revision == before.state.revision + 1
        )
      yield ()).unsafeToFuture()
    }
  }
