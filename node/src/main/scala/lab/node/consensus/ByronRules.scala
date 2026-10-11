// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import cats.syntax.all.*
import lab.cbor.Bytes
import lab.node.{ByronGenesis, Header}
import lab.node.Header.ByronSignature
import lab.witness.{StrictEd25519, VerificationResult}

/** How a Byron main header's signature was established. As with the leader threshold in bootstrap
  * mode, a header whose signature was not fully checked says so rather than counting as verified.
  */
enum ByronCheck:
  /** The leader key is a genesis key, its delegation is the genesis one and the delegate's
    * signature over the header verified.
    */
  case Verified

  /** Structure and linkage hold but the signature was not established. */
  case Unchecked(reason: String)

  /** An epoch-boundary block: no signature, only structure and linkage. */
  case Boundary

  def label: String = this match
    case Verified     => "verified"
    case Unchecked(_) => "unchecked"
    case Boundary     => "boundary"

/** Byron chain-follow state: the last accepted header. */
final case class ByronTip(hash: Bytes, epoch: BigInt, slot: BigInt, boundary: Boolean)

/** Byron header validation: linkage to the previous block (or the genesis hash), slot and epoch
  * order, the protocol magic, and for main blocks that the slot leader is a genesis key whose heavy
  * delegation (from the genesis file) names the signer, and the signature itself. Delegation
  * certificates posted on chain are not tracked: a header delegated differently from the genesis is
  * accepted structurally and reported [[ByronCheck.Unchecked]]. Block bodies are not validated
  * (Byron transactions are outside this node's ledger scope).
  */
object ByronRules:
  final case class Config(genesis: ByronGenesis):
    def epochSlots: BigInt = genesis.epochSlots

  /** Domain tag of heavy-delegation main-block signatures, followed by the CBOR protocol magic. */
  private val SignMainBlockHeavy: Byte = 0x09

  private def cborUInt(n: BigInt): Vector[Byte] =
    if n < 24 then Vector(n.toByte)
    else if n < 256 then Vector(0x18.toByte, n.toByte)
    else if n < 65536 then Vector(0x19.toByte, (n >> 8).toByte, n.toByte)
    else Vector(0x1a.toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)

  def update(
      tip: Option[ByronTip],
      header: Header.Byron,
      config: Config
  ): Either[String, (ByronTip, ByronCheck)] =
    val expectedPrev = tip.map(_.hash).getOrElse(config.genesis.hash)
    for
      _ <- Either.cond(
        header.protocolMagic == config.genesis.protocolMagic,
        (),
        s"protocol magic ${header.protocolMagic} differs from the genesis ${config.genesis.protocolMagic}"
      )
      _ <- Either.cond(
        header.prevHash.contains(expectedPrev),
        (),
        s"previous hash ${header.prevHash.fold("none")(_.hex)} is not the tip ${expectedPrev.hex}"
      )
      _ <- Either.cond(
        header.slotInEpoch < config.epochSlots,
        (),
        s"slot ${header.slotInEpoch} outside a ${config.epochSlots}-slot epoch"
      )
      _ <- orderOk(tip, header)
      check <- if header.boundary then Right(ByronCheck.Boundary) else signature(header, config)
    yield ByronTip(header.hash, header.epoch, header.slot, header.boundary) -> check

  private def orderOk(tip: Option[ByronTip], h: Header.Byron): Either[String, Unit] =
    tip match
      case None =>
        Either.cond(
          h.boundary && h.epoch == 0,
          (),
          "the chain must start with the epoch 0 boundary"
        )
      case Some(t) if h.boundary =>
        Either.cond(
          h.epoch > t.epoch && !t.boundary,
          (),
          s"boundary block of epoch ${h.epoch} after epoch ${t.epoch}${
              if t.boundary then " boundary" else ""
            }"
        )
      case Some(t) =>
        Either.cond(
          h.epoch >= t.epoch && (h.slot > t.slot || (t.boundary && h.slot == t.slot)),
          (),
          s"slot ${h.slot} does not follow ${t.slot}"
        )

  private def signature(header: Header.Byron, config: Config): Either[String, ByronCheck] =
    header.main.toRight("main header without consensus data").flatMap { main =>
      val genesisEntry = config.genesis.delegations.find(_.issuer == main.leader)
      main.signature match
        case ByronSignature.Direct(_) =>
          Right(ByronCheck.Unchecked("direct leader signature (not used on this chain)"))
        case d: ByronSignature.Delegated =>
          for
            _ <- Either.cond(
              d.issuer == main.leader,
              (),
              "delegation issuer is not the slot leader key"
            )
            entry <- genesisEntry.toRight(
              s"slot leader ${main.leader.hex.take(16)} is not a genesis key"
            )
            onGenesis = entry.delegate == d.delegate && entry.cert == d.cert
            _ <- Either.cond(
              header.epoch >= d.epochs._1 && (d.kind == 2 || header.epoch <= d.epochs._2),
              (),
              s"delegation not valid in epoch ${header.epoch}"
            )
            verdict <-
              if !onGenesis then
                Right(ByronCheck.Unchecked("delegation differs from the genesis delegation"))
              else if d.kind != 2 then Right(ByronCheck.Unchecked("light delegation"))
              else
                val message = Bytes(
                  "01".getBytes("US-ASCII").toVector ++ d.issuer.value ++
                    Vector(SignMainBlockHeavy) ++ cborUInt(
                      header.protocolMagic
                    ) ++ main.signed.value
                )
                StrictEd25519.verify(Bytes(d.delegate.value.take(32)), d.signature, message) match
                  case Right(VerificationResult.SignatureVerified) => Right(ByronCheck.Verified)
                  case Right(VerificationResult.SignatureRejected) =>
                    Left("block signature rejected")
                  case Left(other) => Left(s"signature check failed: $other")
          yield verdict
    }
