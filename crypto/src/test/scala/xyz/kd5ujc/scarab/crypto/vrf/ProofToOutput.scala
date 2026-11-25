// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import java.util.Arrays
import xyz.kd5ujc.scarab.codec.Bytes

/** The reference's output of a proof alone, not verified: `crypto_vrf_ietfdraft03_proof_to_hash`,
  * which cardano-base's `outputFromProof` calls (060819b5,
  * `cardano-crypto-praos/src/Cardano/Crypto/VRF/Praos.hs:197-198,514-522`), step for step. Test
  * scope only: the node's verifier never hands out the output of a proof it has not verified
  * (`StrictDraft03.scala:36`), and this does not change that.
  *
  * The steps are those of `cardano-crypto-praos/cbits/vrf03/verify.c:13-41` at that revision. The
  * libsodium fork the default build links instead (the flag `external-libsodium-vrf`,
  * `cardano-crypto-praos.cabal:54-60`), IntersectMBO/libsodium `dbb48cce`, has the same function
  * line for line in `src/libsodium/crypto_vrf/ietfdraft03/verify.c:13-41`, and the same decoder,
  * its names without the `cardano_` prefix.
  *
  *   - Length. The C function reads `crypto_vrf_ietfdraft03_BYTES` (80) bytes; the reference
  *     refuses another length before it, when it decodes the proof (`rawDecodeFixedSized`,
  *     `Praos.hs:395-396`, and its test's own decoding, `Test/Crypto/VRF.hs:127`).
  *   - Gamma, the first 32 bytes (`verify.c:19-22`): `cardano_ge25519_is_canonical` refuses y >= p,
  *     bit 255 aside (`private/ed25519_ref10.c:1237-1252`); `cardano_ge25519_frombytes` refuses a y
  *     with no x and keeps x = 0 whatever the sign bit (`private/ed25519_ref10.c:329-372`). The
  *     verifier's `Ed25519Point.decode` makes the same two checks (`Ed25519Point.java:91-149`: the
  *     round trip of y at 101-108, the square root at 128-131, and the sign at 136-140, which keeps
  *     a zero x). Neither tests Gamma's order: a Gamma of small order is accepted by both.
  *   - s, the last 32 bytes (`verify.c:24-27`): refused when its top four bits are not all zero and
  *     `cardano_sc25519_is_canonical` says it is not below L (`private/ed25519_ref10.c:2686-2706`).
  *     An s whose top four bits are zero is below 2^252, so below L: the check is s >= L, the
  *     verifier's own (`StrictDraft03.scala:83-88`). It is written here as the C writes it.
  *   - c, the 16 bytes between, is not read.
  *   - The output (`verify.c:29-38`): SHA-512 of 0x04, 0x03 and the encoding of [8]Gamma, which is
  *     `Draft03Math.output` (`Draft03Math.java:107-109`).
  *
  * So it accepts what the verifier does not only through what it does not check: any c, and with it
  * any proof whose Gamma decodes and whose s is below L, verified or not. On the decoding of Gamma
  * and on s the two accept the same bytes.
  */
private[vrf] object ProofToOutput:
  enum Refusal:
    case ProofLength(actual: Int)
    case GammaEncoding
    case NoncanonicalScalar

  def apply(pi: Bytes): Either[Refusal, Bytes] =
    if pi.size != 80 then Left(Refusal.ProofLength(pi.size))
    else
      val bytes = pi.toArray
      val s = Arrays.copyOfRange(bytes, 48, 80)
      for
        gamma <- Option(Ed25519Point.decode(Arrays.copyOfRange(bytes, 0, 32)))
          .toRight(Refusal.GammaEncoding)
        _ <- Either.cond(
          (s(31) & 0xf0) == 0 || Draft03Math.integer(s).compareTo(Draft03Math.L) < 0,
          (),
          Refusal.NoncanonicalScalar
        )
      yield Bytes.fromArray(Draft03Math.output(gamma))
