// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, PraosConfig, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Header,
  NodeError,
  OCertError,
  PeerError,
  Prepared,
  ProviderDefect,
  ValidationError,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{EraBlockBody, EraTx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.rules.ShelleyUtxow
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, ReferenceBuild}

/** The signature checks of queued blocks made ahead of the worker ([[VerifyAhead]], #1499): which
  * blocks the verifier verifies, skips or leaves to the worker, and how the worker finds them; and
  * that the node with the checks made ahead decides exactly as without them: the same states, the
  * same events, the same refusal of a block with a bad signature in the middle of the queue, the
  * same blame, whether the checks were made before the worker reached a block, while it did, or not
  * at all.
  */
class VerifyAheadSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  override def munitIOTimeout: FiniteDuration = 120.seconds

  private lazy val forged: ForgedChain =
    ForgedChain.forge(0 until 90, epoch = 0).unsafeRunSync()(using munitIORuntime)

  private def header(i: Int): Header = forged.headers(i)
  private val boom = new IllegalStateException("provider broke")
  private def raw(i: Int): Bytes = forged.raws(i)

  // ------------------------------------------------------------------ the verifier

  /** The gates of a block's verification: when it starts, its release, and when it is recorded. */
  private final case class Gates(
      started: Deferred[IO, Unit],
      release: Deferred[IO, Unit],
      recorded: Deferred[IO, Unit]
  )

  /** A verifier over the forged blocks that records what it verified and dropped. A block in
    * `gated` says when its verification starts, waits for its release and says when it is recorded;
    * one in `unwanted` is one the worker would pass by; one in `failing` fails after it is
    * recorded; one in `unchecked` is one whose checks the cache cannot all make.
    */
  private final class Probe(
      gated: Map[Bytes, Gates],
      val verified: Ref[IO, Vector[Bytes]],
      val discarded: Ref[IO, Vector[Bytes]]
  ):
    def started(i: Int): IO[Unit] = gated(header(i).hash).started.get
    def release(i: Int): IO[Unit] = gated(header(i).hash).release.complete(()).void
    def recorded(i: Int): IO[Unit] = gated(header(i).hash).recorded.get

    def verifier(
        unwanted: Set[Int] = Set.empty,
        failing: Set[Int] = Set.empty,
        unchecked: Set[Int] = Set.empty
    ): VerifyAhead.Verifier[IO] =
      def in(set: Set[Int], h: Header) = set.exists(header(_).hash == h.hash)
      VerifyAhead.Verifier[IO](
        h => IO.pure(!in(unwanted, h)),
        (h, _) =>
          gated.get(h.hash).traverse_(g => g.started.complete(()) *> g.release.get) *>
            verified.update(_ :+ h.hash) *>
            gated.get(h.hash).traverse_(_.recorded.complete(())) *>
            IO.raiseError(new IllegalStateException("verifier broke")).whenA(in(failing, h)) *>
            IO.pure(!in(unchecked, h)),
        hash => discarded.update(_ :+ hash)
      )

  /** A probe whose blocks `gated` wait for their release, with the [[Sentinel]] saying when the
    * verifier has finished everything asked for before it.
    */
  private def probe(gated: Int*): IO[Probe] =
    (
      (gated :+ Sentinel).toVector
        .traverse(i =>
          (Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit])
            .mapN(Gates.apply)
            .map(header(i).hash -> _)
        ),
      Ref.of[IO, Vector[Bytes]](Vector.empty),
      Ref.of[IO, Vector[Bytes]](Vector.empty)
    ).mapN((g, v, d) => Probe(g.toMap, v, d))

  private val Sentinel = 30

  /** Until the verifier has finished every block asked for before, and recorded the sentinel: the
    * verifier takes one block at a time, in order.
    */
  private def drained(p: Probe, ahead: VerifyAhead[IO]): IO[Unit] =
    ahead.ahead(header(Sentinel), raw(Sentinel)) *> p.release(Sentinel) *> p.recorded(Sentinel)

  private def hashes(is: Int*): Vector[Bytes] = is.toVector.map(header(_).hash)

  test("a block verified while queued is a hit, and verified once") {
    for
      p <- probe()
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> ahead.ahead(header(0), raw(0))
          _ <- drained(p, ahead)
          reach <- ahead.reached(header(0).hash)
          counts <- ahead.counts
          verified <- p.verified.get
          held <- ahead.held.get
        yield
          assertEquals(reach, VerifyAhead.Reach.Hit)
          assertEquals(counts, VerifyAhead.Counts(1, 0, 0, 0))
          assertEquals(verified, hashes(0, Sentinel))
          assertEquals(held.keySet, Set(header(Sentinel).hash))
      }
    yield ()
  }

  test("a block being verified when the worker reaches it is joined: the worker waits for it") {
    for
      p <- probe(0)
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> p.started(0)
          // the worker reaches the block while it is being verified; what was verified when the
          // worker goes on
          joining <- ahead.reached(header(0).hash).flatMap(r => p.verified.get.map(r -> _)).start
          // released only once the worker has taken the block from the verifier
          _ <- ahead.held.waitUntil(!_.contains(header(0).hash)) *> p.release(0)
          joined <- joining.joinWithNever
          (reach, seen) = joined
          _ <- drained(p, ahead)
          verified <- p.verified.get
          discarded <- p.discarded.get
          held <- ahead.held.get
        yield
          assertEquals(reach, VerifyAhead.Reach.Joined)
          assertEquals(seen, hashes(0))
          assertEquals(verified, hashes(0, Sentinel))
          assertEquals(discarded, Vector.empty)
          assert(!held.contains(header(0).hash))
      }
    yield ()
  }

  test("a queued block the worker reaches first is a miss and is never verified ahead") {
    for
      p <- probe(0)
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> ahead.ahead(header(1), raw(1)) *> p.started(0)
          reach <- ahead.reached(header(1).hash)
          _ <- p.release(0) *> drained(p, ahead)
          verified <- p.verified.get
          counts <- ahead.counts
        yield
          assertEquals(reach, VerifyAhead.Reach.Miss)
          assertEquals(verified, hashes(0, Sentinel))
          assertEquals(counts, VerifyAhead.Counts(0, 0, 1, 0))
      }
    yield ()
  }

  test("a queued block the worker passes by is never verified") {
    for
      p <- probe(0)
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> ahead.ahead(header(1), raw(1)) *> p.started(0)
          _ <- ahead.passed(header(1).hash)
          _ <- p.release(0) *> drained(p, ahead)
          verified <- p.verified.get
          discarded <- p.discarded.get
          held <- ahead.held.get
          counts <- ahead.counts
        yield
          assertEquals(verified, hashes(0, Sentinel))
          assertEquals(discarded, Vector.empty)
          assert(!held.contains(header(1).hash))
          assertEquals(counts, VerifyAhead.Counts.zero)
      }
    yield ()
  }

  test("a block passed by while being verified has its decoded block dropped once verified") {
    for
      p <- probe(0)
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> p.started(0)
          _ <- ahead.passed(header(0).hash)
          early <- p.discarded.get
          _ <- p.release(0) *> drained(p, ahead)
          discarded <- p.discarded.get
          held <- ahead.held.get
        yield
          assertEquals(early, Vector.empty)
          assertEquals(discarded, hashes(0))
          assert(!held.contains(header(0).hash))
      }
    yield ()
  }

  test("a verified block passed by has its decoded block dropped at once") {
    for
      p <- probe()
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> drained(p, ahead)
          _ <- ahead.passed(header(0).hash)
          discarded <- p.discarded.get
          held <- ahead.held.get
        yield
          assertEquals(discarded, hashes(0))
          assert(!held.contains(header(0).hash))
      }
    yield ()
  }

  test("a block the worker would pass by is not verified, and is a miss if it is reached") {
    for
      p <- probe()
      _ <- VerifyAhead.resource[IO](p.verifier(unwanted = Set(0))).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> drained(p, ahead)
          reach <- ahead.reached(header(0).hash)
          verified <- p.verified.get
        yield
          assertEquals(reach, VerifyAhead.Reach.Miss)
          assertEquals(verified, hashes(Sentinel))
      }
    yield ()
  }

  test("a block whose checks the cache cannot all make is a miss") {
    for
      p <- probe()
      _ <- VerifyAhead.resource[IO](p.verifier(unchecked = Set(0))).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> drained(p, ahead)
          reach <- ahead.reached(header(0).hash)
        yield assertEquals(reach, VerifyAhead.Reach.Miss)
      }
    yield ()
  }

  test("over the cache, a joined block leaves no decoded copy once the rules took theirs") {
    val cache = VerificationCache.unsafe(2)
    val target = 3
    for
      started <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      verifier = VerifyAhead.Verifier[IO](
        _ => IO.pure(true),
        (h, bytes) =>
          (started.complete(()) *> release.get).whenA(h.hash == header(target).hash) *>
            cache.warm[IO](Vector(bytes), ForgedChain.config, 1) *> IO(cache.holds(h.hash)),
        hash => IO(cache.takePrepared(hash)).void
      )
      _ <- VerifyAhead
        .resource[IO](verifier)
        .use { ahead =>
          for
            _ <- ahead.ahead(header(target), raw(target)) *> started.get
            joining <- ahead.reached(header(target).hash).start
            _ <- ahead.held.waitUntil(!_.contains(header(target).hash)) *> release.complete(())
            reach <- joining.joinWithNever
            // the worker's own preparation, then the rules take the decoded block
            _ <- cache.warm[IO](Vector(raw(target)), ForgedChain.config, 2)
            taken <- IO(cache.takePrepared(header(target).hash))
            left <- IO(cache.takePrepared(header(target).hash).isDefined)
          yield
            assertEquals(reach, VerifyAhead.Reach.Joined)
            assert(taken.isDefined)
            assert(!left, "a decoded copy was left in the cache")
        }
        .guarantee(IO(cache.shutdown()))
    yield ()
  }

  test("a block whose verdicts a clear dropped is not held, and the worker checks it again") {
    // maps of one verdict each: the third block's warming clears what the first two left
    val cache =
      VerificationCache.unsafe(2, bounds = VerificationCache.Bounds(1, 4096, 16384))
    cache.noteEpochNonce(forged.genesis.ledgerEpoch, forged.genesis.protocol.epochNonce)
    def warmed(i: Int) = cache.warm[IO](Vector(raw(i)), ForgedChain.config, 1)
    for
      _ <- warmed(0)
      first <- IO(cache.holds(header(0).hash))
      _ <- warmed(1) *> warmed(2)
      held <- IO((0 to 2).map(i => cache.holds(header(i).hash)))
      asked <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      idle <- VerifyAhead.idle[IO]
      prep = BlockSelection.Before.delivered[IO](
        None,
        blocks => asked.update(_ ++ blocks) *> cache.warm[IO](blocks, ForgedChain.config, 1),
        hash => IO(cache.holds(hash)),
        idle,
        (_, _) => IO.unit
      )
      _ <- Handle
        .allow[NodeError](List(2, 0).traverse_(i => prep.prepare(header(i), raw(i))))
        .rescue(e => IO(fail(e.message)))
      preverified <- asked.get
      counts <- idle.counts
      _ <- IO(cache.shutdown())
    yield
      assert(first, "the first block is held once warmed")
      assertEquals(held, Vector(false, false, true))
      // the block still held is prepared without its checks, the one whose verdicts were dropped
      // with them
      assertEquals(preverified, Vector(raw(0)))
      assertEquals(counts.skipped, 1L)
  }

  test("a block warmed while a clear runs is not held, during the clear nor after it") {
    val cache = VerificationCache.unsafe(2)
    cache.noteEpochNonce(forged.genesis.ledgerEpoch, forged.genesis.protocol.epochNonce)
    IO.blocking {
      // the warming runs inside the clear: it begins and ends while the clear is under way, as a
      // warming on another thread may, and finds the maps as the clear leaves them so far
      val during = new java.util.concurrent.atomic.AtomicReference(Option.empty[Boolean])
      cache.evicting {
        cache.warm[IO](Vector(raw(0)), ForgedChain.config, 1).unsafeRunSync()(using munitIORuntime)
        during.set(Some(cache.holds(header(0).hash)))
      }
      (during.get, cache.holds(header(0).hash))
    }.flatMap { (during, after) =>
      // warmed again with no clear, it is held
      cache.warm[IO](Vector(raw(0)), ForgedChain.config, 1).map { _ =>
        assertEquals((during, after), (Some(false), false))
        assert(cache.holds(header(0).hash), "warmed with no clear, the block is held")
      }
    }.guarantee(IO(cache.shutdown()))
  }

  test("a block warmed under another epoch nonce than the one noted now is not held") {
    val cache = VerificationCache.unsafe(2)
    cache.noteEpochNonce(forged.genesis.ledgerEpoch, forged.genesis.protocol.epochNonce)
    cache
      .warm[IO](Vector(raw(0)), ForgedChain.config, 1)
      .map { _ =>
        val before = cache.holds(header(0).hash)
        cache.noteEpochNonce(
          forged.genesis.ledgerEpoch,
          xyz.kd5ujc.scarab.ledger.Nonce.Hash(Bytes(Vector.fill(32)(1.toByte)))
        )
        assertEquals((before, cache.holds(header(0).hash)), (true, false))
      }
      .guarantee(IO(cache.shutdown()))
  }

  test("a block whose check met a provider defect is not held: the worker asks for its checks") {
    val defect = ProviderDefect.Kes(xyz.kd5ujc.scarab.crypto.kes.Sum6Kes.Failure.Defect(boom))
    // the real verifiers, except that the certificate of the second block meets a defect
    val failing = new Verdicts:
      export Verdicts.Direct.{keyHash, praosVrf, takePrepared, tpraosVrf, witness}
      def opCert(
          h: Header.Shelley,
          slotsPerKesPeriod: BigInt,
          maxKesEvolutions: BigInt
      ): Either[OCertError | ProviderDefect, Unit] =
        if h.hash == header(1).hash then Left(defect)
        else Verdicts.Direct.opCert(h, slotsPerKesPeriod, maxKesEvolutions)
    val cache = VerificationCache.unsafe(2, failing)
    cache.noteEpochNonce(forged.genesis.ledgerEpoch, forged.genesis.protocol.epochNonce)
    cache
      .warm[IO](Vector(raw(0), raw(1), raw(2)), ForgedChain.config, 2)
      .map(_ => (0 to 2).map(i => cache.holds(header(i).hash)))
      .guarantee(IO(cache.shutdown()))
      .map(held => assertEquals(held, Vector(true, false, true)))
  }

  test("a verification that fails is discarded: a miss, and the verifier goes on") {
    for
      p <- probe()
      _ <- VerifyAhead.resource[IO](p.verifier(failing = Set(0))).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> ahead.ahead(header(1), raw(1))
          _ <- drained(p, ahead)
          first <- ahead.reached(header(0).hash)
          second <- ahead.reached(header(1).hash)
          verified <- p.verified.get
        yield
          assertEquals((first, second), (VerifyAhead.Reach.Miss, VerifyAhead.Reach.Hit))
          assertEquals(verified, hashes(0, 1, Sentinel))
      }
    yield ()
  }

  test("requests beyond the verifier's queue are dropped: never verified, a miss") {
    val asked = 1 to VerifyAhead.Requests + 1
    for
      p <- probe(0)
      _ <- VerifyAhead.resource[IO](p.verifier()).use { ahead =>
        for
          _ <- ahead.ahead(header(0), raw(0)) *> p.started(0)
          _ <- asked.toList.traverse_(i => ahead.ahead(header(i), raw(i)))
          held <- ahead.held.get
          dropped <- ahead.reached(header(asked.last).hash)
          _ <- p.release(0)
        yield
          assertEquals(held.keySet, (0 +: asked.init).map(header(_).hash).toSet)
          assertEquals(dropped, VerifyAhead.Reach.Miss)
      }
    yield ()
  }

  test("with nothing verified ahead every block reached is a miss") {
    for
      ahead <- VerifyAhead.idle[IO]
      _ <- ahead.ahead(header(0), raw(0))
      reach <- ahead.reached(header(0).hash)
      held <- ahead.held.get
    yield
      assertEquals(reach, VerifyAhead.Reach.Miss)
      assertEquals(held, Map.empty)
  }

  // ------------------------------------------------------------------ the node

  /** What a peer announces, and how the worker is held: the chain's genesis; the peer's events and
    * the bodies it serves; the block whose preparation is held (`holdAt`) until the verifier has
    * verified `until`, the last of those queued behind it; and the block whose certificate's KES
    * signature the provider refuses, if any.
    */
  private final case class Script(
      genesis: MirrorState,
      events: Vector[SyncEvent],
      bodies: Map[Bytes, Bytes],
      holdAt: Header,
      until: Header,
      bad: Option[Header]
  )

  /** The forged block whose certificate's KES signature the provider refuses: in the middle of the
    * queue while the worker is held at the first block.
    */
  private val index = 10
  private def target: Header.Shelley = forged.headers(index)

  private def forward(h: Header) = SyncEvent.RollForward(h, ChainSync.Tip.Origin)

  private lazy val badSignature: Script = Script(
    forged.genesis,
    forged.headers.map(forward),
    forged.headers.map(_.hash).zip(forged.raws).toMap,
    header(0),
    target,
    Some(target)
  )

  /** Two forks of one prefix, the second longer: the peer announces the prefix and the first fork,
    * rolls back to the prefix and announces the second. The worker is held at the prefix's last
    * block while all eight blocks of the forks are queued and verified behind it; it then adopts
    * the first fork and switches to the second, rolling the first back.
    */
  private lazy val forks: (ForgedChain, ForgedChain) =
    ForgedChain
      .competingForks(5000 until 5020, 5020 until 5100, 31, 47)
      .unsafeRunSync()(using munitIORuntime)

  private lazy val rollback: Script =
    val (longer, shorter) = forks
    val common = longer.headers.zip(shorter.headers).takeWhile((a, b) => a.hash == b.hash).size
    Script(
      longer.genesis,
      shorter.headers.map(forward) ++
        Vector(SyncEvent.RollBackward(longer.headers(common - 1).point, ChainSync.Tip.Origin)) ++
        longer.headers.drop(common).map(forward),
      (longer.headers.zip(longer.raws) ++ shorter.headers.zip(shorter.raws))
        .map((h, raw) => h.hash -> raw)
        .toMap,
      longer.headers(common - 1),
      longer.headers.last,
      None
    )

  /** The real verifiers, except that the KES signature of `bad` is refused; `calls` counts the
    * checks of its certificate.
    */
  private final class BadSignature(bad: Option[Header]) extends Verdicts:
    export Verdicts.Direct.{keyHash, praosVrf, takePrepared, tpraosVrf, witness}
    private val counted = new java.util.concurrent.atomic.AtomicInteger(0)
    def calls: Int = counted.get
    def opCert(
        header: Header.Shelley,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: BigInt
    ): Either[OCertError | ProviderDefect, Unit] =
      if bad.exists(_.hash == header.hash) then
        counted.incrementAndGet(): Unit
        Left(OCertError.KesSignatureRejected)
      else Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)

  /** How the worker meets the checks: as before checks were made ahead (`Baseline`); through the
    * node's preparation with nothing made ahead (`Idle`); with the script's held block held until
    * the verifier has verified the blocks queued behind it (`Before`); or with the checks of the
    * script's last queued block started ahead and released only when the worker reaches it, so that
    * the worker most likely finds them being made (`During`); or as `Before` over a cache whose
    * maps hold two verdicts each, so that they are cleared again and again between the checks made
    * ahead and the worker, which then makes them itself (`Evicted`).
    */
  private enum Schedule:
    case Baseline, Idle, Before, During, Evicted

  private final case class Seen(
      end: SessionEnd,
      invalid: Map[Bytes, ValidationError],
      events: Vector[FollowerEvent],
      chain: VolatileChain[MirrorState],
      blamed: Vector[ValidationError],
      counts: VerifyAhead.Counts,
      calls: Int
  )

  /** A follower session over the real block rules of the forged chains with the verification cache
    * as their verifiers, against a peer playing `script`, whose blocks the node's selection worker
    * adopts with the preparation `schedule` gives. Diffusion pipelining is off, so that the refusal
    * of a block the peer delivered is blamed on it.
    */
  private def session(script: Script, schedule: Schedule): IO[Seen] =
    val provider = BadSignature(script.bad)
    val bounds =
      if schedule == Schedule.Evicted then VerificationCache.Bounds(2, 4096, 2)
      else VerificationCache.Bounds.Default
    val cache = VerificationCache.unsafe(2, provider, bounds = bounds)
    // the epoch nonce of the chain's start, as the node knows the one of its tip: the VRF proofs
    // of its epoch's blocks are verified ahead
    cache.noteEpochNonce(script.genesis.ledgerEpoch, script.genesis.protocol.epochNonce)
    NodeScope.resource
      .use { node =>
        for
          ref <- Ref.of[IO, VolatileChain[MirrorState]](
            VolatileChain.genesis(ForgedChain.geometry.k.toInt, script.genesis)
          )
          given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
          emitted <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
          follower <- Follower.create[IO, MirrorState](
            new MirrorRules[IO](
              ForgedChain.config.copy(verdicts = cache),
              ForgedChain.globals,
              None,
              None,
              ForgedChain.mirrorEnv.byron,
              xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
              afterBlock = Some((_, _, s) =>
                IO.delay(cache.noteEpochNonce(s.ledgerEpoch, s.protocol.epochNonce))
              )
            ),
            event => emitted.update(_ :+ event),
            ForgedChain.geometry
          )
          pool <- WorkerTesting.pool(follower)
          praos <- Ref.of[IO, PraosConfig](ForgedChain.config)
          queued <- Deferred[IO, Unit]
          started <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          base = Main
            .verifier(follower, pool, cache, praos)
            .getOrElse(fail("a cache of two threads verifies ahead"))
          blamed <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
          // the node's verifier, saying when it has verified the last block queued behind the held
          // one and, `During`, holding that block's checks
          last = script.until.hash
          verifier = base.copy(verify =
            (h, bytes) =>
              (started.complete(()) *> release.get)
                .whenA(schedule == Schedule.During && h.hash == last) *>
                base.verify(h, bytes).flatTap(_ => queued.complete(()).void.whenA(h.hash == last))
          )
          preverify = (blocks: Vector[Bytes]) => cache.warm[IO](blocks, ForgedChain.config)
          holds = (hash: Bytes) => IO(cache.holds(hash))
          seen <- VerifyAhead.resource[IO](verifier).use { verifyAhead =>
            for
              idle <- VerifyAhead.idle[IO]
              noReads = (_: Header, _: Bytes) => IO.unit
              prep = schedule match
                case Schedule.Baseline => baseline(preverify)
                case Schedule.Idle =>
                  BlockSelection.Before.delivered[IO](None, preverify, holds, idle, noReads)
                case Schedule.Before | Schedule.During | Schedule.Evicted =>
                  BlockSelection.Before.delivered[IO](None, preverify, holds, verifyAhead, noReads)
              before = held(
                prep,
                h =>
                  schedule match
                    case Schedule.Before | Schedule.Evicted =>
                      queued.get.whenA(h.hash == script.holdAt.hash)
                    case Schedule.During =>
                      (started.get *> release.complete(()).start.void).whenA(h.hash == last)
                    case _ => IO.unit
              )
              queue <- Queue.unbounded[IO, SyncEvent]
              _ <- script.events.traverse_(queue.offer)
              done <- Deferred[IO, Unit]
              peer = new WorkerTesting.Scripted[IO]:
                def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
                  IO.pure(candidates.headOption)
                def next(using Raise[IO, PeerError]) = queue.tryTake.flatMap {
                  case Some(event) => IO.pure(event)
                  case None        => done.complete(()) *> IO.never[SyncEvent]
                }
                def fetch(header: Header)(using Raise[IO, PeerError]) =
                  IO.pure(script.bodies(header.hash))
              end <- SessionEnd
                .scoped(node) { (_, _) ?=>
                  WorkerTesting
                    .follow(
                      follower,
                      pool,
                      peer,
                      done.get,
                      before,
                      Some(refusal => blamed.update(_ :+ refusal)),
                      BlockPool.Diffusion.Off
                    )
                    .as(SessionEnd.Stopped(false))
                }
                .timeout(60.seconds)
              chain <- ref.get
              invalid <- pool.invalidBlocks.snapshot
              events <- emitted.get
              counts <- if schedule == Schedule.Idle then idle.counts else verifyAhead.counts
              blames <- blamed.get
            yield Seen(end, invalid.entries, events, chain, blames, counts, provider.calls)
          }
        yield seen
      }
      .guarantee(IO(cache.shutdown()))

  /** The preparation as it was before checks were made ahead: the checks of the block alone. */
  private def baseline(preverify: Peer.Prepare[IO]): BlockSelection.Before[IO] =
    new BlockSelection.Before[IO]:
      def prepare(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] =
        preverify(Vector(raw))
      def store(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] = IO.unit
      def ahead(header: Header, raw: Bytes): IO[Unit] = IO.unit
      def passed(header: Header): IO[Unit] = IO.unit

  /** `before`, its preparation of a block held by `first`. */
  private def held(
      before: BlockSelection.Before[IO],
      first: Header => IO[Unit]
  ): BlockSelection.Before[IO] =
    new BlockSelection.Before[IO]:
      def prepare(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] =
        first(header) *> before.prepare(header, raw)
      def store(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] =
        before.store(header, raw)
      def ahead(header: Header, raw: Bytes): IO[Unit] = before.ahead(header, raw)
      def passed(header: Header): IO[Unit] = before.passed(header)

  private def same(seen: Seen, expected: Seen): Unit =
    assertEquals(seen.end, expected.end)
    assertEquals(seen.invalid, expected.invalid)
    assertEquals(seen.events, expected.events)
    assertEquals(seen.chain, expected.chain)
    assertEquals(seen.blamed, expected.blamed)

  test("a bad signature in the middle of the queue: the same refusal, blame and states") {
    for
      expected <- session(badSignature, Schedule.Baseline)
      idle <- session(badSignature, Schedule.Idle)
      before <- session(badSignature, Schedule.Before)
      during <- session(badSignature, Schedule.During)
      evicted <- session(badSignature, Schedule.Evicted)
    yield
      // the baseline refuses the target, blames its sender, and stops at its predecessor
      assertEquals(expected.invalid.keySet, Set(target.hash))
      assertEquals(expected.chain.tip, forged.headers(index - 1).point)
      assertEquals(expected.calls, 1)
      assertEquals(expected.blamed.map(_.point), Vector(target.point))
      same(idle, expected)
      same(before, expected)
      same(during, expected)
      same(evicted, expected)
      // nothing was made ahead in the first, the checks of the held queue in the second, which the
      // worker then did not ask for again, and the target's were released as the worker reached
      // it in the third; in the fourth the cache's clears dropped most of what was made ahead, and
      // the worker made those checks itself
      assertEquals(idle.counts.hit + idle.counts.joined + idle.counts.skipped, 0L)
      assert(idle.counts.miss > index.toLong, idle.counts.toString)
      assert(before.counts.hit >= index.toLong, before.counts.toString)
      assert(before.counts.skipped >= index.toLong, before.counts.toString)
      assertEquals(before.calls, 1)
      assert(during.counts.hit + during.counts.joined > 0, during.counts.toString)
      assert(evicted.counts.hit > evicted.counts.skipped, evicted.counts.toString)
  }

  test("a rollback over queued blocks verified ahead: the same switch, events and states") {
    val (longer, shorter) = forks
    for
      expected <- session(rollback, Schedule.Baseline)
      before <- session(rollback, Schedule.Before)
      during <- session(rollback, Schedule.During)
      evicted <- session(rollback, Schedule.Evicted)
    yield
      // the baseline adopts the first fork, then switches to the second and rolls the first back
      assertEquals(expected.chain.tip, longer.headers.last.point)
      assert(
        expected.events.exists {
          case FollowerEvent.RolledBack(from, _, _) => from == shorter.headers.last.point
          case _                                    => false
        },
        expected.events.toString
      )
      assertEquals(expected.invalid, Map.empty)
      same(before, expected)
      same(during, expected)
      same(evicted, expected)
      // the eight blocks of the forks were verified while the worker was held before them, and
      // the worker did not ask for their checks again
      assert(before.counts.hit >= 8L, before.counts.toString)
      assert(before.counts.skipped >= 8L, before.counts.toString)
      assert(evicted.counts.hit > evicted.counts.skipped, evicted.counts.toString)
  }

  // ------------------------------------------------------------------ the era-walking chain

  private val fixtures = Path.of("fixtures/eras")
  private lazy val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(fixtures.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private lazy val chainRaws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
  private lazy val chainHeaders: Vector[Header] =
    chainRaws.map(
      Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity).header
    )

  /** The witness checks of a block, as the rules ask for them. */
  private def checks(p: Prepared): Vector[(Bytes, Bytes, Bytes)] = p match
    case Prepared.Flat(_, txs) =>
      txs.flatMap(_.toOption).flatMap { tx =>
        (tx.witnesses.vkeys ++ tx.witnesses.bootstrap.map(w => w.vkey -> w.signature)).map {
          (k, s) => (k, s, tx.body.id)
        }
      }
    case Prepared.Conway(read) =>
      read.block.toOption.toVector.flatMap(block =>
        EraBlockBody.conway
          .txSeqBlockBody(block.blockBody)
          .flatMap(tx =>
            ShelleyUtxow
              .verifiedWitsAsked[Conway, TxLevel.Top](tx)(using
                EraTx.conway,
                EraTx.conway.bodyOf.top
              )
              .map(c => (c.vkey, c.signature, c.message))
          )
      )

  /** The real verifiers, except that `bad` is not a valid witness; `asked` counts its checks. */
  private final class BadWitness(bad: (Bytes, Bytes, Bytes)) extends Verdicts:
    export Verdicts.Direct.{keyHash, opCert, praosVrf, takePrepared, tpraosVrf}
    private val counted = new java.util.concurrent.atomic.AtomicInteger(0)
    def asked: Int = counted.get
    def witness(vkey: Bytes, signature: Bytes, message: Bytes): Either[ProviderDefect, Boolean] =
      if bad == ((vkey, signature, message)) then
        counted.incrementAndGet(): Unit
        Right(false)
      else Verdicts.Direct.witness(vkey, signature, message)

  /** The blocks applied in order until one is refused: the states, the refusal with its block, and
    * the block whose preparation or queueing first asked for the bad witness.
    */
  private final case class Applied(
      states: Vector[MirrorState],
      refused: Option[(Int, NodeError)],
      firstAsked: Option[Int],
      asked: Int,
      skipped: Int
  )

  /** The era-walking chain applied as the worker applies it: each block's checks asked for when it
    * is prepared, and, `window > 0`, also when it enters a queue of `window` blocks, the worker
    * then asking for them only where the cache does not hold every verdict of the block.
    */
  private def applied(bad: (Bytes, Bytes, Bytes), window: Int): IO[Applied] =
    val provider = BadWitness(bad)
    val cache = VerificationCache.unsafe(2, provider)
    val rules = MirrorRules[IO](
      setup.praos.copy(verdicts = cache),
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
      afterBlock =
        Some((_, _, s) => IO.delay(cache.noteEpochNonce(s.ledgerEpoch, s.protocol.epochNonce)))
    )
    def warm(i: Int) = chainRaws.lift(i).traverse_(r => cache.warm[IO](Vector(r), setup.praos, 2))
    def noted(at: Int, run: Applied) =
      run.copy(firstAsked = run.firstAsked.orElse(Option.when(provider.asked > 0)(at)))
    val start = Applied(Vector(setup.mirrorStart), None, None, 0, 0)
    (0 until window - 1).toList.traverse_(warm) *>
      chainRaws.indices.toList
        .foldLeftM(start) { (run, i) =>
          if run.refused.isDefined then IO.pure(run)
          else
            for
              _ <- warm(i + window - 1).whenA(window > 0)
              skip <- IO(window > 0 && cache.holds(chainHeaders(i).hash))
              _ <- warm(i).unlessA(skip)
              prepared = noted(i, run.copy(skipped = run.skipped + (if skip then 1 else 0)))
              next <- Handle
                .allow[NodeError](
                  rules(prepared.states.last, chainHeaders(i), chainRaws(i))
                    .map(s => prepared.copy(states = prepared.states :+ s))
                )
                .rescue(e => IO.pure(prepared.copy(refused = Some(i -> e))))
            yield next
        }
        .map(_.copy(asked = provider.asked))
        .guarantee(IO(cache.shutdown()))

  test("the era-walking chain with a bad witness in a queued block: the same states and refusal") {
    val witnessed = chainRaws.indices.flatMap(i =>
      NodeRules
        .decodeShelley(chainRaws(i), setup.layout)
        .toVector
        .flatMap(checks)
        .headOption
        .map(i -> _)
    )
    val (flagged, bad) = witnessed(witnessed.size / 2)
    assert(flagged >= 10, s"the bad block $flagged must be queued behind ten others")
    for
      without <- applied(bad, 0)
      ahead <- applied(bad, 10)
    yield
      assertEquals(without.refused.map(_._1), Some(flagged))
      assertEquals(ahead.refused, without.refused)
      assertEquals(ahead.states, without.states)
      // the bad witness was asked once in each: when its block was prepared, and when it was
      // queued, well before the block was applied
      assertEquals((without.asked, ahead.asked), (1, 1))
      assertEquals(without.firstAsked, Some(flagged))
      assert(ahead.firstAsked.exists(_ < flagged), ahead.firstAsked.toString)
      // the worker asked for no check of most of the blocks it applied
      assert(ahead.skipped > flagged / 2, ahead.skipped.toString)
  }
