// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import lab.cbor.Bytes
import lab.node.consensus.{Nonce, OCertError, OCertRules, Praos, PraosConfig, TPraos}
import lab.vrf.PraosVrfCertificate as Vrf
import lab.witness.{StrictEd25519, VerificationResult}

/** Signature checks that do not depend on the ledger state, made available ahead of the sequential
  * rules.
  *
  * Verifying a transaction witness, an operational certificate or a KES signature is a pure
  * function of the bytes of the block alone: it needs no UTxO, no nonce, no parameters beyond the
  * node configuration. They are also most of the work of validating a block (about half of the time
  * on Preview). So the node verifies them for a whole batch of fetched blocks in parallel
  * ([[warm]]) and remembers the verdicts here; the rules ask for the same verdict through
  * [[witness]] and [[opCert]], which return the remembered one (once) or compute it. The verdict is
  * exactly the one the plain function gives, so the rules stay pure functions of their inputs; this
  * is memoisation, and a verdict that was never warmed is simply computed in place.
  */
object Verification:
  private final case class WitnessKey(vkey: Bytes, signature: Bytes, message: Bytes)
  private final case class HeaderKey(
      hash: Bytes,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  )

  private val Limit = 100000
  private val witnesses = new ConcurrentHashMap[WitnessKey, java.lang.Boolean]()
  private val headers = new ConcurrentHashMap[HeaderKey, Either[OCertError, Unit]]()
  private val prepared = new ConcurrentHashMap[Bytes, NodeRules.Prepared]()

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
  private lazy val pool = new java.util.concurrent.ForkJoinPool(defaultParallelism)

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

  /** Praos (Babbage and later) VRF verdict, see [[lab.vrf.PraosVrfCertificate.verify]]. */
  def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
    Vrf.alpha(input) match
      case Right(alpha) =>
        val known = vrfs.remove(VrfKey(0, alpha, vkey, proof, output))
        vrfStats.record(known != null)
        if known != null then known else Vrf.verify(input, vkey, proof, output)
      case Left(_) => Vrf.verify(input, vkey, proof, output)

  /** TPraos VRF verdict over an already derived alpha. */
  def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
    val known = vrfs.remove(VrfKey(1, alpha, vkey, proof, output))
    vrfStats.record(known != null)
    if known != null then known else Vrf.verifyAlpha(alpha, vkey, proof, output)

  /** `witnesses hits/lookups, opcerts hits/lookups, vrf hits/lookups` for the log. */
  def summary: String = s"witness=$witnessStats opCert=$opCertStats vrf=$vrfStats"

  private def warmVrf(header: Header.Shelley, config: PraosConfig): Unit =
    val hint = epochNonce.get
    if hint != null && config.geometry.epochOf(header.slot) == hint._1 then
      header.vrf match
        case HeaderVrf.Praos(cert) =>
          Praos.vrfInput(hint._2, header.slot).foreach { input =>
            Vrf.alpha(input).foreach { alpha =>
              vrfs.put(
                VrfKey(0, alpha, header.vrfVkey, cert.proof, cert.output),
                Vrf.verify(input, header.vrfVkey, cert.proof, cert.output)
              )
            }
          }
        case HeaderVrf.TPraos(nonceCert, leaderCert) =>
          for (uc, cert) <- Seq(TPraos.SeedEta -> nonceCert, TPraos.SeedL -> leaderCert) do
            val alpha = TPraos.seed(uc, header.slot, hint._2)
            vrfs.put(
              VrfKey(1, alpha, header.vrfVkey, cert.proof, cert.output),
              Vrf.verifyAlpha(alpha, header.vrfVkey, cert.proof, cert.output)
            )

  /** The block decoded while warming, if any (taken once). */
  def takePrepared(hash: Bytes): Option[NodeRules.Prepared] = Option(prepared.remove(hash))

  private def compute(key: WitnessKey): Boolean =
    StrictEd25519.verify(key.vkey, key.signature, key.message) match
      case Right(VerificationResult.SignatureVerified) => true
      case _                                           => false

  /** How often a verdict was already there (`hits`) or had to be computed by the rules (`misses`).
    */
  final class Counters:
    val hits = new java.util.concurrent.atomic.LongAdder
    val misses = new java.util.concurrent.atomic.LongAdder
    def record(hit: Boolean): Unit = if hit then hits.increment() else misses.increment()
    override def toString: String = s"${hits.sum}/${hits.sum + misses.sum}"

  val witnessStats = new Counters
  val opCertStats = new Counters
  val vrfStats = new Counters

  /** Whether `signature` is a valid witness of `message` under `vkey`. */
  def witness(vkey: Bytes, signature: Bytes, message: Bytes): Boolean =
    val key = WitnessKey(vkey, signature, message)
    val known = witnesses.remove(key)
    witnessStats.record(known != null)
    if known != null then known.booleanValue else compute(key)

  /** The OCERT signature checks of a header (see [[OCertRules.verifySignatures]]). */
  def opCert(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError, Unit] =
    val known = headers.remove(HeaderKey(header.hash, slotsPerKesPeriod, maxKesEvolutions))
    opCertStats.record(known != null)
    if known != null then known
    else OCertRules.verifySignatures(header, slotsPerKesPeriod, maxKesEvolutions)

  /** Verify everything a block's signatures need and remember the verdicts. Never fails: a block
    * that does not decode or a check that errors is left for the rules to report.
    */
  private def warmBlock(raw: Bytes, config: PraosConfig): Unit =
    lastConfig.set(config)
    if witnesses.size > Limit then witnesses.clear()
    if headers.size > Limit then headers.clear()
    if prepared.size > 4096 then prepared.clear()
    if vrfs.size > 16384 then vrfs.clear()
    NodeRules.decodeShelley(raw).foreach { p =>
      val b = p.block
      val key = HeaderKey(b.header.hash, config.slotsPerKesPeriod, config.maxKesEvolutions)
      headers.put(
        key,
        OCertRules.verifySignatures(b.header, config.slotsPerKesPeriod, config.maxKesEvolutions)
      )
      p.txs.foreach(_.foreach { tx =>
        val id = tx.body.id
        (tx.witnesses.vkeys ++ tx.witnesses.bootstrap.map(w => w.vkey -> w.signature)).foreach {
          case (vkey, signature) =>
            val k = WitnessKey(vkey, signature, id)
            witnesses.put(k, compute(k))
        }
      })
      warmVrf(b.header, config)
      prepared.put(b.header.hash, p)
    }

  /** Warm the verdicts of `blocks` using up to `parallelism` threads. */
  def warm[F[_]: Async](blocks: Vector[Bytes], config: PraosConfig, parallelism: Int): F[Unit] =
    if blocks.isEmpty then Async[F].unit
    else
      blocks
        .parTraverseN(parallelism)(b =>
          Async[F].blocking(
            try warmBlock(b, config)
            catch case scala.util.control.NonFatal(_) => ()
          )
        )
        .void

  val defaultParallelism: Int = math.max(1, Runtime.getRuntime.availableProcessors())
