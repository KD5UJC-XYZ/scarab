// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEpochBoundary as B, ConwayRewardStart as R, ConwayRewardPulser as P}
import lab.header.PraosNonceEvolution as N

class SyntheticSuccessorBlockSuite extends munit.FunSuite:
  private def get[E, A](v: Either[E, A]): A = v.fold(e => fail(e.toString), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private val pin = Bytes(Vector.fill(32)(77.toByte))
  private def array(v: V*) = get(Cbor.encode(V.Arr(v.toVector.map(Node(_, Bytes.empty)))))
  private val params = get(
    R.decodePoolParameters(
      array(
        V.Text(R.PoolParameterFormat),
        V.UInt(9),
        V.UInt(0),
        V.UInt(0),
        V.UInt(1),
        V.UInt(0),
        V.UInt(1),
        V.UInt(0),
        V.UInt(1),
        V.UInt(1)
      )
    )
  )
  private val globals = get(
    R.decodePulserGlobals(
      array(
        V.Text(R.PulserGlobalFormat),
        V.UInt(1530),
        V.UInt(1),
        V.UInt(20),
        V.UInt(BigInt("45000000000000000")),
        V.UInt(5)
      )
    )
  )
  private def profile(window: Int) = get(
    CoherentSequence.syntheticRewardProfile(
      params,
      globals,
      window,
      Some(false),
      Some(0),
      Some(false),
      Some(false),
      Some(0),
      Some(false),
      Some(false)
    )
  )

  sys.env.get("STAKE_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    val input = get(SequenceInput.load(dir))
    import ReferenceJson.Json
    def replace(j: Json, path: List[String], v: Json): Json = path match
      case Nil => v
      case key :: rest =>
        val fs = j.asInstanceOf[Json.Obj].fields
        Json.Obj(fs.updated(key, replace(fs(key), rest, v)))
    def render(j: Json): String = j match
      case Json.Obj(fs) =>
        fs.toVector.sortBy(_._1).map((k, v) => "\"" + k + "\":" + render(v)).mkString("{", ",", "}")
      case Json.Arr(xs) => xs.map(render).mkString("[", ",", "]")
      case Json.Str(s)  => "\"" + s + "\""
      case Json.Num(s)  => s
      case Json.Lit(s)  => s
    def json(name: String) = ReferenceJson.parse(input.originals(name))
    val protocol = json("pre-protocol-state.md")
    // Synthetic supplied anchor only: exact signed blocks/parent are unchanged. Shift epoch
    // geometry so the first is a successor, and derive its original verified nonce by tick.
    val files = input.originals
      .updated(
        "transfer-genesis.md",
        raw(render(replace(json("transfer-genesis.md"), List("epochLength"), Json.Num("1530"))))
      )
      .updated(
        "pre-tips.md",
        raw(
          render(
            Json.Arr(
              json("pre-tips.md")
                .asInstanceOf[Json.Arr]
                .values
                .map(j => replace(j, List("epoch"), Json.Num("0")))
            )
          )
        )
      )
      .updated(
        "pre-ledger-state.md",
        raw(render(replace(json("pre-ledger-state.md"), List("lastEpoch"), Json.Num("0"))))
      )
      .updated(
        "pre-protocol-state.md",
        raw(
          render(
            replace(
              replace(
                protocol,
                List("candidateNonce"),
                ReferenceJson.field(protocol, "epochNonce")
              ),
              List("lastEpochBlockNonce"),
              Json.Lit("null")
            )
          )
        )
      )
    val manifest = raw(
      "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.toVector
        .sortBy(_._1)
        .map((k, n) => k + "\t" + ClusterHeaderObservation.sha256(files(n)).hex)
        .mkString("\n") + "\n"
    )
    val context = get(SequenceInput.bind(manifest, files))
    val utxo = get(Bytes.fromHex(new String(files("pre-utxo-cbor.md").toArray, "UTF-8").trim))
    val seed = get(
      ConwayStakeSeed.decode(
        files("pre-ledger-state.md"),
        context.sourcePins("preLedgerSha256"),
        utxo,
        ClusterHeaderObservation.sha256(utxo),
        1530
      )
    )
    val blocks = get(
      ClusterHeaderObservation.capturesBounded(dir.resolve("scala-sequence-capture.md"), 16)
    )
      .map(c => get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block))))
    def runtime(window: Int) = CoherentSequence
      .createWithSyntheticRewards[IO](
        context,
        seed,
        profile(window),
        B.Pots(0, 0, context.ledger.fees, globals.maxSupply),
        Map.empty,
        Map.empty,
        pin
      )
      .map(get(_))
    def candidate(r: CoherentSequence.Runtime[IO], snap: CoherentSequence.Snapshot) = for
      preview <- r
        .prepareSyntheticSuccessor(snap.fence, blocks.head.header.hash, blocks.head.header.slot)
        .map(get(_))
      candidate <- r.prepareSyntheticSuccessorBlock(snap.fence, preview, blocks.head).map(get(_))
    yield candidate
    def same(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
      assertEquals(a.id, b.id)
      assertEquals(a.ledger.id, b.ledger.id)
      assertEquals(a.ledger.environment.id, b.ledger.environment.id)
      assertEquals(a.ledger.outputMap, b.ledger.outputMap)
      assertEquals(a.ledger.fees, b.ledger.fees)
      assertEquals(a.stake.get.id, b.stake.get.id)
      assertEquals(a.stake.get.context.id, b.stake.get.context.id)
      assertEquals(a.stake.get.snapshots, b.stake.get.snapshots)
      assertEquals(a.nonces.id, b.nonces.id)
      assertEquals(a.nonces.fields, b.nonces.fields)
      assertEquals(a.certificates.state.id, b.certificates.state.id)
      assertEquals(a.certificates.state.contextId, b.certificates.state.contextId)
      assertEquals(a.syntheticRewards.get.id, b.syntheticRewards.get.id)

    test("actual successor header uses old-mark leadership and ticked nonce; publish counts once") {
      (for
        r <- runtime(50)
        before <- r.snapshot
        public <- r.prepare(blocks.head)
        _ = assert(public.isLeft)
        pending <- candidate(r, before)
        unchanged <- r.snapshot
        _ = same(before.state, unchanged.state)
        results <- (r.publish(pending), r.publish(pending)).parTupled
        _ = assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
        result = get(Vector(results._1, results._2).find(_.isRight).get)
        after = result.state
        _ = assertEquals(after.ledger.environment.epoch, BigInt(1))
        _ = assertEquals(after.stake.get.epoch, BigInt(1))
        _ = assertEquals(after.stake.get.snapshots.set, before.state.stake.get.snapshots.mark)
        _ = assertEquals(after.nonces.fields.previousEpoch, Some(before.state.nonces.fields.epoch))
        _ = assertEquals(
          result.nonceObservation.epochNonceUsed,
          before.state.nonces.fields.candidate
        )
        _ = assertEquals(
          after.syntheticRewards.get.currentBlocks,
          Map(result.certificateObservation.issuer -> BigInt(1))
        )
        _ = assertEquals(
          after.syntheticRewards.get.previousBlocks,
          before.state.syntheticRewards.get.currentBlocks
        )
        stake = before.state.stake.get.snapshots.mark
          .distribution(result.certificateObservation.issuer)
          .ratio
        _ = assertEquals(after.eligibility.get.headers.head.stake.numerator, stake.numerator)
        _ = assertEquals(after.eligibility.get.headers.head.stake.denominator, stake.denominator)
        _ = assertEquals(after.revision, before.state.revision + 1)
        _ = assert(!after.fullLedgerValidated && !after.consensusValidated)
      yield ()).unsafeToFuture()
    }

    test("early/start/late first block RUPD uses actual slot and original pre-tick environment") {
      Vector(50, 25, 10)
        .traverse_ { window =>
          for
            r <- runtime(window)
            before <- r.snapshot
            pending <- candidate(r, before)
            applied <- r.publish(pending).map(get(_))
            rewards = applied.state.syntheticRewards.get
            _ =
              if window == 50 then assert(rewards.frozen.isEmpty && rewards.pulser.isEmpty)
              else
                val f = rewards.frozen.get
                assertEquals(f.epoch, BigInt(1))
                assertEquals(f.observedSlot, blocks.head.header.slot)
                assertEquals(f.preTickTupleId, before.state.id)
                assertEquals(f.go, before.state.stake.get.snapshots.go)
                assertEquals(f.snapshotFees, before.state.stake.get.snapshots.fees)
                assertEquals(f.previousBlocks, before.state.syntheticRewards.get.previousBlocks)
                assertEquals(
                  rewards.pulser.get.phase,
                  if window == 10 then P.Phase.Complete else P.Phase.Pulsing
                )
            next <- r.snapshot
            second <- r.prepareSyntheticBlock(next.fence, blocks(1)).map(get(_))
            afterSecond <- r.publish(second).map(get(_))
            _ = assertEquals(
              afterSecond.state.syntheticRewards.get.currentBlocks.values.sum,
              BigInt(2)
            )
            _ = assertEquals(
              afterSecond.state.syntheticRewards.get.pots.fees,
              afterSecond.state.ledger.fees
            )
          yield ()
        }
        .unsafeToFuture()
    }

    test(
      "invalid first block, stale preview and foreign capabilities leave every component unchanged"
    ) {
      (for
        a <- runtime(25)
        b <- runtime(25)
        before <- a.snapshot
        preview <- a
          .prepareSyntheticSuccessor(before.fence, blocks.head.header.hash, blocks.head.header.slot)
          .map(get(_))
        bad = {
          val old = blocks.head.header.raw
          val changed = Bytes(old.value.updated(old.size - 1, (old.value.last ^ 1).toByte))
          def substitute(bytes: Bytes): Bytes =
            val index = bytes.value.indexOfSlice(old.value)
            assert(index >= 0)
            Bytes(bytes.value.take(index) ++ changed.value ++ bytes.value.drop(index + old.size))
          get(
            SequenceInput.block(
              BoundedChainFollower.Original(
                substitute(blocks.head.original.envelope),
                substitute(blocks.head.original.block)
              )
            )
          )
        }
        badPreview <- a
          .prepareSyntheticSuccessor(before.fence, bad.header.hash, bad.header.slot)
          .map(get(_))
        badCertificate <- a.prepareSyntheticSuccessorBlock(before.fence, badPreview, bad)
        _ = assert(badCertificate.swap.toOption.get.toString.contains("successor-certificate"))
        mismatch <- a.prepareSyntheticSuccessorBlock(before.fence, preview, blocks(1))
        _ = assert(mismatch.isLeft)
        foreign <- b.prepareSyntheticSuccessorBlock(before.fence, preview, blocks.head)
        _ = assertEquals(foreign, Left(CoherentSequence.Failure.ForeignFence))
        unchanged <- a.snapshot
        _ = same(before.state, unchanged.state)
        valid <- candidate(a, before)
        _ <- a.publish(valid).map(get(_))
        stale <- a.prepareSyntheticSuccessorBlock(before.fence, preview, blocks.head)
        _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleFence))
        now <- a.snapshot
        restored <- a.rollbackTo(now.fence, before.state.acquisition.tip).map(get(_))
        _ = same(before.state, restored.state)
        sibling <- a.prepareSyntheticSuccessorBlock(restored.fence, preview, blocks.head)
        _ = assertEquals(sibling, Left(CoherentSequence.Failure.StaleCandidate))
        rejected <- a.publish(valid)
        _ = assertEquals(rejected, Left(CoherentSequence.Failure.StaleCandidate))
      yield ()).unsafeToFuture()
    }

    test(
      "boundary plus block is one receipt: whole tuple undo, new freeze on replay, codecs reject"
    ) {
      (for
        r <- runtime(10)
        before <- r.snapshot
        pending <- candidate(r, before)
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        canceled <- (entered.complete(()) *> release.get *> r.publish(pending)).start
        _ <- entered.get
        _ <- canceled.cancel
        untouched <- r.snapshot
        _ = same(before.state, untouched.state)
        first <- r.publish(pending).map(get(_))
        snap <- r.snapshot
        v1 = ValidatedCheckpoint.encode(context, snap, pin, 0, 8)
        _ = assert(v1.swap.toOption.get.contains("stake"))
        v2 <- r.exportLocalCheckpoint(pin, pin, 0)
        _ = assert(v2.swap.toOption.get.contains("stake"))
        second <- r.prepareSyntheticBlock(snap.fence, blocks(1)).map(get(_))
        _ <- r.publish(second).map(get(_))
        both <- r.snapshot
        undoSecond <- r.rollbackTo(both.fence, first.state.acquisition.tip).map(get(_))
        _ = same(first.state, undoSecond.state)
        undoBoundary <- r.rollbackTo(undoSecond.fence, before.state.acquisition.tip).map(get(_))
        _ = same(before.state, undoBoundary.state)
        _ = assertEquals(undoBoundary.state.revision, BigInt(4))
        replay <- candidate(r, undoBoundary)
        replayed <- r.publish(replay).map(get(_))
        _ = assertEquals(replayed.state.ledger.outputMap, first.state.ledger.outputMap)
        _ = {
          val a = replayed.state.stake.get.snapshots
          val b = first.state.stake.get.snapshots
          Vector((a.mark, b.mark), (a.set, b.set), (a.go, b.go)).foreach { (x, y) =>
            assertEquals(x.active, y.active); assertEquals(x.pools, y.pools);
            assertEquals(x.total, y.total)
          }
          assertEquals(a.fees, b.fees)
        }
        _ = assertEquals(replayed.state.nonces.fields, first.state.nonces.fields)
        _ = assert(
          replayed.state.syntheticRewards.get.frozen.get.id != first.state.syntheticRewards.get.frozen.get.id
        )
      yield ()).unsafeToFuture()
    }

    test("successor context and frozen capsule survive compaction then rollback and continuation") {
      (for
        r <- runtime(10)
        seed <- r.snapshot
        firstCandidate <- candidate(r, seed)
        first <- r.publish(firstCandidate).map(get(_))
        firstSnapshot <- r.snapshot
        secondCandidate <- r.prepareSyntheticBlock(firstSnapshot.fence, blocks(1)).map(get(_))
        _ <- r.publish(secondCandidate).map(get(_))
        before <- r.snapshot
        compact <- r.advanceAnchor(before.fence, first.state.acquisition.tip).map(get(_))
        _ = assertEquals(
          compact.state.syntheticRewards.get.frozen.get.id,
          first.state.syntheticRewards.get.frozen.get.id
        )
        back <- r.rollbackTo(compact.fence, first.state.acquisition.tip).map(get(_))
        _ = assertEquals(back.state.syntheticRewards.get.id, first.state.syntheticRewards.get.id)
        next <- r.prepareSyntheticBlock(back.fence, blocks(1)).map(get(_))
        continued <- r.publish(next).map(get(_))
        _ = same(continued.state, compact.state)
      yield ()).unsafeToFuture()
    }

    test("coordinator rejects reward ASC/security globals different from the header context") {
      Vector((BigInt(1), BigInt(20), BigInt(1)), (BigInt(1), BigInt(10), BigInt(5)))
        .traverse_ { (n, d, k) =>
          val wrong = get(
            R.decodePulserGlobals(
              array(
                V.Text(R.PulserGlobalFormat),
                V.UInt(1530),
                V.UInt(n),
                V.UInt(d),
                V.UInt(globals.maxSupply),
                V.UInt(k)
              )
            )
          )
          val p = get(
            CoherentSequence.syntheticRewardProfile(
              params,
              wrong,
              50,
              Some(false),
              Some(0),
              Some(false),
              Some(false),
              Some(0),
              Some(false),
              Some(false)
            )
          )
          CoherentSequence
            .createWithSyntheticRewards[IO](
              context,
              seed,
              p,
              B.Pots(0, 0, context.ledger.fees, globals.maxSupply),
              Map.empty,
              Map.empty,
              pin
            )
            .map(r => assert(r.isLeft))
        }
        .unsafeToFuture()
    }
  }
