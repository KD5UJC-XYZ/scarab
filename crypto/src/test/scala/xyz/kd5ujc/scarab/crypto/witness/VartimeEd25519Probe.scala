// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

/** Reads of `VartimeEd25519`'s working storage for the suites: what a decoded key is. */
object VartimeEd25519Probe:
  /** The canonical encoding of the point `VartimeEd25519.decode` reads from `e`, if any. */
  def decoded(e: Array[Byte]): Option[Array[Byte]] =
    val w = new VartimeEd25519()
    Option.when(w.decode(w.point, e, 0)) {
      VartimeEd25519.toProjective(w.projective, w.point)
      w.encodeProjective()
      w.encoding.clone()
    }
