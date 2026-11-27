// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf, Prepared, Verdicts, WitnessCheck}
import cats.effect.{Async, Resource, Sync}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Nonce, OCertError, ProviderDefect}
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig, TPraos}
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.ledger.era.{EraBlockBody, EraTx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.rules.ShelleyUtxow
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.StateLayout

/** Signature checks that do not depend on the ledger state, made available ahead of the sequential
  * rules.
  *
  * Verifying a transaction witness, an operational certificate or a KES signature is a pure
  * function of the bytes of the block alone: it needs no UTxO, no nonce, no parameters beyond the
  * node configuration. They are also most of the work of validating a block (about half of the time
  * on Preview). So the node verifies them for a whole batch of fetched blocks in parallel
  * ([[warm]]) and remembers the verdicts here; the rules ask for the same verdict through
  * [[Verdicts]], which returns the remembered one (once) or computes it. The verdict is exactly the
  * one [[Verdicts.Direct]] gives, so the rules stay pure functions of their inputs; this is
  * memoisation, and a verdict that was never warmed is simply computed in place.
  *
  * The state is instance-local: `Main` creates one cache in a `Resource`
  * ([[VerificationCache.resource]]) and hands it to the rules through `PraosConfig.verdicts`; tests
  * build their own or use [[Verdicts.Direct]]. It is still mutable and outside `Ref`: the verdict
  * maps are written from the parallel warming threads and read, synchronously, from the pure rules,
  * which have no effect type to thread a `Ref` through. Every structure is a concurrent map or
  * atomic, bounded (cleared at `Limit`), and holds only verdicts that are functions of their key,
  * so sharing one between threads changes timing, never results.
  *
  * The reference warms nothing: it asks the crypto provider once, when the rules reach the check
  * (ouroboros-consensus 82ecba32 `Protocol/Praos.hs` 587–592 and 637–640). So only the call the
  * validation makes decides. A provider defect ([[xyz.kd5ujc.scarab.ledger.ProviderDefect]]) met
  * ahead of time is no information: it is discarded, never kept or handed over, and the validation
  * that reaches the check asks the provider itself and meets its own answer. A verdict (accept or
  * reject) computed ahead of time is a function of its key and is reused. The checks of the blocks
  * waiting in the add-block queue are made ahead of the selection worker the same way
  * ([[VerifyAhead]], #1499), an Optimisation (REF-6) for which the same holds.
  *
  * @param direct
  *   the providers every verdict is computed with; [[Verdicts.Direct]] except in tests
  * @param layout
  *   the pool parameters' layout of the chain's state, which a Conway block's transactions are
  *   decoded in for the rules ([[Prepared.decode]]); the rules take a block decoded in another only
  *   to decode it again ([[NodeRules.prepare]])
  * @param running
  *   the permits all warming shares, `parallelism` of them; a test may supply its own to observe
  *   the waits
  */
final class VerificationCache private (
    val parallelism: Int,
    direct: Verdicts,
    layout: StateLayout,
    private[node] val running: java.util.concurrent.Semaphore,
    bounds: VerificationCache.Bounds
) extends Verdicts:
  private final case class WitnessKey(vkey: Bytes, signature: Bytes, message: Bytes)
  private final case class HeaderKey(
      hash: Bytes,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  )

  private val Limit = bounds.verdicts
  private def MinChunk = VerificationCache.MinChunk
  private val witnesses = new ConcurrentHashMap[WitnessKey, java.lang.Boolean]()
  private val headers = new ConcurrentHashMap[HeaderKey, Either[OCertError, Unit]]()
  private val prepared = new ConcurrentHashMap[Bytes, Prepared]()

  // The blocks whose every verdict a warming kept ([[holds]]): the generation of the verdict maps
  // the warming began in, and the epoch nonce it verified the VRF proofs under. A clear of a verdict
  // map moves the generation before and after it and is counted while it runs, so that a block
  // whose warming a clear overlapped, before, during or after its own run, is not held.
  private final case class Kept(generation: Long, hint: (BigInt, Nonce))
  private val complete = new ConcurrentHashMap[Bytes, Kept]()
  private val generation = new java.util.concurrent.atomic.AtomicLong(0)
  private val clearing = new java.util.concurrent.atomic.AtomicInteger(0)

  /** `clear` of a verdict map, counted while it runs and the generation moved before and after it
    * ([[holds]]).
    */
  private[node] def evicting(clear: => Unit): Unit =
    clearing.incrementAndGet(): Unit
    generation.incrementAndGet(): Unit
    clear
    generation.incrementAndGet(): Unit
    clearing.decrementAndGet(): Unit

  // VRF proofs depend on the epoch nonce, which only the sequential rules know; the verdicts are
  // keyed by the derived alpha string, so a block warmed under a wrong nonce is just a miss.
  private final case class VrfKey(kind: Int, alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes)
  private val vrfs = new ConcurrentHashMap[VrfKey, Vrf.Result]()
  private val epochNonce = new AtomicReference[(BigInt, Nonce)]()

  /** The newest epoch nonce the rules have seen: blocks of that epoch can have their VRF proofs
    * verified ahead of time.
    */
  def noteEpochNonce(epoch: BigInt, nonce: Nonce): Unit =
    val previous = epochNonce.getAndSet((epoch, nonce))
    // The first block of a new epoch: the blocks already fetched for it could not be warmed (their
    // nonce was not known), so verify their VRF proofs now, in chain order, on the side.
    if previous == null || previous._1 != epoch then rewarm(epoch)

  private val lastConfig = new AtomicReference[PraosConfig]()
  private lazy val pool = new java.util.concurrent.ForkJoinPool(parallelism)

  private[node] def shutdown(): Unit = pool.shutdownNow(): Unit

  private def rewarm(epoch: BigInt): Unit =
    val config = lastConfig.get
    if config != null then
      // warming is a guess at what will be asked: a failure here is the real check's to report
      pool.execute { () =>
        val _ = Either.catchNonFatal {
          val pending = prepared.values.asScala.toVector
            .filter(p => config.geometry.epochOf(p.envelope.header.slot) == epoch)
            .sortBy(_.envelope.header.slot)
          pending.asJava
            .parallelStream()
            .forEach(p => warmVrf(p.envelope.header, config, epochNonce.get): Unit)
        }
      }

  /** Praos (Babbage and later) VRF verdict, see
    * [[xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate.verify]].
    */
  override def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
    Vrf.alpha(input) match
      case Right(alpha) =>
        takenVrf(VrfKey(0, alpha, vkey, proof, output), direct.praosVrf(input, vkey, proof, output))
      case Left(_) => direct.praosVrf(input, vkey, proof, output)

  /** TPraos VRF verdict over an already derived alpha. */
  override def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
    takenVrf(VrfKey(1, alpha, vkey, proof, output), direct.tpraosVrf(alpha, vkey, proof, output))

  /** The block's warmed VRF verdict of `key`, taken once, or `verdict`. */
  private def takenVrf(key: VrfKey, verdict: => Vrf.Result): Vrf.Result =
    val known = Option(vrfs.remove(key))
    vrfStats.record(known.isDefined)
    known.getOrElse(verdict)

  /** `witnesses hits/lookups, opcerts hits/lookups, vrf hits/lookups, warmed skipped/checked` for
    * the log. The last is the signature work of [[warm]] that found its verdict already there: with
    * the hits it tells whether the checks run ahead are the checks the rules ask for.
    */
  def summary: String =
    s"witness=$witnessStats opCert=$opCertStats vrf=$vrfStats warmed=$warmedStats"

  /** Warm the VRF verdict(s) of `header` under the epoch nonce `hint`: whether every one is kept
    * (none is for a block of another epoch than the hint's, or one the providers failed on).
    */
  private def warmVrf(
      header: Header.Shelley,
      config: PraosConfig,
      hint: (BigInt, Nonce)
  ): Boolean =
    def kept(result: Vrf.Result) = ProviderDefect.ofVrf(result).isEmpty
    hint != null && config.geometry.epochOf(header.slot) == hint._1 && (header.vrf match
      case HeaderVrf.Praos(cert) =>
        Praos
          .vrfInput(hint._2, header.slot)
          .flatMap(input => Vrf.alpha(input).map(input -> _))
          .exists((input, alpha) =>
            kept(
              keepVrf(
                VrfKey(0, alpha, header.vrfVkey, cert.proof, cert.output),
                direct.praosVrf(input, header.vrfVkey, cert.proof, cert.output)
              )
            )
          )
      case HeaderVrf.TPraos(nonceCert, leaderCert) =>
        Seq(TPraos.SeedEta -> nonceCert, TPraos.SeedL -> leaderCert)
          .map { (uc, cert) =>
            val alpha = TPraos.seed(uc, header.slot, hint._2)
            kept(
              keepVrf(
                VrfKey(1, alpha, header.vrfVkey, cert.proof, cert.output),
                direct.tpraosVrf(alpha, header.vrfVkey, cert.proof, cert.output)
              )
            )
          }
          .forall(identity))

  /** The verdicts as header-first validation reads them (G6): computed once if they are not there
    * yet and *kept*, so that the block's own validation, which comes later and consumes them
    * through this cache, finds them and verifies nothing twice. Entries of blocks that are never
    * fetched stay until the maps are cleared at their limits.
    */
  def forHeaders: Verdicts = new Verdicts:
    def witness(vkey: Bytes, signature: Bytes, message: Bytes): Either[ProviderDefect, Boolean] =
      VerificationCache.this.witness(vkey, signature, message)

    def opCert(
        header: Header.Shelley,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: BigInt
    ): Either[OCertError | ProviderDefect, Unit] =
      if headers.size > Limit then evicting(headers.clear())
      keepHeader(
        HeaderKey(header.hash, slotsPerKesPeriod, maxKesEvolutions),
        direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)
      )

    def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      Vrf.alpha(input) match
        case Right(alpha) =>
          keptVrf(
            VrfKey(0, alpha, vkey, proof, output),
            direct.praosVrf(input, vkey, proof, output)
          )
        case Left(_) => direct.praosVrf(input, vkey, proof, output)

    def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      keptVrf(VrfKey(1, alpha, vkey, proof, output), direct.tpraosVrf(alpha, vkey, proof, output))

    /** The kept VRF verdict of `key`, or `verdict` kept now. */
    private def keptVrf(key: VrfKey, verdict: => Vrf.Result): Vrf.Result =
      if vrfs.size > bounds.vrfs then evicting(vrfs.clear())
      keepVrf(key, verdict)

    def keyHash(vkey: Bytes): Bytes = VerificationCache.this.keyHash(vkey)
    def takePrepared(hash: Bytes): Option[Prepared] = None

  /** Verify the signatures of the leading headers of a ChainSync batch in parallel and keep the
    * verdicts for [[forHeaders]]. `state` is the header state before the first header: its epoch
    * nonce serves the headers of that epoch (the others are verified by the rules in order).
    */
  def warmHeaders[F[_]: Async](
      batch: Vector[Header],
      state: xyz.kd5ujc.scarab.consensus.state.ProtocolState,
      config: PraosConfig
  ): F[Unit] =
    val shelley = batch.collect { case h: Header.Shelley => h }
    shelley.headOption match
      case None => Async[F].unit
      case Some(first) =>
        val epoch = config.geometry.epochOf(first.slot)
        val nonce = xyz.kd5ujc.scarab.consensus.Praos.tick(state, first.era, epoch).epochNonce
        // warming is a guess at what will be asked: a failure here is the real check's to report
        shelley
          .parTraverseN(parallelism)(h =>
            Async[F].blocking {
              val _ = Either.catchNonFatal {
                keepHeader(
                  HeaderKey(h.hash, config.slotsPerKesPeriod, config.maxKesEvolutions),
                  direct.opCert(h, config.slotsPerKesPeriod, config.maxKesEvolutions)
                )
                warmVrf(h, config, (epoch, nonce)): Unit
              }
            }
          )
          .void

  /** Whether every verdict of the block `hash` 's checks is here: a warming decoded it and kept the
    * verdict of each of its checks (none a provider failed on, its VRF under the epoch nonce noted
    * now), no verdict map was cleared since that warming began and none is being cleared, and the
    * rules have not taken the block ([[takePrepared]]). A few reads, no decoding.
    */
  def holds(hash: Bytes): Boolean =
    clearing.get == 0 &&
      Option(complete.get(hash)).exists(k =>
        k.generation == generation.get && k.hint == epochNonce.get
      ) &&
      prepared.containsKey(hash)

  /** The block decoded while warming, if any (taken once). */
  override def takePrepared(hash: Bytes): Option[Prepared] =
    complete.remove(hash)
    Option(prepared.remove(hash))

  private def compute(key: WitnessKey): Either[ProviderDefect, Boolean] =
    direct.witness(key.vkey, key.signature, key.message)

  /** The kept OCERT verdict of `key`, or `verdict` kept now. A provider defect is no verdict and is
    * not kept: it is only returned, and a later ask computes again.
    */
  private def keepHeader(
      key: HeaderKey,
      verdict: => Either[OCertError | ProviderDefect, Unit]
  ): Either[OCertError | ProviderDefect, Unit] =
    val known = headers.get(key)
    if known != null then known
    else
      val computed = verdict
      computed match
        case Right(())               => headers.putIfAbsent(key, Right(())): Unit
        case Left(e: OCertError)     => headers.putIfAbsent(key, Left(e)): Unit
        case Left(_: ProviderDefect) => ()
      computed

  /** As [[keepHeader]] for a VRF answer. */
  private def keepVrf(key: VrfKey, verdict: => Vrf.Result): Vrf.Result =
    val known = vrfs.get(key)
    if known != null then known
    else
      val computed = verdict
      if ProviderDefect.ofVrf(computed).isEmpty then vrfs.putIfAbsent(key, computed): Unit
      computed

  private val keyHashes = new ConcurrentHashMap[Bytes, Bytes]()

  /** The Blake2b-224 hash of a verification key; the same keys sign again and again. */
  override def keyHash(vkey: Bytes): Bytes =
    val known = keyHashes.get(vkey)
    if known != null then known
    else
      val hash = direct.keyHash(vkey)
      if keyHashes.size > 200000 then keyHashes.clear()
      keyHashes.put(vkey, hash)
      hash

  /** How often a verdict was already there (`hits`) or had to be computed by the rules (`misses`).
    */
  final class Counters:
    val hits = new java.util.concurrent.atomic.LongAdder
    val misses = new java.util.concurrent.atomic.LongAdder
    def record(hit: Boolean): Unit = if hit then hits.increment() else misses.increment()
    override def toString: String = s"${hits.sum}/${hits.sum + misses.sum}"

  val witnessStats = new Counters

  /** The witness verifications [[warm]] was asked for: `hits` found the verdict already there and
    * skipped the work, `misses` computed it. Printed as `skipped/asked`.
    */
  val warmedStats = new Counters
  val opCertStats = new Counters
  val vrfStats = new Counters

  /** Whether `signature` is a valid witness of `message` under `vkey`. */
  override def witness(
      vkey: Bytes,
      signature: Bytes,
      message: Bytes
  ): Either[ProviderDefect, Boolean] =
    val key = WitnessKey(vkey, signature, message)
    val known = Option(witnesses.remove(key)).map(v => Right(v.booleanValue))
    witnessStats.record(known.isDefined)
    known.getOrElse(compute(key))

  /** The OCERT signature checks of a header (see
    * [[xyz.kd5ujc.scarab.ledger.OCertRules.verifySignatures]]).
    */
  override def opCert(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError | ProviderDefect, Unit] =
    val key = HeaderKey(header.hash, slotsPerKesPeriod, maxKesEvolutions)
    val known = Option(headers.remove(key))
    opCertStats.record(known.isDefined)
    known.getOrElse(direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions))

  /** One independent signature check whose verdict goes into a cache: whether the verdict is kept
    * there (it is not where a provider failed: a defect is never kept).
    */
  private type Job = () => Boolean

  /** A job as it is scheduled: its outcome recorded, any failure discarded. */
  private type Task = () => Unit

  // Ranges are warmed concurrently (one `warm` per fetched range): all of them together run at most
  // `parallelism` chunks at once ([[running]]), so that several single-block batches do not put
  // several times as many runnable threads as CPUs on the machine.

  /** A block whose checks are running: the decoded block is offered to the rules (`prepared`) when
    * the last of its jobs is done, so that the rules find the verdicts together with the block.
    */
  private final class Pending(val block: Prepared, jobs: Int):
    private val remaining = new java.util.concurrent.atomic.AtomicInteger(jobs)
    private val failed = new java.util.concurrent.atomic.AtomicBoolean(false)
    private val partial = new java.util.concurrent.atomic.AtomicBoolean(false)
    def failure(): Unit = failed.set(true)
    def unkept(): Unit = partial.set(true)
    def finished(): Boolean = remaining.decrementAndGet() == 0 && !failed.get
    def kept: Boolean = !partial.get

  /** Decode a block and split its signature checks into independent jobs: the operational
    * certificate and KES signature of the header, the VRF proof(s), and one job per distinct vkey
    * or bootstrap witness. Never fails: a block that does not decode has no jobs and is left for
    * the rules to report.
    */
  private def jobsOf(raw: Bytes, config: PraosConfig): Vector[Task] =
    lastConfig.set(config)
    if witnesses.size > Limit then evicting(witnesses.clear())
    if headers.size > Limit then evicting(headers.clear())
    if prepared.size > bounds.prepared then evicting { prepared.clear(); complete.clear() }
    if vrfs.size > bounds.vrfs then evicting(vrfs.clear())
    // the generation the block's verdicts are kept in, and the nonce its VRF proofs are verified
    // under: a clear after this, or another nonce noted, makes it not held
    val begun = generation.get
    val hint = epochNonce.get
    NodeRules.decodeShelley(raw, layout).fold(Vector.empty[Task]) { p =>
      val b = p.envelope
      val key = HeaderKey(b.header.hash, config.slotsPerKesPeriod, config.maxKesEvolutions)
      val triples = (p match
        case Prepared.Flat(_, txs) =>
          txs
            .flatMap(_.toOption)
            .flatMap { tx =>
              val id = tx.body.id
              (tx.witnesses.vkeys ++ tx.witnesses.bootstrap.map(w => w.vkey -> w.signature)).map {
                (vkey, signature) => WitnessKey(vkey, signature, id)
              }
            }
        // the checks the era's UTXOW asks for, exactly (`verifiedWitsAsked`)
        case Prepared.Conway(read) =>
          read.block.fold(
            _ => Vector.empty[WitnessKey],
            block =>
              EraBlockBody.conway
                .txSeqBlockBody(block.blockBody)
                .flatMap(tx =>
                  ShelleyUtxow
                    .verifiedWitsAsked[Conway, TxLevel.Top](tx)(using
                      EraTx.conway,
                      EraTx.conway.bodyOf.top
                    )
                    .map(c => WitnessKey(c.vkey, c.signature, c.message))
                )
          )
      ).distinct
      val checks: Vector[Job] =
        // header-first validation may have computed it already (see forHeaders)
        Vector[Job](
          () =>
            keepHeader(
              key,
              direct.opCert(b.header, config.slotsPerKesPeriod, config.maxKesEvolutions)
            ) match
              case Left(_: ProviderDefect)         => false
              case Left(_: OCertError) | Right(()) => true
          ,
          () => warmVrf(b.header, config, hint)
        ) ++ triples.map(witnessJob)
      val pending = new Pending(p, checks.size)
      // a job's defect is discarded (the validation asks the provider itself); the last job to
      // finish stores the block's prepared checks, and the block as held where every verdict was
      // kept
      checks.map { job => () =>
        Either
          .catchNonFatal(job())
          .fold(_ => pending.failure(), kept => if !kept then pending.unkept())
        if pending.finished() then
          prepared.put(b.header.hash, p)
          if pending.kept then complete.put(b.header.hash, Kept(begun, hint)): Unit
      }
    }

  /** The check of one witness, its verdict kept. A verdict that is still there (the same block
    * warmed again: a second peer's batch, a range fetched again after a rollback, a transaction of
    * a closure warmed again) is a function of its key, so it is not computed again. A defect is
    * discarded: the validation asks the provider itself.
    */
  private def witnessJob(k: WitnessKey): Job = () =>
    val present = witnesses.containsKey(k)
    warmedStats.record(present)
    present || {
      keyHash(k.vkey)
      compute(k).map(valid => witnesses.put(k, valid)).isRight
    }

  /** The jobs of a batch in chunks of about `perChunk` jobs: a few chunks per thread so that a slow
    * one does not leave the others idle, and never fewer than [[MinChunk]] jobs (about a
    * millisecond of Ed25519), so a small block runs as one task and pays no scheduling.
    */
  private[node] def chunked[A](jobs: Vector[A], parallelism: Int): Vector[Vector[A]] =
    val size = math.max(MinChunk, (jobs.size + parallelism * 4 - 1) / (parallelism * 4))
    jobs.grouped(size).toVector

  /** Warm the verdicts of `blocks` using up to `parallelism` threads. The work runs on `blocking`
    * threads, beside the rules (which run on the CPU pool, see [[Compute]]): putting it on that
    * pool too serialised the two behind each other and cost a third of the throughput
    * (performance.md).
    *
    * Parallel over the signature checks of the whole batch, not over its blocks (#125): batches of
    * late Alonzo and Babbage hold one or a few blocks of hundreds of witnesses, and a loop over
    * blocks left every check of a block on one thread. This is pure signature verification filling
    * a cache; the application of transactions stays sequential and is not touched (no parallel
    * transaction application within a block).
    */
  def warm[F[_]: Async](blocks: Vector[Bytes], config: PraosConfig, parallelism: Int): F[Unit] =
    if blocks.isEmpty then Async[F].unit
    else
      val guarded: (=> Vector[Task]) => Vector[Task] = body =>
        Either.catchNonFatal(body).getOrElse(Vector.empty)
      blocks
        .parTraverseN(parallelism)(b => Async[F].blocking(guarded(jobsOf(b, config))))
        .flatMap(jobs => run(jobs.flatten, parallelism))

  /** `jobs` in [[chunked]] chunks on up to `parallelism` `blocking` threads, under the bound all
    * warming shares ([[running]]), each chunk holding one permit ([[permit]]).
    */
  private def run[F[_]: Async](jobs: Vector[Task], parallelism: Int): F[Unit] =
    chunked(jobs, parallelism)
      .parTraverseN(parallelism)(chunk => permit[F].surround(Async[F].blocking(chunk.foreach(_()))))
      .void

  /** One of [[running]]'s permits, released exactly once. Waiting for it is cancelable (the waiting
    * thread is interrupted), so a caller cancelled while other warming holds every permit, as the
    * closure check is when its read of the outputs fails, is not held behind that work. A permit
    * the semaphore granted is marked held by the thread that took it, before the effect returns,
    * and is released by the release or, when cancellation discards the effect's result, by the
    * cancellation; a wait interrupted before the grant takes no permit and releases none.
    */
  private def permit[F[_]: Async]: Resource[F, Unit] =
    def releaseHeld(held: java.util.concurrent.atomic.AtomicBoolean): F[Unit] =
      Async[F].delay(if held.getAndSet(false) then running.release())
    Resource
      .makeFull[F, java.util.concurrent.atomic.AtomicBoolean](poll =>
        Async[F]
          .delay(new java.util.concurrent.atomic.AtomicBoolean(false))
          .flatMap(held =>
            poll(Async[F].interruptible { running.acquire(); held.set(true) })
              .onCancel(releaseHeld(held))
              .as(held)
          )
      )(releaseHeld)
      .void

  /** Verify the witness signatures `checks` in parallel and keep the verdicts for the rules, which
    * take each once through [[witness]]: the closure check of an endorser block before a vote,
    * whose transactions it then applies in order, as the block path warms a fetched range
    * ([[warm]]). The application stays sequential and the verdicts are the ones [[Verdicts.Direct]]
    * gives, so only when each signature is verified changes (an Improvement over the reference,
    * which verifies each when its rule reaches it, approved by the owner on 2026-11-22 for #79). A
    * job's failure is discarded, as [[warm]]'s is: the rule that reaches the check asks the
    * provider itself.
    */
  def warmWitnesses[F[_]: Async](checks: Vector[WitnessCheck]): F[Unit] =
    Async[F].delay(if witnesses.size > Limit then evicting(witnesses.clear())) *>
      run(
        checks.distinct.map { c =>
          val job = witnessJob(WitnessKey(c.vkey, c.signature, c.message))
          (() => Either.catchNonFatal(job()).fold(_ => (), _ => ())): Task
        },
        parallelism
      )

  /** Warm with the cache's own default parallelism. */
  def warm[F[_]: Async](blocks: Vector[Bytes], config: PraosConfig): F[Unit] =
    warm[F](blocks, config, parallelism)

object VerificationCache:
  /** How many entries the maps hold before they are cleared: the witness and certificate verdicts
    * (each), the decoded blocks and the VRF verdicts. A suite may give smaller ones, to clear them
    * between two warmings.
    */
  private[node] final case class Bounds(verdicts: Int, prepared: Int, vrfs: Int)

  private[node] object Bounds:
    val Default: Bounds = Bounds(100000, 4096, 16384)

  /** The fewest checks worth a task of their own. */
  private val MinChunk = 8

  val defaultParallelism: Int = math.max(1, Runtime.getRuntime.availableProcessors())

  /** A fresh, empty cache whose background pool is shut down when the resource is released. */
  def resource[F[_]: Sync](
      layout: StateLayout,
      parallelism: Int = defaultParallelism
  ): Resource[F, VerificationCache] =
    Resource.make(
      Sync[F].delay(
        new VerificationCache(
          parallelism,
          Verdicts.Direct,
          layout,
          new java.util.concurrent.Semaphore(parallelism),
          Bounds.Default
        )
      )
    )(c => Sync[F].delay(c.shutdown()))

  /** An unmanaged cache for tests (no background pool is started unless an epoch rewarm happens).
    */
  private[node] def unsafe(
      parallelism: Int = defaultParallelism,
      direct: Verdicts = Verdicts.Direct,
      running: Option[java.util.concurrent.Semaphore] = None,
      layout: StateLayout = StateLayout.Node11_1,
      bounds: Bounds = Bounds.Default
  ): VerificationCache =
    new VerificationCache(
      parallelism,
      direct,
      layout,
      running.getOrElse(new java.util.concurrent.Semaphore(parallelism)),
      bounds
    )
