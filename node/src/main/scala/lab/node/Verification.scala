// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.ConcurrentHashMap
import lab.cbor.Bytes
import lab.node.consensus.{OCertError, OCertRules, PraosConfig}
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

  /** The block decoded while warming, if any (taken once). */
  def takePrepared(hash: Bytes): Option[NodeRules.Prepared] = Option(prepared.remove(hash))

  private def compute(key: WitnessKey): Boolean =
    StrictEd25519.verify(key.vkey, key.signature, key.message) match
      case Right(VerificationResult.SignatureVerified) => true
      case _                                           => false

  /** Whether `signature` is a valid witness of `message` under `vkey`. */
  def witness(vkey: Bytes, signature: Bytes, message: Bytes): Boolean =
    val key = WitnessKey(vkey, signature, message)
    val known = witnesses.remove(key)
    if known != null then known.booleanValue else compute(key)

  /** The OCERT signature checks of a header (see [[OCertRules.verifySignatures]]). */
  def opCert(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError, Unit] =
    val known = headers.remove(HeaderKey(header.hash, slotsPerKesPeriod, maxKesEvolutions))
    if known != null then known
    else OCertRules.verifySignatures(header, slotsPerKesPeriod, maxKesEvolutions)

  /** Verify everything a block's signatures need and remember the verdicts. Never fails: a block
    * that does not decode or a check that errors is left for the rules to report.
    */
  private def warmBlock(raw: Bytes, config: PraosConfig): Unit =
    if witnesses.size > Limit then witnesses.clear()
    if headers.size > Limit then headers.clear()
    if prepared.size > 4096 then prepared.clear()
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
