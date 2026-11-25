// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf.secret

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover

/** Evidence that a value may leave a [[Scratch]]: it is immutable and holds nothing of a scratch,
  * only what the computation publishes (a proof, an output, a public key) or a plain answer. There
  * is no instance for an array, a [[Scratch.Buffer]], a scratch or a function, so none of them can
  * be the result of [[Scratch.within]], and so none can be read after its buffers are erased.
  */
private[vrf] trait Public[A]

private[vrf] object Public:
  private def evidence[A]: Public[A] = new Public[A] {}

  given Public[Bytes] = evidence
  given Public[Boolean] = evidence
  given Public[Unit] = evidence
  given Public[Draft03Prover.Proved] = evidence
  given [A: Public]: Public[Option[A]] = evidence
  given [A: Public, B: Public]: Public[Either[A, B]] = evidence
  given [A: Public, B: Public]: Public[(A, B)] = evidence
