// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Order
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Credential as OldCredential, DRep as OldDRep}
import xyz.kd5ujc.scarab.ledger.state as mirror
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders

/** The orders of the ledger-state mirror agree with the orders the node-to-client answers write
  * maps and sets in for the node's own types ([[NodeOrders]], byte-proven against the Haskell
  * node), so converting one to the other changes no bytes.
  */
class MirrorOrderAgreementSuite extends ScalaCheckSuite:
  import NodeOrders.given

  private val hashes: Gen[Bytes] =
    for
      head <- Gen.oneOf(0, 1, 0x7f, 0x80, 0xff)
      last <- Gen.oneOf(0, 1, 0x7f, 0x80, 0xff)
    yield Bytes.fromArray(Array.tabulate(28) {
      case 0  => head.toByte
      case 27 => last.toByte
      case _  => 0.toByte
    })

  private val credentials: Gen[OldCredential] =
    hashes.flatMap(h => Gen.oneOf(OldCredential.Key(h), OldCredential.Script(h)))

  private val dreps: Gen[OldDRep] = Gen.oneOf(
    credentials.map(OldDRep.Registered(_)),
    Gen.const(OldDRep.AlwaysAbstain),
    Gen.const(OldDRep.AlwaysNoConfidence)
  )

  private def hash[A](bytes: Bytes)(make: Bytes => Either[mirror.VocabError, A]): A =
    make(bytes).fold(e => fail(e.toString), identity)

  private def credential(c: OldCredential): mirror.Credential[mirror.Staking] = c match
    case OldCredential.Key(h) => mirror.Credential.KeyHashObj(hash(h)(mirror.KeyHash.fromBytes))
    case OldCredential.Script(h) =>
      mirror.Credential.ScriptHashObj(hash(h)(mirror.ScriptHash.fromBytes))

  private def drep(d: OldDRep): mirror.DRep = d match
    case OldDRep.Registered(OldCredential.Key(h)) =>
      mirror.DRep.DRepKeyHash(hash(h)(mirror.KeyHash.fromBytes))
    case OldDRep.Registered(OldCredential.Script(h)) =>
      mirror.DRep.DRepScriptHash(hash(h)(mirror.ScriptHash.fromBytes))
    case OldDRep.AlwaysAbstain      => mirror.DRep.DRepAlwaysAbstain
    case OldDRep.AlwaysNoConfidence => mirror.DRep.DRepAlwaysNoConfidence

  property("Credential: the mirror order is the node's") {
    forAll(credentials, credentials) { (a, b) =>
      Integer.signum(Order[OldCredential].compare(a, b)) ==
        Integer.signum(
          Order[mirror.Credential[mirror.Staking]].compare(credential(a), credential(b))
        )
    }
  }

  property("DRep: the mirror order is the node's") {
    forAll(dreps, dreps) { (a, b) =>
      Integer.signum(Order[OldDRep].compare(a, b)) ==
        Integer.signum(Order[mirror.DRep].compare(drep(a), drep(b)))
    }
  }

  property("sorting a list agrees element for element") {
    forAll(Gen.listOf(dreps)) { ds =>
      ds.sorted(using Order[OldDRep].toOrdering).map(drep) ==
        ds.map(drep).sorted(using Order[mirror.DRep].toOrdering)
    }
  }
