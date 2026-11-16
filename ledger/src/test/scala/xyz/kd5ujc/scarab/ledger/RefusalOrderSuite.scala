// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.rules.{Gov, Test}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Empty, ShelleyBasedEra}

/** The order of the items a refusal lists, where the reference lists the items of a set: the keys
  * of the vkey witnesses that do not verify (`validateVerifiedWits`,
  * `Shelley/Rules/Utxow.hs:390-407`, cardano-ledger f649f975) and the unelected committee voters
  * (`unelectedCommitteeVoters`, `Conway/Rules/Gov.hs:652-665`, cardano-ledger f649f975), whatever
  * order the transaction gives them in.
  */
class RefusalOrderSuite extends FunSuite:
  private def filled(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))

  test("the keys of failing vkey witnesses are listed by their hashes, whatever their order"):
    val keys = Vector(1, 2, 3, 4).map(filled(32, _))
    // the order of `Ord WitVKey` for distinct keys: the key's hash, unsigned and lexicographic
    val byHash = keys.sortBy(k => Blake2b.hash224.hash(k))
    assertNotEquals(byHash, keys, "the keys' own order is not their hashes' order")
    val body = TestLedger.body("refusal-order", Vector.empty, Vector.empty, 0)
    def failing(order: Vector[Bytes]) =
      val tx = TestLedger.tx(body, Vector.empty, Witnesses(order.map(_ -> filled(64, 0))))
      TxChecks.verifiedWits(tx, Verdicts.Direct)
    val expected = Right(
      Test.failureUnless(
        false,
        PredicateFailure.utxow(UtxowFailure.InvalidWitnesses(byHash))
      )
    )
    assertEquals(failing(keys), expected)
    assertEquals(failing(keys.reverse), expected)

  test("unelected committee voters are listed once each, a script before a key, then by hash"):
    val cert = Empty.certState[Conway](using ShelleyBasedEra.SConway)
    // a script and a key twice each: the reference's set holds each once
    val voters = Vector(
      Voter.CommitteeHot(Credential.Key(filled(28, 2))),
      Voter.CommitteeHot(Credential.Script(filled(28, 5))),
      Voter.CommitteeHot(Credential.Key(filled(28, 1))),
      Voter.CommitteeHot(Credential.Script(filled(28, 3))),
      Voter.CommitteeHot(Credential.Key(filled(28, 2))),
      Voter.CommitteeHot(Credential.Script(filled(28, 5)))
    )
    // with no committee every hot credential is unelected
    assertEquals(
      Gov.unelected(None, cert, voters),
      Vector(
        Credential.Script(filled(28, 3)),
        Credential.Script(filled(28, 5)),
        Credential.Key(filled(28, 1)),
        Credential.Key(filled(28, 2))
      )
    )
    assertEquals(Gov.unelected(None, cert, voters.reverse), Gov.unelected(None, cert, voters))

  test("a verifier that fails is reported for the first witness in the reference's order"):
    val keys = Vector(1, 2, 3, 4).map(filled(32, _))
    val first = keys.minBy(k => Blake2b.hash224.hash(k))
    assertNotEquals(first, keys.head, "the first key read is not the first by hash")
    val body = TestLedger.body("refusal-order-defect", Vector.empty, Vector.empty, 0)
    // each witness's verifier fails with a defect of its own
    val defects: Map[Bytes, ProviderDefect] = keys
      .map(k =>
        k -> ProviderDefect.Ed25519(
          VerificationError.ImplementationFailure(new IllegalStateException(k.hex))
        )
      )
      .toMap
    val verdicts = new Verdicts:
      export Verdicts.Direct.{keyHash, opCert, praosVrf, takePrepared, tpraosVrf}
      def witness(vkey: Bytes, signature: Bytes, message: Bytes): Either[ProviderDefect, Boolean] =
        Left(defects(vkey))
    val tx = TestLedger.tx(body, Vector.empty, Witnesses(keys.map(_ -> filled(64, 0))))
    assertEquals(
      TxChecks.verifiedWits(tx, verdicts),
      Left(LedgerFailure.Unverifiable(body.id, defects(first)))
    )
