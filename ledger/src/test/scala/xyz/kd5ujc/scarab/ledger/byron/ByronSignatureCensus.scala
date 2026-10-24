// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{ByronEd25519, StrictEd25519, VerificationResult}
import xyz.kd5ujc.scarab.ledger.{Block, ByronDelegation, Header, LedgerDecodeError}

/** Every Ed25519 signature the Byron rules verify in a sequence of recorded blocks (and the genesis
  * delegation certificates), with the message each is verified over, as `ByronRules`,
  * `DelegationRules`, `ByronUpdate` and `ByronLedger` build it. A replay suite uses it to count the
  * signatures of a network's recorded Byron era and to compare the two verification profiles on
  * each.
  */
object ByronSignatureCensus:
  enum Kind:
    case Header, GenesisCertificate, Certificate, Proposal, Vote, TxWitness, RedeemWitness

  final case class Signed(kind: Kind, key: Bytes, signature: Bytes, message: Bytes):
    private def accepted(result: Either[?, VerificationResult]) =
      result == Right(VerificationResult.SignatureVerified)
    def byron: Boolean = accepted(ByronEd25519.verify(key, signature, message))
    def strict: Boolean = accepted(StrictEd25519.verify(key, signature, message))

  /** `ByronRules.verifySignature`: the heavy block tag of the header's genesis key. */
  private val SignMainBlockHeavy = 0x09

  private def certificate(kind: Kind, magic: Bytes)(c: ByronCert): Signed =
    val signed = ByronCbor.bytes(ByronCbor.concat(ByronCbor.of(0x30, 0x30), c.delegate, c.rawEpoch))
    Signed(
      kind,
      c.issuer.take(32),
      c.signature,
      ByronSig.tag(ByronSig.SignCertificate, magic) ++ signed
    )

  def genesis(magic: Bytes, delegations: Vector[ByronDelegation]): Vector[Signed] =
    delegations.map(d =>
      certificate(Kind.GenesisCertificate, magic)(
        ByronCert(Bytes.empty, d.epoch, ByronCbor.uint(d.epoch), d.issuer, d.delegate, d.cert)
      )
    )

  def of(magic: Bytes, block: Block.Byron): Either[LedgerDecodeError, Vector[Signed]] =
    val header = block.header.main.toVector.collect {
      case Header.ByronMain(leader, d: Header.ByronSignature.Delegated, signed) =>
        Signed(
          Kind.Header,
          d.delegate.take(32),
          d.signature,
          ByronCbor.concat(
            Bytes.fromArray("01".getBytes("US-ASCII")),
            leader,
            ByronCbor.of(SignMainBlockHeavy),
            magic,
            signed
          )
        )
    }
    if block.header.boundary then Right(header)
    else
      ByronBody.decode(block.body).map { body =>
        val certificates = body.delegation.map(certificate(Kind.Certificate, magic))
        val proposal = body.update.proposal.toVector.map(p =>
          Signed(
            Kind.Proposal,
            p.issuer.take(32),
            p.signature,
            ByronSig.tag(ByronSig.SignUpdateProposal, magic) ++ p.signedBody
          )
        )
        val votes = body.update.votes.map(v =>
          Signed(
            Kind.Vote,
            v.voter.take(32),
            v.signature,
            ByronSig.tag(ByronSig.SignUpdateVote, magic) ++
              ByronCbor.of(0x82) ++ v.rawProposalId ++ ByronCbor.of(0xf5)
          )
        )
        val witnesses = body.txs.flatMap { aux =>
          val signedHash = ByronCbor.of(0x58, 0x20) ++ aux.tx.id
          aux.witnesses.map {
            case ByronWitness.VerKey(k, s) =>
              Signed(
                Kind.TxWitness,
                k.take(32),
                s,
                ByronSig.tag(ByronSig.SignTx, magic) ++ signedHash
              )
            case ByronWitness.Redeem(k, s) =>
              Signed(
                Kind.RedeemWitness,
                k,
                s,
                ByronSig.tag(ByronSig.SignRedeemTx, magic) ++ signedHash
              )
          }
        }
        header ++ certificates ++ proposal ++ votes ++ witnesses
      }

  def all(magic: Bytes, blocks: Vector[Block.Byron]): Either[LedgerDecodeError, Vector[Signed]] =
    blocks.flatTraverse(of(magic, _))
