// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Verdicts

/** The era decides the verification profile: Byron's rules accept what the reference's Byron path
  * accepts, every later era (`Verdicts`, which also verifies Shelley bootstrap witnesses, as
  * `Cardano.Ledger.Keys.Bootstrap.verifyBootstrapWit` does with `Ed25519DSIGN`) stays strict. The
  * rows are from `fixtures/witness-byron/generated.tsv`; the reference accepts each of them
  * (`reference-generated.tsv`, pinned by `ByronEd25519ReferenceSuite`).
  */
class ByronSignatureProfileSuite extends FunSuite:
  private val rows: Map[String, (Bytes, Bytes, Bytes)] =
    Files
      .readAllLines(Path.of("fixtures/witness-byron/generated.tsv"))
      .asScala
      .toVector
      .map(_.split("\t", -1).toVector)
      .collect { case Vector(id, key, signature, message) =>
        def bytes(hex: String) = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
        id -> (bytes(key), bytes(signature), bytes(message))
      }
      .toMap

  // one row per class: non-canonical S, small-order A, non-canonical A
  private val referenceAccepted =
    Vector("s-plus-1L-2", "a-order8-3-0", "a-y-p-plus-1-sign0-order1-0", "a-x0-sign-identity-0")

  test("Byron witnesses accept the reference-accepted rows; later eras refuse them") {
    referenceAccepted.foreach { id =>
      val (key, signature, message) = rows.getOrElse(id, fail(s"missing row $id"))
      assert(ByronSig.verifyRedeem(key, signature, message), id)
      assert(ByronSig.verify(key ++ Bytes.fromArray(new Array[Byte](32)), signature, message), id)
      assert(!Verdicts.Direct.witness(key, signature, message), id)
    }
  }
