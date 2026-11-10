// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto

import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.{Sum6Kes, Sum6KesSigning}
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold, StrictDraft03}
import xyz.kd5ujc.scarab.crypto.witness.{StrictEd25519, VerificationResult}

/** Time per verification of the checks a node makes for every header and witness: a transaction
  * witness (Ed25519), a header's VRF proof (draft-03), its KES signature (Sum6) and the leader
  * threshold. Not part of `check`; run it in a forked JVM, one per build compared:
  *
  * `java -cp <crypto test classpath> xyz.kd5ujc.scarab.crypto.VerifyBench [rounds]`
  *
  * The inputs come from a fixed seed, so every run verifies the same 64 items of each kind. Each
  * round verifies all of them once; after five warm-up rounds the median of `rounds` is printed per
  * item, with the number accepted (every item is valid, so it must be 64).
  */
object VerifyBench:
  private val Items = 64

  def main(args: Array[String]): Unit =
    val rounds = args.headOption.flatMap(_.toIntOption).getOrElse(15)
    val random = SecureRandom.getInstance("SHA1PRNG")
    random.setSeed(650L)
    def bytes(n: Int): Array[Byte] =
      val out = new Array[Byte](n)
      random.nextBytes(out)
      out

    val witnesses = Vector.fill(Items) {
      val key = new Ed25519PrivateKeyParameters(bytes(32), 0)
      val message = bytes(32)
      val signer = new Ed25519Signer
      signer.init(true, key)
      signer.update(message, 0, message.length)
      (
        Bytes.fromArray(key.generatePublicKey().getEncoded),
        Bytes.fromArray(signer.generateSignature()),
        Bytes.fromArray(message)
      )
    }
    val proofs = Vector.fill(Items) {
      val key = Draft03Prover.SigningKey
        .fromSeed(Bytes.fromArray(bytes(32)))
        .fold(
          e => sys.error(e.toString),
          identity
        )
      val alpha = Bytes.fromArray(bytes(32))
      val proved = key.prove(alpha).fold(e => sys.error(e.toString), identity)
      (key.publicKey, proved.proof, alpha)
    }
    val kes = Vector.fill(Items) {
      val key = Sum6KesSigning
        .generate(Bytes.fromArray(bytes(32)))
        .fold(
          e => sys.error(e.toString),
          identity
        )
      val message = Bytes.fromArray(bytes(64))
      (key.verificationKey, message, key.sign(message))
    }
    val stake =
      PraosLeaderThreshold.Fraction.checked(1, 1000).fold(e => sys.error(e.toString), identity)
    val active =
      PraosLeaderThreshold.Fraction.checked(1, 20).fold(e => sys.error(e.toString), identity)
    val values = Vector.fill(Items)(BigInt(1, bytes(32)))

    def ed25519(): Int = witnesses.count { (key, signature, message) =>
      StrictEd25519.verify(key, signature, message) == Right(VerificationResult.SignatureVerified)
    }
    def vrf(): Int = proofs.count { (key, proof, alpha) =>
      StrictDraft03.verify(key, proof, alpha) match
        case StrictDraft03.Result.Verified(_) => true
        case _                                => false
    }
    def kesVerify(): Int = kes.count { (root, message, signature) =>
      Sum6Kes.verify(root, 0, message, signature) match
        case Right(Sum6Kes.Result.SuppliedMessageSignatureVerified) => true
        case _                                                      => false
    }
    // every value is decided (eligible or not); none may hit the iteration limit
    def leader(): Int = values.count { value =>
      PraosLeaderThreshold.check(value, stake, active) match
        case Right(PraosLeaderThreshold.Decision.IterationLimit) | Left(_) => false
        case Right(_)                                                      => true
    }

    def timed(name: String)(run: () => Int): Unit =
      (1 to 5).foreach(_ => run())
      val samples = (1 to rounds).map { _ =>
        val start = System.nanoTime()
        val accepted = run()
        ((System.nanoTime() - start).toDouble / Items, accepted)
      }
      val ns = samples.map(_._1).sorted
      println(
        f"METRIC $name%-10s median_ns=${ns(ns.size / 2)}%.0f min_ns=${ns.head}%.0f accepted=${samples.map(_._2).distinct.mkString(",")}"
      )

    timed("ed25519")(() => ed25519())
    timed("vrf")(() => vrf())
    timed("kes")(() => kesVerify())
    timed("leader")(() => leader())
