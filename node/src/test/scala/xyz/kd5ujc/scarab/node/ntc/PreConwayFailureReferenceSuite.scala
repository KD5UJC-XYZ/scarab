// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.protocol.Era
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The failures of the eras before Conway against what the reference node (11.1.3) answered.
  *
  * `fixtures/devnet/refused-raw-eras.jsonl` holds the raw `ApplyTxErr` that the Haskell node sent
  * over LocalTxSubmission for each transaction of the `e2e` scenario that must be refused while the
  * chain is in an earlier era (`devnet.py e2e`, `eraload.keep_raw_reason`), with the transaction.
  * The cardano-cli of the reference submits in the era of the chain, so these exist for Shelley,
  * Allegra, Mary and Alonzo (the e2e load has no refusal in Babbage).
  *
  * Each failure is rebuilt from the payload of the recorded reason (the outputs, the slots) and
  * written by [[PreConwayFailureCbor]]; the bytes must be the recorded ones.
  */
class PreConwayFailureReferenceSuite extends FunSuite:
  private final case class Record(label: String, era: Era, mustReject: String, reason: Bytes)

  private def records: Vector[Record] =
    Files
      .readAllLines(Path.of("fixtures/devnet/refused-raw-eras.jsonl"))
      .asScala
      .toVector
      .map { line =>
        val json = parse(line).fold(e => fail(e.message), identity).hcursor
        def text(key: String) = json.get[String](key).fold(e => fail(e.message), identity)
        val era = text("era") match
          case "shelley" => Era.Shelley
          case "allegra" => Era.Allegra
          case "mary"    => Era.Mary
          case "alonzo"  => Era.Alonzo
          case "babbage" => Era.Babbage
          case other     => fail(s"era $other")
        Record(
          text("label"),
          era,
          text("mustReject"),
          Bytes.fromHex(text("reason")).fold(e => fail(e), identity)
        )
      }

  /** The item at the path of array indices. */
  private def at(n: Node, path: Int*): Node = path.foldLeft(n) { (node, i) =>
    node.value match
      case CborValue.Arr(xs) if xs.isDefinedAt(i) => xs(i)
      case other                                  => fail(s"no item $i in $other")
  }

  private def items(n: Node): Vector[Node] = n.value match
    case CborValue.Arr(xs) => xs
    case other             => fail(s"not an array: $other")

  private def uint(n: Node): BigInt = n.value match
    case CborValue.UInt(v) => v
    case other             => fail(s"not an unsigned integer: $other")

  private def txOut(n: Node): TxOut = TxOut.decode(n).fold(e => fail(e), identity)

  /** The failure the recorded reason reports, from its payload. */
  private def failureOf(r: Record, reason: Node): PredicateFailure =
    // [[era, [failure]]]: the LEDGER failure is the first of the list
    val ledger = at(reason, 0, 1, 0)
    r.mustReject match
      case "OutputTooSmallUTxO" =>
        // Shelley to Mary: LEDGER [0, UTXOW [4, UTXO [6, outputs]]]
        PredicateFailure.utxo(
          UtxoFailure.OutputTooSmall(items(at(ledger, 1, 1, 1)).map(txOut))
        )
      case "OutputTooBigUTxO" if r.era == Era.Alonzo =>
        // LEDGER [0, UTXOW [0, [4, UTXO [12, [[size, max, output]]]]]]
        PredicateFailure.utxo(
          UtxoFailure.OutputTooBig(
            items(at(ledger, 1, 1, 1, 1)).map(t =>
              (uint(at(t, 0)).toInt, uint(at(t, 1)).toInt, txOut(at(t, 2)))
            )
          )
        )
      case "OutputTooBigUTxO" =>
        // Allegra and Mary: the outputs alone (the sizes are not in the failure)
        PredicateFailure.utxo(
          UtxoFailure.OutputTooBig(items(at(ledger, 1, 1, 1)).map(o => (0, 0, txOut(o))))
        )
      case "MIRCertificateTooLate" =>
        // LEDGER [1, DELEGS [1, DELPL [1, DELEG [8, [slot, first slot too late]]]]]
        val mismatch = at(ledger, 1, 1, 1, 1)
        PredicateFailure.deleg(
          DelegFailure.MIRCertificateTooLateinEpoch(
            Mismatch(uint(at(mismatch, 0)), uint(at(mismatch, 1)))
          )
        )
      case "MIRTransferNotCurrentlyAllowed" =>
        PredicateFailure.deleg(DelegFailure.MIRTransferNotCurrentlyAllowed)
      case "MIRNegativesNotCurrentlyAllowed" =>
        PredicateFailure.deleg(DelegFailure.MIRNegativesNotCurrentlyAllowed)
      case other => fail(s"${r.label}: no rebuilding for $other")

  test("the recorded reasons are Shelley to Alonzo, one per kind of refusal of the e2e load") {
    assertEquals(
      records.map(r => (r.era, r.mustReject)),
      Vector(
        (Era.Shelley, "OutputTooSmallUTxO"),
        (Era.Shelley, "MIRCertificateTooLate"),
        (Era.Allegra, "OutputTooSmallUTxO"),
        (Era.Mary, "OutputTooSmallUTxO"),
        (Era.Mary, "OutputTooBigUTxO"),
        (Era.Mary, "MIRTransferNotCurrentlyAllowed"),
        (Era.Mary, "MIRNegativesNotCurrentlyAllowed"),
        (Era.Alonzo, "OutputTooBigUTxO")
      )
    )
  }

  records.foreach { r =>
    test(s"${r.era} ${r.mustReject} (${r.label}): the bytes are the reference's") {
      val reason = Cbor.decode(r.reason).fold(e => fail(s"$e"), identity)
      val failure = failureOf(r, reason)
      assertEquals(
        PreConwayFailureCbor.applyTxError(r.era, Vector(failure)).map(_.hex),
        Some(r.reason.hex)
      )
    }
  }
