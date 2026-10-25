// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf, Prepared, Verdicts}
import cats.effect.{Async, Resource, Sync}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Nonce, OCertError, OCertRules, ProviderDefect}
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig, TPraos}
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf

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
  * so sharing one between threads changes timing, never results. A provider defect
  * ([[xyz.kd5ujc.scarab.ledger.ProviderDefect]]) is no verdict and is never kept: the rules ask the
  * provider again and meet it themselves.
  */
final class VerificationCache private (val parallelism: Int) extends Verdicts:
  private final case class WitnessKey(vkey: Bytes, signature: Bytes, message: Bytes)
  private final case class HeaderKey(
      hash: Bytes,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  )

  private val Limit = 100000
  private def MinChunk = VerificationCache.MinChunk
  private val witnesses = new ConcurrentHashMap[WitnessKey, java.lang.Boolean]()
  private val headers = new ConcurrentHashMap[HeaderKey, Either[OCertError, Unit]]()
  private val prepared = new ConcurrentHashMap[Bytes, Prepared]()

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
      pool.execute { () =>
        try
          val pending = prepared.values.asScala.toVector
            .filter(p => config.geometry.epochOf(p.block.header.slot) == epoch)
            .sortBy(_.block.header.slot)
          pending.asJava.parallelStream().forEach(p => warmVrf(p.block.header, config))
        catch case scala.util.control.NonFatal(_) => ()
      }

  /** Praos (Babbage and later) VRF verdict, see
    * [[xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate.verify]].
    */
  override def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
    Vrf.alpha(input) match
      case Right(alpha) =>
        val known = vrfs.remove(VrfKey(0, alpha, vkey, proof, output))
        vrfStats.record(known != null)
        if known != null then known else Verdicts.Direct.praosVrf(input, vkey, proof, output)
      case Left(_) => Vrf.verify(input, vkey, proof, output)

  /** TPraos VRF verdict over an already derived alpha. */
  override def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
    val known = vrfs.remove(VrfKey(1, alpha, vkey, proof, output))
    vrfStats.record(known != null)
    if known != null then known else Verdicts.Direct.tpraosVrf(alpha, vkey, proof, output)

  /** `witnesses hits/lookups, opcerts hits/lookups, vrf hits/lookups, warmed skipped/checked` for
    * the log. The last is the signature work of [[warm]] that found its verdict already there: with
    * the hits it tells whether the checks run ahead are the checks the rules ask for.
    */
  def summary: String =
    s"witness=$witnessStats opCert=$opCertStats vrf=$vrfStats warmed=$warmedStats"

  private def warmVrf(header: Header.Shelley, config: PraosConfig): Unit =
    warmVrf(header, config, epochNonce.get)

  private def warmVrf(
      header: Header.Shelley,
      config: PraosConfig,
      hint: (BigInt, Nonce)
  ): Unit =
    if hint != null && config.geometry.epochOf(header.slot) == hint._1 then
      header.vrf match
        case HeaderVrf.Praos(cert) =>
          Praos.vrfInput(hint._2, header.slot).foreach { input =>
            Vrf.alpha(input).foreach { alpha =>
              keepVrf(
                VrfKey(0, alpha, header.vrfVkey, cert.proof, cert.output),
                Vrf.verify(input, header.vrfVkey, cert.proof, cert.output)
              )
            }
          }
        case HeaderVrf.TPraos(nonceCert, leaderCert) =>
          for (uc, cert) <- Seq(TPraos.SeedEta -> nonceCert, TPraos.SeedL -> leaderCert) do
            val alpha = TPraos.seed(uc, header.slot, hint._2)
            keepVrf(
              VrfKey(1, alpha, header.vrfVkey, cert.proof, cert.output),
              Vrf.verifyAlpha(alpha, header.vrfVkey, cert.proof, cert.output)
            ): Unit

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
      if headers.size > Limit then headers.clear()
      keepHeader(
        HeaderKey(header.hash, slotsPerKesPeriod, maxKesEvolutions),
        Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)
      )

    def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      Vrf.alpha(input) match
        case Right(alpha) =>
          if vrfs.size > 16384 then vrfs.clear()
          keepVrf(
            VrfKey(0, alpha, vkey, proof, output),
            Verdicts.Direct.praosVrf(input, vkey, proof, output)
          )
        case Left(_) => Vrf.verify(input, vkey, proof, output)

    def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      if vrfs.size > 16384 then vrfs.clear()
      keepVrf(
        VrfKey(1, alpha, vkey, proof, output),
        Verdicts.Direct.tpraosVrf(alpha, vkey, proof, output)
      )

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
    if shelley.isEmpty then Async[F].unit
    else
      val epoch = config.geometry.epochOf(shelley.head.slot)
      val nonce = xyz.kd5ujc.scarab.consensus.Praos.tick(state, shelley.head.era, epoch).epochNonce
      shelley
        .parTraverseN(parallelism)(h =>
          Async[F].blocking(
            try
              keepHeader(
                HeaderKey(h.hash, config.slotsPerKesPeriod, config.maxKesEvolutions),
                OCertRules.verifySignatures(h, config.slotsPerKesPeriod, config.maxKesEvolutions)
              )
              warmVrf(h, config, (epoch, nonce))
            catch case scala.util.control.NonFatal(_) => ()
          )
        )
        .void

  /** The block decoded while warming, if any (taken once). */
  override def takePrepared(hash: Bytes): Option[Prepared] = Option(prepared.remove(hash))

  private def compute(key: WitnessKey): Either[ProviderDefect, Boolean] =
    Verdicts.Direct.witness(key.vkey, key.signature, key.message)

  /** The kept OCERT verdict of `key`, or `verdict` kept now unless it is a provider defect. */
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

  /** The kept VRF verdict of `key`, or `verdict` kept now unless it records a provider defect. */
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
      val hash = Verdicts.Direct.keyHash(vkey)
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
    val known = witnesses.remove(key)
    witnessStats.record(known != null)
    if known != null then Right(known.booleanValue) else compute(key)

  /** The OCERT signature checks of a header (see [[OCertRules.verifySignatures]]). */
  override def opCert(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError | ProviderDefect, Unit] =
    val known = headers.remove(HeaderKey(header.hash, slotsPerKesPeriod, maxKesEvolutions))
    opCertStats.record(known != null)
    if known != null then known
    else Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)

  /** One independent signature check whose verdict goes into a cache. */
  private type Job = () => Unit

  // Ranges are warmed concurrently (one `warm` per fetched range): all of them together run at most
  // `parallelism` chunks at once, so that several single-block batches do not put several times
  // as many runnable threads as CPUs on the machine.
  private val running = new java.util.concurrent.Semaphore(parallelism)

  /** A block whose checks are running: the decoded block is offered to the rules (`prepared`) when
    * the last of its jobs is done, so that the rules find the verdicts together with the block.
    */
  private final class Pending(val block: Prepared, jobs: Int):
    private val remaining = new java.util.concurrent.atomic.AtomicInteger(jobs)
    private val failed = new java.util.concurrent.atomic.AtomicBoolean(false)
    def failure(): Unit = failed.set(true)
    def finished(): Boolean = remaining.decrementAndGet() == 0 && !failed.get

  /** Decode a block and split its signature checks into independent jobs: the operational
    * certificate and KES signature of the header, the VRF proof(s), and one job per distinct vkey
    * or bootstrap witness. Never fails: a block that does not decode has no jobs and is left for
    * the rules to report.
    */
  private def jobsOf(raw: Bytes, config: PraosConfig): Vector[Job] =
    lastConfig.set(config)
    if witnesses.size > Limit then witnesses.clear()
    if headers.size > Limit then headers.clear()
    if prepared.size > 4096 then prepared.clear()
    if vrfs.size > 16384 then vrfs.clear()
    NodeRules.decodeShelley(raw).fold(Vector.empty[Job]) { p =>
      val b = p.block
      val key = HeaderKey(b.header.hash, config.slotsPerKesPeriod, config.maxKesEvolutions)
      val triples = p.txs.toVector
        .flatMap(_.toOption)
        .flatMap { tx =>
          val id = tx.body.id
          (tx.witnesses.vkeys ++ tx.witnesses.bootstrap.map(w => w.vkey -> w.signature)).map {
            (vkey, signature) => WitnessKey(vkey, signature, id)
          }
        }
        .distinct
      val checks: Vector[Job] =
        // header-first validation may have computed it already (see forHeaders)
        Vector[Job](
          () =>
            keepHeader(
              key,
              OCertRules
                .verifySignatures(b.header, config.slotsPerKesPeriod, config.maxKesEvolutions)
            ): Unit,
          () => warmVrf(b.header, config)
        ) ++ triples.map { k =>
          (() =>
            // a verdict that is still there (the same block warmed again: a second peer's batch, a
            // range fetched again after a rollback) is a function of its key, so it is not computed
            // again
            val present = witnesses.containsKey(k)
            warmedStats.record(present)
            if !present then
              keyHash(k.vkey)
              compute(k).foreach(valid => witnesses.put(k, valid)): Unit
          ): Job
        }
      val pending = new Pending(p, checks.size)
      checks.map { job => () =>
        try job()
        catch case scala.util.control.NonFatal(_) => pending.failure()
        finally if pending.finished() then prepared.put(b.header.hash, p): Unit
      }
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
      val guarded: (=> Vector[Job]) => Vector[Job] = body =>
        try body
        catch case scala.util.control.NonFatal(_) => Vector.empty
      for
        jobs <- blocks
          .parTraverseN(parallelism)(b => Async[F].blocking(guarded(jobsOf(b, config))))
          .map(_.flatten)
        _ <- chunked(jobs, parallelism)
          .parTraverseN(parallelism)(chunk =>
            Async[F].blocking {
              running.acquire()
              try chunk.foreach(_())
              finally running.release()
            }
          )
      yield ()

  /** Warm with the cache's own default parallelism. */
  def warm[F[_]: Async](blocks: Vector[Bytes], config: PraosConfig): F[Unit] =
    warm[F](blocks, config, parallelism)

object VerificationCache:
  /** The fewest checks worth a task of their own. */
  private val MinChunk = 8

  val defaultParallelism: Int = math.max(1, Runtime.getRuntime.availableProcessors())

  /** A fresh, empty cache whose background pool is shut down when the resource is released. */
  def resource[F[_]: Sync](parallelism: Int = defaultParallelism): Resource[F, VerificationCache] =
    Resource.make(Sync[F].delay(new VerificationCache(parallelism)))(c =>
      Sync[F].delay(c.shutdown())
    )

  /** An unmanaged cache for tests (no background pool is started unless an epoch rewarm happens).
    */
  private[node] def unsafe(parallelism: Int = defaultParallelism): VerificationCache =
    new VerificationCache(parallelism)
