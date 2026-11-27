// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Order
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellStateCodecs,
  NodeOrders,
  Params,
  UtxoCodecs,
  Versions,
  Wire
}

/** What the node's encoders of a refusal share: Conway's index in the hard-fork combinator's
  * `ApplyTxErr` and its era mismatch; the writers of the leaves of a failure (the Haskell state
  * codecs' values, the sets, maps, lists and maybes they nest in, a `Mismatch` in line); the
  * encodings the eras share (POOL's failures, UTXOS's `ValidationTagMismatch`, a purpose with its
  * item); and the voting procedures and pool relays of Conway's context errors and certificates.
  *
  * Conway's encoder of the era's failures ([[ConwayFailureCbor]]), the encoder of the eras before
  * Conway ([[PreConwayFailureCbor]]) and the context errors' ([[ContextFailureCbor]]) write with
  * these.
  */
object FailureCbor:
  private[ntc] type W = CborOut

  val ConwayIndex = 6

  /** The hard-fork-combinator era mismatch (`encodeEitherMismatch`, `Left`,
    * `HardFork/Combinator/Serialisation/Common.hs:570-582`, ouroboros-consensus 82ecba32): the era
    * of the transaction, then the ledger's, each its index and its name (`singleEraName`).
    */
  def eraMismatch(transactionEra: Int, transactionEraName: String, ledger: Era): Bytes =
    CborOut.build { w =>
      w.array(2)
      w.array(2).uint(transactionEra).text(transactionEraName)
      w.array(2).uint(ledger.index).text(ledger.toString)
    }

  // ---- helpers

  /** The ledger values of a failure are written by the Haskell state codecs, at the version the
    * reference writes the Conway era at; `W` is the failure's own writer.
    */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
  import NodeOrders.given

  private[ntc] def put[A](w: W, a: A)(using codec: Codec[A]): W = w.raw(codec.encodeUnchecked(a))

  /** The codec of an item written by a function of the failure's writer. */
  private[ntc] def element[A](each: (W, A) => Unit): Codec[A] =
    Wire.writeOnly("a failure item")((a, out) => out.raw(CborOut.build(each(_, a))))

  private[ntc] def list[A](w: W, xs: Iterable[A])(each: (W, A) => Unit): W =
    put(w, xs.toVector)(using Wire.seqOf(element(each)))

  private[ntc] def set[A: Order](w: W, xs: Iterable[A])(each: (W, A) => Unit)(using ProtVer): W =
    put(w, xs.toSet)(using Wire.sortedSetOf(element(each)))

  private[ntc] def map[K: Order, V](w: W, entries: Map[K, V])(
      key: (W, K) => Unit,
      value: (W, V) => Unit
  )(using ProtVer): W = put(w, entries)(using Wire.sortedMapOf(element(key), element(value)))

  private[ntc] def maybe[A](w: W, a: Option[A])(each: (W, A) => Unit): W =
    put(w, a)(using Wire.strictMaybe(element(each)))

  private[ntc] def credential(w: W, c: Credential): W =
    put(w, c)(using HaskellStateCodecs.nodeCredential)
  private[ntc] def txIn(w: W, i: TxIn): W = put(w, i)(using UtxoCodecs.txIn)
  private[ntc] def txOut(w: W, o: TxOut): W = put(w, o)(using UtxoCodecs.txOut)
  private[ntc] def rewardAccount(w: W, a: RewardAccount): W =
    put(w, a)(using HaskellStateCodecs.rewardAccount)
  private[ntc] def govActionId(w: W, i: GovActionId): W =
    put(w, i)(using HaskellStateCodecs.govActionId)
  private[ntc] def proposalProcedure(w: W, p: Proposal): W =
    put(w, p)(using HaskellStateCodecs.proposal)
  private[ntc] def exUnits(w: W, e: ExUnits): W = put(w, e)(using Params.exUnits)

  private[ntc] def hash(w: W, h: Bytes): Unit = { w.bytes(h); () }

  private[ntc] def hashSet(w: W, hs: Set[Bytes])(using ProtVer): W = set(w, hs)(hash)

  private[ntc] def maybeHash(w: W, h: Option[Bytes]): W = maybe(w, h)((k, x) => k.bytes(x))

  /** A mismatch as a group: supplied, then expected. */
  private[ntc] def group[A](w: W, m: Mismatch[A])(each: (W, A) => Unit): Unit =
    each(w, m.supplied); each(w, m.expected)

  /** A mismatch the ledger writes in the reverse order. */
  private[ntc] def swapped[A](w: W, m: Mismatch[A])(each: (W, A) => Unit): Unit =
    each(w, m.expected); each(w, m.supplied)

  private[ntc] def coin(w: W, c: BigInt): Unit = { w.uint(c); () }
  private[ntc] def small(w: W, n: Int): Unit = { w.uint(n); () }

  /** A Haskell `Int` as `encodeInt` writes it: signed, up to 64 bits, in the shortest head. */
  private[ntc] def int(w: W, n: Long): Unit = { w.int(n); () }

  private[ntc] def voter(w: W, v: Voter): W = v match
    case Voter.CommitteeHot(Credential.Key(h))    => w.array(2).uint(0).bytes(h)
    case Voter.CommitteeHot(Credential.Script(h)) => w.array(2).uint(1).bytes(h)
    case Voter.DRepVoter(Credential.Key(h))       => w.array(2).uint(2).bytes(h)
    case Voter.DRepVoter(Credential.Script(h))    => w.array(2).uint(3).bytes(h)
    case Voter.Pool(h)                            => w.array(2).uint(4).bytes(h)

  private[ntc] def protVer(w: W, v: (BigInt, BigInt)): Unit = {
    w.array(2).uint(v._1).uint(v._2); ()
  }

  /** A Plutus purpose as the ledger names it with the item it applies to (`PlutusPurpose AsItem`):
    * `[tag, item]`, for the pre-Conway purposes [[ContextFailureCbor.purpose]] writes. It writes a
    * certifying purpose's certificate itself, and Alonzo and Babbage have no voting or proposing
    * purpose (Conway's are written by [[ConwayFailureCbor]]), so no caller reaches those three arms
    * below; they are kept only because the match is total.
    */
  private[ntc] def item(w: W, p: Purpose): Unit = p match
    case Purpose.Spending(_, input, _)  => w.array(2).uint(0); txIn(w, input)
    case Purpose.Minting(_, policy)     => w.array(2).uint(1).bytes(policy)
    case Purpose.Certifying(_, _)       => w.array(2).uint(2); w.nul
    case Purpose.Rewarding(_, account)  => w.array(2).uint(3); rewardAccount(w, account)
    case Purpose.Voting(_, v)           => w.array(2).uint(4); voter(w, v)
    case Purpose.Proposing(_, proposal) => w.array(2).uint(5); proposalProcedure(w, proposal)

  private[ntc] def value(w: W, v: Value): Unit = { put(w, v)(using UtxoCodecs.value); () }

  // ---- shared encodings

  /** UTXOS's `ValidationTagMismatch` (`Alonzo/Rules/Utxos.hs:351-356`, cardano-ledger f649f975,
    * which Babbage's and Conway's UTXOS hold at the same tag): the flag, and the failures'
    * descriptions with their reconstructions ([[PlutusFailureCbor]]), each written once it is
    * described.
    */
  private[ntc] def validationTagMismatch(
      f: UtxosFailure.ValidationTagMismatch
  ): Either[RefusalPresentationError, W => Unit] =
    f.failures
      .traverse(_.traverse { failure =>
        PlutusFailureCbor
          .description(failure)
          .left
          .map(cause => RefusalPresentationError.InvalidPlutusContext(failure, cause))
          .map(description =>
            (
              description.getOrElse(failure.cause.message),
              PlutusFailureCbor.reconstruction(failure)
            )
          )
      })
      .map { descriptions => w =>
        w.array(3).uint(0).bool(f.isValid)
        descriptions match
          case None => w.array(1).uint(0)
          case Some(fs) =>
            w.array(2).uint(1)
            list(w, fs) { case (k, (description, reconstruction)) =>
              k.array(3).uint(1).text(description).bytes(reconstruction)
            }
        ()
      }

  /** POOL's failures (`Shelley/Rules/Pool.hs:153-170`, cardano-ledger f649f975), whose encoding
    * every era from Shelley to Conway shares.
    */
  private[ntc] def pool(w: W, f: PoolFailure): Unit = f match
    case PoolFailure.StakePoolNotRegisteredOnKey(p) => w.array(2).uint(0).bytes(p)
    case PoolFailure.StakePoolRetirementWrongEpoch(current, latest) =>
      w.array(4).uint(1).uint(current.expected).uint(latest.supplied).uint(latest.expected)
    case PoolFailure.StakePoolCostTooLow(m) => w.array(3).uint(3); group(w, m)(coin)
    case PoolFailure.WrongNetworkPool(m, p) =>
      w.array(4).uint(4); swapped(w, m)(small); w.bytes(p)
    case PoolFailure.PoolMedataHashTooBig(p, size) => w.array(3).uint(5).bytes(p).uint(size)
    case PoolFailure.VRFKeyHashAlreadyRegistered(p, vrf) =>
      w.array(3).uint(6).bytes(p).bytes(vrf)

  /** Conway's voting procedures in a context error (`VotingProceduresFieldNotSupported`): the map
    * of voters to the map of action ids to their procedures, sorted as the reference's maps.
    */
  private[ntc] def votingProcedures(
      procedures: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  )(using ProtVer): Bytes =
    CborOut.build { w =>
      val procedure = element[VotingProcedure] { (out, value) =>
        out.array(2)
        put(out, value.vote)(using HaskellStateCodecs.vote)
        put(out, value.anchor)(using Wire.nullable(HaskellStateCodecs.anchor)); ()
      }
      put(w, procedures.map { case (v, votes) => v -> votes.toMap }.toMap)(using
        Wire.sortedMapOf(
          element((out, v: Voter) => { voter(out, v); () }),
          Wire.sortedMapOf(HaskellStateCodecs.govActionId, procedure)
        )
      ); ()
    }

  /** A pool registration's relays (`StrictSeq StakePoolRelay`) as the reference writes them, or
    * none where the node's bytes do not read as relays.
    */
  private[ntc] def relayBytes(raw: Bytes): Option[Bytes] =
    PoolRelays.decode(raw).toOption.map { relays =>
      CborOut.build { w =>
        list(w, relays) { (out, relay) =>
          def optional[A](value: Option[A])(write: A => CborOut): Unit =
            value.fold({ out.nul; () })(item => { write(item); () })
          relay match
            case PoolRelay.SingleHostAddr(port, ipv4, ipv6) =>
              out.array(4).uint(0)
              optional(port)(p => out.uint(p.toInt))
              optional(ipv4)(a => out.bytes(a.octets))
              optional(ipv6)(a => out.bytes(a.wire))
            case PoolRelay.SingleHostName(port, name) =>
              out.array(3).uint(1); optional(port)(p => out.uint(p.toInt)); out.text(name); ()
            case PoolRelay.MultiHostName(name) => out.array(2).uint(2).text(name); ()
        }
      }
    }
