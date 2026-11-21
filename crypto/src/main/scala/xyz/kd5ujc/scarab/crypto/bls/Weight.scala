// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import xyz.kd5ujc.scarab.codec.Rational

/** The weight of a committee seat, of the signers of a certificate, or of the threshold they must
  * reach: `type Weight = Rational` (cardano-base c1e1ed8d `cardano-crypto-leios/src/Cardano/Crypto/
  * Leios.hs:118-120`), exact. The ledger seats each pool with its stake ratio and the quorum is
  * `ppLeiosQuorumStakeThreshold`, so a weight is a fraction of the total active stake.
  */
opaque type Weight = Rational

object Weight:
  val zero: Weight = Rational.zero

  def fromNatural(n: BigInt): Option[Weight] = if n.signum >= 0 then Some(Rational(n)) else None

  def apply(r: Rational): Weight = r

  given Ordering[Weight] = (a, b) => if a < b then -1 else if b < a then 1 else 0

  extension (w: Weight)
    def +(o: Weight): Weight = w + o
    def value: Rational = w
