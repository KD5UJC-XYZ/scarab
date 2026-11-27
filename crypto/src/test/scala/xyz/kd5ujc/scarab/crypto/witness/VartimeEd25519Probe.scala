// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

/** Reads of the working storage of `VartimeEd25519` for the suites and the fuzz targets: what a
  * decoded key is, and what its double multiplication gives.
  */
object VartimeEd25519Probe:
  /** The canonical encoding of the point `VartimeEd25519.decode` reads from `e`, if any. */
  def decoded(e: Array[Byte]): Option[Array[Byte]] =
    val w = new VartimeEd25519()
    Option.when(w.decode(w.point, e, 0)) {
      VartimeEd25519.toProjective(w.projective, w.point)
      w.encodeProjective()
      w.encoding.clone()
    }

  /** The canonical encoding of [a]A + [b]B by the verifiers' double multiplication, A the point
    * `VartimeEd25519.decode` reads from `point` (y modulo p, any order), or none when it reads
    * none. `a` and `b` are 32 bytes below 2^255, the range of their non-adjacent forms.
    */
  def doubleScalarMultiplyBasepoint(
      a: Array[Byte],
      point: Array[Byte],
      b: Array[Byte]
  ): Option[Array[Byte]] =
    val w = new VartimeEd25519()
    Option.when(w.decode(w.point, point, 0)) {
      w.oddMultiples(w.point)
      VartimeEd25519.nonAdjacentForm(w.nafA, a, 0)
      VartimeEd25519.nonAdjacentForm(w.nafB, b, 0)
      w.doubleScalarMultiplyBasepoint()
      w.encodeProjective()
      w.encoding.clone()
    }
