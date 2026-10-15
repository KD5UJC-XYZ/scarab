// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.ForgeError
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.ledger.VrfCert
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.node.NodeRules
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.consensus.{PraosConfig, PraosState}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.TestLedger.Key
import xyz.kd5ujc.scarab.node.mempool.{Mempool, MempoolEnv, MempoolEvents, TxKit}
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}
import munit.CatsEffectSuite

/** Forging against the real block rules: whatever the forger builds must pass the same `NodeRules`
  * that validate blocks received from peers, and the follower must adopt it.
  */
class ForgeSuite extends CatsEffectSuite:
  private val geometry = EpochGeometry(
    Instant.EPOCH,
    1000,
    epochLength = 1000,
    securityParam = 5,
    activeSlotsCoeff = 0.5
  )
  private val slotsPerKes = BigInt(100)
  private val config = PraosConfig(
    geometry,
    slotsPerKes,
    maxKesEvolutions = 62,
    activeSlotsCoeff = Leader.Fraction.checked(1, 2).toOption.get
  )
  private val globals =
    LedgerGlobals(TestLedger.Supply, 1000, Rational(1, 2), network = 0, updateQuorum = 2)
  private val epochNonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))

  private def hex(b: Array[Byte]) = Bytes.fromArray(b)

  /** A pool with all the stake, its keys and an operational certificate starting at `kesStart`. */
  private final case class Pool(creds: PoolCredentials, cold: Key, stake: Key):
    def id: Bytes = creds.poolId

  private def pool(kesStart: Int, seed: Int = 1): Pool =
    val cold = TestLedger.key()
    val kes = Sum6KesSigning.generate(hex(Array.fill(32)(seed.toByte))).toOption.get
    val vrf =
      Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)((seed + 50).toByte))).toOption.get
    def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)
    val signable = Bytes.fromArray(kes.verificationKey.toArray ++ be8(0) ++ be8(kesStart))
    val oc = Header.OpCert(kes.verificationKey, 0, kesStart, cold.sign(signable))
    Pool(PoolCredentials(vrf, kes, oc, cold.vkey), cold, TestLedger.key())

  private def ledgerWith(p: Pool, funds: Int): LedgerState =
    val base = TxKit.ledger(funds)
    val cred = p.stake.credential
    val params = PoolParams(
      p.id,
      p.creds.vrfKeyHash,
      0,
      0,
      Rational.zero,
      RewardAccount(0, cred),
      Set.empty,
      Bytes.empty,
      None
    )
    base.copy(
      poolDistribution = Snapshot(
        Map(cred -> BigInt(1000000)),
        Map(cred -> p.id),
        Map(p.id -> params)
      )
    )

  private def nodeState(ledger: LedgerState): NodeState =
    val lite = LiteLedger(
      0,
      LiteParams((10, 0), Rational.zero, Nonce.Neutral, 65536, 16384, 1100),
      Map.empty,
      Vector.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      2
    )
    val praos = PraosState(
      epoch = 5,
      Nonce.Neutral,
      Nonce.Neutral,
      epochNonce,
      Nonce.Neutral,
      Nonce.Neutral,
      Map.empty
    )
    NodeState(praos, ledger, BootstrapState.initial(lite))

  private final class Harness(
      val follower: Follower[IO, NodeState],
      val forger: Forger[IO],
      val ref: Ref[IO, VolatileChain[NodeState]],
      val mempool: Mempool[IO],
      val events: Ref[IO, Vector[ForgeEvent]],
      val raws: Ref[IO, Map[Bytes, Bytes]]
  ):
    def chain: IO[VolatileChain[NodeState]] = ref.get
    def seen: IO[Vector[ForgeEvent]] = events.get

  private def harness(
      p: Pool,
      ledger: LedgerState,
      connected: Boolean = true,
      maxTipAge: BigInt = 1000000,
      live: Boolean = false,
      ready: Option[IO[Boolean]] = None,
      offsetMillis: Long = 0
  )(using Handle[IO, NodeError]): IO[Harness] =
    for
      ref <- Ref.of[IO, VolatileChain[NodeState]](
        VolatileChain.genesis(geometry.securityParam, nodeState(ledger))
      )
      given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
      rules = NodeRules[IO](config, globals)
      mempool <- Mempool.create[IO](
        ledger,
        4999,
        MempoolEnv(globals, geometry),
        1000000,
        MempoolEvents.none[IO]
      )
      raws <- Ref.of[IO, Map[Bytes, Bytes]](Map.empty)
      follower <- Follower.create[IO, NodeState](
        rules,
        _ => IO.unit,
        geometry,
        persistence = Some(new xyz.kd5ujc.scarab.storage.ChainPersistence[IO, NodeState]:
          def extended(
              ref: BlockRef,
              raw: Bytes,
              immutable: Vector[BlockRef],
              anchor: xyz.kd5ujc.scarab.consensus.Anchor[NodeState]
          ) = raws.update(_.updated(ref.hash, raw))
          def rolledBack(to: xyz.kd5ujc.scarab.protocol.ChainSync.Point) = IO.unit),
        onTip = Some((c, added) => mempool.sync(c.state.ledger, c.tipRef.get.slot, Set.empty).void)
      )
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      forger <- Forger.create[IO](
        config,
        globals,
        p.creds,
        follower,
        mempool,
        e => seen.update(_ :+ e),
        ready.getOrElse(IO.pure(connected)),
        maxTipAge = Some(maxTipAge),
        // `live`: the real loop on the (test) clock, starting at slot 5000; else steps are driven by hand
        now = Some(
          if live then IO.realTimeInstant.map(_.plusSeconds(5000).plusMillis(offsetMillis))
          else IO.pure(Instant.EPOCH)
        ),
        sleep = if live then None else Some(_ => IO.unit)
      )
    yield new Harness(follower, forger, ref, mempool, seen, raws)

  private def withHandle[A](f: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](f).rescue(e => IO.raiseError(new RuntimeException(e.message)))

  private def forgedIn(h: Harness): IO[Vector[ForgeEvent.Forged]] =
    h.seen.map(_.collect { case f: ForgeEvent.Forged => f })

  test("an elected slot yields a block our rules accept, built from the mempool, and adopted") {
    val p = pool(kesStart = 50)
    val (t1, id1) = TxKit.pay(1)
    val (t2, id2) = TxKit.pay(2)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 3))
        _ <- h.mempool.add(t1)
        _ <- h.mempool.add(t2)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        chain <- h.chain
      yield
        assert(forged.nonEmpty, "f = 1/2 with all the stake: some of 40 slots must be won")
        val first = forged.head
        assertEquals(first.txs, Vector(id1, id2))
        assertEquals(first.blockNo, BigInt(0))
        assertEquals(first.prev, None)
        assertEquals(first.relativeKesPeriod, 0)
        assertEquals(chain.blocks.size, forged.size.min(geometry.securityParam))
        assertEquals(chain.tipRef.map(_.hash), Some(forged.last.hash))
        // consecutive: each block extends the previous one
        forged.sliding(2).foreach {
          case Seq(a, b) => assertEquals(b.prev, Some(a.hash))
          case _         => ()
        }
        // the ledger counted the blocks for the pool and applied the transactions
        assertEquals(chain.state.ledger.blocksCurrent.get(p.id), Some(BigInt(forged.size)))
        assert(!chain.state.ledger.utxo.contains(TestLedger.txIn(1)))
        assert(!chain.state.ledger.utxo.contains(TestLedger.txIn(2)))
        assert(chain.state.ledger.utxo.contains(TestLedger.txIn(3)))
    }
  }

  test("the loop attempts every slot: each slot the pool leads is forged, none is skipped") {
    val p = pool(kesStart = 50, seed = 9)
    // the slots the leadership check elects, computed apart from the forger
    val leads = (5000 until 5200).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    assert(leads.size > 40 && leads.size < 160, leads.size.toString)
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1), live = true)
          fiber <- h.forger.run.start
          _ <- IO.sleep(200500.millis)
          _ <- fiber.cancel
          forged <- forgedIn(h)
          events <- h.seen
        yield
          assertEquals(forged.map(_.slot.toInt).filter(_ < 5200), leads.toVector)
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
      }
    )
  }

  test(
    "the loop follows a system start that is not on a whole second: no slot is lost or doubled"
  ) {
    val p = pool(kesStart = 50, seed = 9)
    val leads = (5000 until 5200).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    // the clock stands 437 ms into a slot's tenth of a second relative to the genesis' whole second
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1), live = true, offsetMillis = 437)
          fiber <- h.forger.run.start
          _ <- IO.sleep(200500.millis)
          _ <- fiber.cancel
          forged <- forgedIn(h)
          events <- h.seen
        yield
          assertEquals(forged.map(_.slot.toInt).filter(_ < 5200), leads.toVector)
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Skipped]), events.toString)
      }
    )
  }

  test("per-epoch counters: checked and led slots, blocks, latency; a summary closes each epoch") {
    val p = pool(kesStart = 50, seed = 9)
    // the clock starts 10 slots before the end of epoch 5 (slots 5000 to 5999)
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1), live = true, offsetMillis = 990000)
          fiber <- h.forger.run.start
          _ <- IO.sleep(30500.millis)
          _ <- fiber.cancel
          forged <- forgedIn(h)
          events <- h.seen
          fifth <- h.forger.stats.epoch(5)
          sixth <- h.forger.stats.epoch(6)
          total <- h.forger.stats.total
        yield
          val summaries = events.collect { case s: ForgeEvent.EpochSummary => s }
          assertEquals(summaries.map(_.epoch), Vector(BigInt(5)))
          assertEquals(summaries.head.stats, fifth)
          assertEquals(fifth.slotsChecked, 10L)
          assertEquals(sixth.slotsChecked, 21L)
          assertEquals(total.leaderSlots, forged.size.toLong)
          assertEquals(total.forged, forged.size.toLong)
          assertEquals(total.adopted, forged.size.toLong)
          assertEquals(fifth.forged, forged.count(_.epoch == 5).toLong)
          assertEquals(sixth.forged, forged.count(_.epoch == 6).toLong)
          assertEquals(total.latencyCount, forged.size.toLong)
          assertEquals(total.latencyMaxMs, 0L) // the test clock does not move during a step
          assertEquals(total.lost + total.failed + total.skippedTotal, 0L)
      }
    )
  }

  test("slots that cannot be checked are counted by reason") {
    val p = pool(kesStart = 50, seed = 7)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), connected = false)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        offline <- h.forger.stats.total
        h2 <- harness(p, ledgerWith(p, 1), maxTipAge = 500)
        _ <- (5000 until 5003).toList.traverse_(s => h2.forger.step(s))
        old <- h2.forger.stats.total
      yield
        assertEquals(offline.skipped, Map(ForgeEpochStats.NotConnected -> 40L))
        assertEquals(offline.slotsChecked, 0L)
        assertEquals(old.skipped, Map(ForgeEpochStats.TipTooOld -> 3L))
    }
  }

  test("slots that pass while a step is still running are reported as skipped") {
    val p = pool(kesStart = 50, seed = 10)
    TestControl.executeEmbed(
      withHandle {
        for
          calls <- Ref.of[IO, Int](0)
          // the first step takes 3.5 s: slots 5001 and 5002 pass meanwhile
          slow = calls
            .getAndUpdate(_ + 1)
            .flatMap(n => IO.sleep(3500.millis).whenA(n == 0).as(true))
          h <- harness(p, ledgerWith(p, 1), live = true, ready = Some(slow))
          fiber <- h.forger.run.start
          _ <- IO.sleep(6500.millis)
          _ <- fiber.cancel
          events <- h.seen
        yield assertEquals(
          events.collect { case ForgeEvent.Skipped(s, _) => s.toInt },
          Vector(5001, 5002)
        )
      }
    )
  }

  test("the mempool is synchronised after adoption: the included transactions leave it") {
    val p = pool(kesStart = 50, seed = 2)
    val (t1, id1) = TxKit.pay(1)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 2))
        _ <- h.mempool.add(t1)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        held <- h.mempool.snapshot
      yield assertEquals(held.entries.map(_.id), Vector.empty[Bytes])
    }
  }

  test("KES evolves across period boundaries and every block still validates") {
    val p = pool(kesStart = 50, seed = 3)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5400).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        events <- h.seen
        chain <- h.chain
      yield
        assertEquals(chain.state.ledger.blocksCurrent.get(p.id), Some(BigInt(forged.size)))
        // slots 5000..5399 are KES periods 50..53 (slotsPerKESPeriod = 100)
        assertEquals(forged.map(_.relativeKesPeriod).distinct.sorted, Vector(0, 1, 2, 3))
        assertEquals(
          events.collect { case ForgeEvent.KesEvolved(_, r) => r }.distinct.sorted,
          Vector(1, 2, 3).filter(r => forged.exists(_.relativeKesPeriod >= r))
        )
        assert(events.collect { case _: ForgeEvent.Failed => 1 }.isEmpty, events.toString)
    }
  }

  test("a pool without stake, or whose VRF key is not the registered one, is never elected") {
    val p = pool(kesStart = 50, seed = 4)
    val stranger = pool(kesStart = 50, seed = 5)
    withHandle {
      for
        h <- harness(stranger, ledgerWith(p, 1))
        _ <- (5000 until 5100).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        events <- h.seen
      yield
        assertEquals(forged, Vector.empty)
        assertEquals(events.collect { case _: ForgeEvent.Failed => 1 }.size, 0)
    }
  }

  test("outside the certificate's KES window forging fails with KeyPeriodOutOfRange") {
    val p = pool(kesStart = 60, seed = 6) // slots 5000.. are periods 50..: before the window
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5030).toList.traverse_(s => h.forger.step(s))
        events <- h.seen
        chain <- h.chain
      yield
        assertEquals(chain.blocks.size, 0)
        assert(
          events.exists {
            case ForgeEvent.Failed(_, ForgeError.KeyPeriodOutOfRange(50, 60, 62)) => true
            case _                                                                => false
          },
          events.toString
        )
    }
  }

  test("nothing is forged before a peer is known, or from a tip that is too old") {
    val p = pool(kesStart = 50, seed = 7)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), connected = false)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        none <- forgedIn(h)
        h2 <- harness(p, ledgerWith(p, 1), maxTipAge = 500)
        // the genesis tip is "slot 0": slot 5000 is far beyond maxTipAge 500
        _ <- h2.forger.step(5000)
        skipped <- h2.seen
      yield
        assertEquals(none, Vector.empty)
        assert(skipped.exists(_.isInstanceOf[ForgeEvent.Skipped]), skipped.toString)
    }
  }

  test("a block built on a tip that has since moved is lost, not adopted") {
    val p = pool(kesStart = 50, seed = 8)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        chain <- h.chain
        // an identical second block on the old tip (the genesis block position)
        again <- h.follower.adoptForged(
          Block.decodeShelley(blockOnGenesis(p, h)).toOption.get.header,
          blockOnGenesis(p, h)
        )
      yield again match
        case Follower.Adoption.Lost(_) => ()
        case other                     => fail(s"expected Lost, got $other")
    }
  }

  /** A valid-looking block with no predecessor (it extends the genesis, not the tip). */
  private def blockOnGenesis(p: Pool, h: Harness): Bytes =
    val body = BlockBuilder.body(Vector.empty)
    val headerBody = BlockBuilder.headerBody(
      0,
      5001,
      None,
      p.creds.coldVkey,
      p.creds.vrf.publicKey,
      VrfCert(Bytes.fromArray(new Array[Byte](64)), Bytes.fromArray(new Array[Byte](80))),
      body.size,
      body.hash,
      p.creds.opCert,
      (10, 0)
    )
    BlockBuilder.block(headerBody, Bytes.fromArray(new Array[Byte](448)), body)

  test(
    "a slot battle is decided by chain selection: of two blocks at one height exactly one wins"
  ) {
    val p = pool(kesStart = 50, seed = 10)
    val ledger = ledgerWith(p, 1)
    val elected = (5000 until 5200).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    val pair = elected.zip(elected.tail).find((a, b) => b - a <= 5).get
    withHandle {
      for
        a <- harness(p, ledger)
        b <- harness(
          pool(kesStart = 50, seed = 10).copy(creds = p.creds, cold = p.cold, stake = p.stake),
          ledger
        )
        _ <- a.forger.step(pair._1)
        _ <- b.forger.step(pair._2)
        blockA <- a.chain.map(_.tipRef.get)
        blockB <- b.chain.map(_.tipRef.get)
        rawA <- a.raws.get.map(_(blockA.hash))
        rawB <- b.raws.get.map(_(blockB.hash))
        headerA = Block.decodeShelley(rawA).toOption.get.header
        headerB = Block.decodeShelley(rawB).toOption.get.header
        toA <- a.follower.adoptForged(headerB, rawB)
        toB <- b.follower.adoptForged(headerA, rawA)
        tipA <- a.chain.map(_.tipRef.map(_.hash))
        tipB <- b.chain.map(_.tipRef.map(_.hash))
      yield
        val bWins = xyz.kd5ujc.scarab.consensus.ChainSelection.prefers(
          xyz.kd5ujc.scarab.consensus.ChainSelection.Tiebreaker.Restricted(5),
          xyz.kd5ujc.scarab.consensus.SelectView.of(headerA),
          xyz.kd5ujc.scarab.consensus.SelectView.of(headerB)
        )
        // exactly one of the two blocks is preferred, and each node ends on that one
        if bWins then
          assert(toA.isInstanceOf[Follower.Adoption.Adopted], toA.toString)
          assert(toB.isInstanceOf[Follower.Adoption.Lost], toB.toString)
        else
          assert(toA.isInstanceOf[Follower.Adoption.Lost], toA.toString)
          assert(toB.isInstanceOf[Follower.Adoption.Adopted], toB.toString)
        val winner = if bWins then blockB.hash else blockA.hash
        assertEquals(tipA, Some(winner))
        assertEquals(tipB, Some(winner))
    }
  }

  test("a block our rules refuse is Invalid and leaves the chain alone") {
    val p = pool(kesStart = 50, seed = 9)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        raw = blockOnGenesis(p, h)
        result <- h.follower.adoptForged(Block.decodeShelley(raw).toOption.get.header, raw)
        chain <- h.chain
      yield
        assert(result.isInstanceOf[Follower.Adoption.Invalid], result.toString)
        assertEquals(chain.blocks.size, 0)
    }
  }

  test("body encoding: the computed size equals the encoded size and the block decodes back") {
    val (t1, _) = TxKit.pay(1)
    val (t2, _) = TxKit.pay(2)
    def entry(raw: Bytes) = MempoolStateFixture.entry(raw)
    val pieces = Vector(t1, t2).map(r => BlockBuilder.piece(entry(r)).toOption.get)
    List(Vector.empty, pieces.take(1), pieces).foreach { ps =>
      val body = BlockBuilder.body(ps)
      assertEquals(BlockBuilder.bodySize(ps), body.size)
    }
    val p = pool(50)
    val body = BlockBuilder.body(pieces)
    val hb = BlockBuilder.headerBody(
      7,
      123456,
      Some(Blake2b.hash256.hash(Bytes(Vector(1)))),
      p.creds.coldVkey,
      p.creds.vrf.publicKey,
      VrfCert(Bytes.fromArray(new Array[Byte](64)), Bytes.fromArray(new Array[Byte](80))),
      body.size,
      body.hash,
      p.creds.opCert,
      (10, 3)
    )
    val raw = BlockBuilder.block(hb, Bytes.fromArray(new Array[Byte](448)), body)
    val decoded = Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley]
    assertEquals(decoded.bodySize, body.size)
    assertEquals(decoded.bodyHash, body.hash)
    assertEquals(decoded.header.bodyHash, body.hash)
    assertEquals(decoded.header.blockNo, BigInt(7))
    assertEquals(decoded.header.slot, BigInt(123456))
    assertEquals(decoded.header.protocolVersion, (BigInt(10), BigInt(3)))
    assertEquals(decoded.header.bodyRaw, hb)
    assertEquals(decoded.transactions.map(_.id), body.ids)
    assertEquals(decoded.era, xyz.kd5ujc.scarab.protocol.Era.Conway)
  }

  test("leadership: the probability of winning follows the stake, and a win is checkable") {
    val key = Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)(9.toByte))).toOption.get
    val f = Leader.Fraction.checked(1, 20).toOption.get
    def wins(stake: Int, total: Int) =
      (0 until 4000).count(s => Leadership.check(s, epochNonce, key, stake, total, f).isRight)
    val full = wins(1, 1)
    val tenth = wins(1, 10)
    assert(full > 120 && full < 290, s"expected about 200 of 4000 at f = 1/20, got $full")
    assert(
      tenth > 5 && tenth < 50,
      s"expected about 20 of 4000 at a tenth of the stake, got $tenth"
    )
    assertEquals(Leadership.check(1, epochNonce, key, 0, 10, f), Left(ForgeError.NotLeader(1)))
  }
