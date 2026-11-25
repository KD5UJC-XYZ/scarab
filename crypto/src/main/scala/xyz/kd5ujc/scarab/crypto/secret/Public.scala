// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover

/** Evidence that a value may leave a [[Scratch]]: it is immutable and holds nothing of a scratch,
  * only what the computation publishes (a proof, an output, a public key) or a plain answer. There
  * is no instance for an array, a `Scratch#Buffer`, a scratch or a function, so none of them can be
  * the result of [[Scratch.within]], and so none can be read after its buffers are erased. The
  * trait is sealed and its one implementation private: the instances below are all there are, and a
  * new one is added here, where review sees it.
  */
private[scarab] sealed trait Public[A]

private[scarab] object Public:
  private final class Evidence[A] extends Public[A]
  private def evidence[A]: Public[A] = new Evidence[A]

  given Public[Bytes] = evidence
  given Public[Boolean] = evidence
  given Public[Int] = evidence
  given Public[Unit] = evidence
  given Public[Draft03Prover.Proved] = evidence
  given vrfFailure: Public[Draft03Prover.Failure] = evidence
  given kesFailure: Public[Sum6KesSigning.Failure] = evidence
  // keys own their arrays, which no scratch holds: a key made from a buffer copies what it keeps
  given vrfKey: Public[Draft03Prover.SigningKey] = evidence
  given kesKey: Public[Sum6KesSigning.SigningKey] = evidence
  given [F: Public, K: Public]: Public[KeyText.Read[F, K]] = evidence
  given [A: Public]: Public[Option[A]] = evidence
  given [A: Public, B: Public]: Public[Either[A, B]] = evidence
  given [A: Public, B: Public]: Public[(A, B)] = evidence
