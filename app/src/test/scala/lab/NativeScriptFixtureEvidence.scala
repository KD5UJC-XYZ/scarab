// SPDX-License-Identifier: Apache-2.0
package lab

import java.security.MessageDigest
import lab.cbor.Bytes
import lab.ledger.{ConwayStake, NativeScript, TxIn}
import lab.submission.SignedTransaction
import scala.util.control.NonFatal

/** Test-only bootstrap evidence. Reference funding is not Scala admission or a runtime import. */
private[lab] object NativeScriptFixtureEvidence:
  final case class Bootstrap(
      fundedInput: TxIn,
      scriptHash: Bytes,
      wholeUtxoSHA256: Bytes,
      mempackSHA256: Bytes,
      seedSHA256: Bytes,
      debugSHA256: Bytes,
      entries: Int
  ):
    val scope = "reference-only-fixture-funding"
    val fullLedgerValidated = false
    val runtimeImport = false

  final case class Original(
      transactionId: Bytes,
      envelopeSHA256: Bytes,
      bodySHA256: Bytes,
      witnessesSHA256: Bytes,
      bytes: Int
  )
  private def sha(bytes: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes.toArray))
  private def get[A](result: Either[?, A]): A =
    result.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  /** All whole-state sources must describe the same confirmed script output. Point, fees and
    * capture identities remain bound by the existing native packet/endpoint manifests.
    */
  def bootstrap(
      mempack: Bytes,
      whole: Bytes,
      seed: Bytes,
      debug: Bytes,
      originalScript: Bytes,
      expectedInput: TxIn,
      expectedCoin: BigInt
  ): Either[String, Bootstrap] = protect {
    require(expectedCoin > 0 && expectedCoin <= (BigInt(1) << 64) - 1, "funded coin bound")
    val script = get(NativeScript.decode(originalScript))
    val unpacked = get(NativeCoinUtxoMemPack.decode(mempack))
    val nativeWhole = get(NativeEndpointLedger.utxoSemantics(whole))
    require(
      get(NativeEndpointLedger.utxoSemantics(unpacked)) == nativeWhole,
      "MemPack/whole mismatch"
    )
    get(NativeEndpointLedger.checkReplacement(seed, debug, whole))
    val expectedAddress = Bytes(Vector(0x70.toByte) ++ script.hash.value)
    val funded = nativeWhole.getOrElse(
      expectedInput,
      throw new IllegalArgumentException("funded input absent")
    )
    require(
      funded._1.isEmpty && funded._2 == expectedCoin && funded._3 == expectedAddress,
      "funded script credential/address/value mismatch"
    )
    val scripts = nativeWhole.filter((_, out) => ((out._3.value.head & 255) >>> 4) == 7)
    require(scripts.keySet == Set(expectedInput), "exactly one enterprise script bootstrap input")
    val stake = get(ConwayStake.decodeUtxo(whole))
    require(
      stake(expectedInput).credential.isEmpty && stake(expectedInput).coin == expectedCoin,
      "enterprise script input must not add instantaneous stake"
    )
    Bootstrap(
      expectedInput,
      script.hash,
      sha(whole),
      sha(mempack),
      sha(seed),
      sha(debug),
      nativeWhole.size
    )
  }

  def originals(raw: Bytes): Either[String, Original] =
    SignedTransaction
      .checked(raw)
      .left
      .map(_.toString)
      .map(tx =>
        Original(
          tx.transactionId,
          tx.envelopeSHA256,
          sha(tx.originalBody),
          sha(tx.originalWitnesses),
          tx.byteSize
        )
      )

  /** These spans must come from the applied follower block, not a reference CLI receipt. */
  def inclusionOriginals(
      submitted: Bytes,
      includedBody: Bytes,
      includedWitnesses: Bytes
  ): Either[String, Original] = protect {
    val tx = get(SignedTransaction.checked(submitted))
    require(tx.originalBody == includedBody, "included body original differs")
    require(tx.originalWitnesses == includedWitnesses, "included witness original differs")
    get(originals(submitted))
  }
