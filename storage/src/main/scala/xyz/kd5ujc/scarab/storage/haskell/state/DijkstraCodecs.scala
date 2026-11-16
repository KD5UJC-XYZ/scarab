// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Cbor
import xyz.kd5ujc.scarab.ledger.{LedgerDecodeError, Value}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer
}

import NodeOrders.given

/** Re-encodings of decoded Dijkstra wire values at version 12, as the Leios reference writes them
  * (cardano-ledger 9df9f98c). These are not persisted-state codecs. Memoized native scripts and
  * inline data retain their bytes; records, maps and output forms are rebuilt from values.
  */
object DijkstraCodecs:
  private given ProtVer = ProtVer(Word64.fromUnsigned(12), Word64.zero)
  private val state = HaskellStateCodecs

  /** The ledger owns decoding and its constraints. Checking a constructed value requires that the
    * encoding reads back as that value, so an unknown key, out-of-range scalar, invalid kept item
    * or unsupported field combination is refused before it can be written by `Codec.write`.
    */
  private def codec[A](shape: String)(read: Node => Either[LedgerDecodeError, A])(
      put: (A, Writer) => Unit
  )(same: (A, A) => Boolean): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] =
      read(n).left.map(e => DecodeError.at(n, DecodeError.Kind.Domain(e)))
    def encode(a: A, out: Writer): Unit = put(a, out)
    override def check(a: A): Option[ConstraintViolation] =
      decodeBytes(encodeUnchecked(a), Cbor.Limits.Unbounded) match
        case Left(e)  => Some(ConstraintViolation(shape, "a writable Dijkstra value", e.toString))
        case Right(b) => Codec.refuse(!same(a, b), shape, "an exact value round trip", a)

  private def alternative(out: Writer, size: Int, tag: Int): Unit =
    out.arrayHead(size)
    out.uint(tag.toLong)

  private def optional[A](value: Option[A], out: Writer)(put: (A, Writer) => Unit): Unit =
    value.fold(out.nul())(put(_, out))

  /** `EncCBOR StakePoolRelay`, `State/StakePool.hs:440-455`: definite tagged records, nullable
    * ports and IP bytes. The decoded IPv6 already holds the reference's four little-endian words.
    */
  val relay: Codec[DijkstraRelay] = codec("StakePoolRelay")(DijkstraRelay.decode) { (r, out) =>
    r match
      case DijkstraRelay.SingleHostAddr(port, ipv4, ipv6) =>
        alternative(out, 4, 0)
        optional(port, out)((p, w) => w.uint(p.toLong))
        optional(ipv4, out)((b, w) => w.bytes(b))
        optional(ipv6, out)((b, w) => w.bytes(b))
      case DijkstraRelay.SingleHostName(port, name) =>
        alternative(out, 3, 1)
        optional(port, out)((p, w) => w.uint(p.toLong))
        out.text(name)
      case DijkstraRelay.MultiHostName(name) =>
        alternative(out, 2, 2)
        out.text(name)
  }(_ == _)

  /** `encodeScript`, `Alonzo/Scripts.hs:723-726`, with Dijkstra's language tags 0..4 and the
    * memoized native-script payload (`Dijkstra/Scripts.hs:284-293`).
    */
  val script: Codec[DijkstraScript] = codec("AlonzoScript")(DijkstraScript.script) { (s, out) =>
    s match
      case DijkstraScript.Native(raw) =>
        alternative(out, 2, 0)
        out.raw(raw)
      case DijkstraScript.Plutus(language, code) =>
        alternative(out, 2, language.hashTag)
        out.bytes(code)
  }(_ == _)

  /** `EncCBOR (Datum era)`, `Plutus/Data.hs:292-300`: hash or nested, byte-preserved data. */
  val datum: Codec[DijkstraDatum] = codec("Datum")(DijkstraDatum.decode) { (d, out) =>
    d match
      case DijkstraDatum.NoDatum =>
        () // OmitC writes nothing; check refuses it as a standalone item
      case DijkstraDatum.Hash(hash) =>
        alternative(out, 2, 0)
        out.bytes(hash)
      case DijkstraDatum.Inline(raw) =>
        alternative(out, 2, 1)
        out.tag(24)
        out.bytes(raw)
  }(_ == _)

  /** `EncCBOR BabbageTxOut` and `encodeTxOut`, `Babbage/TxOut.hs:514-519,594-609`: legacy lists
    * without an inline datum or reference script, otherwise the sparse map, whose nested script is
    * re-encoded and whose inline data keeps its own bytes.
    */
  val txOut: Codec[DijkstraTxOut] = codec("BabbageTxOut")(DijkstraTxOut.decode) { (o, out) =>
    def value(): Unit = UtxoCodecs.value.encode(Value(o.value.coin, o.value.assets), out)
    (o.datum, o.referenceScript) match
      case (DijkstraDatum.NoDatum, None) =>
        out.arrayHead(2)
        out.bytes(o.address.raw)
        value()
      case (DijkstraDatum.Hash(hash), None) =>
        out.arrayHead(3)
        out.bytes(o.address.raw)
        value()
        out.bytes(hash)
      case (DijkstraDatum.Inline(_), None | Some(_)) | (DijkstraDatum.NoDatum, Some(_)) |
          (DijkstraDatum.Hash(_), Some(_)) =>
        val hasDatum = o.datum != DijkstraDatum.NoDatum
        out.mapHead(2 + (if hasDatum then 1 else 0) + o.referenceScript.size)
        out.uint(0L)
        out.bytes(o.address.raw)
        out.uint(1L)
        value()
        if hasDatum then
          out.uint(2L)
          datum.encode(o.datum, out)
        o.referenceScript.foreach { s =>
          out.uint(3L)
          out.tag(24)
          out.bytes(script.encodeUnchecked(s))
        }
  }((a, b) => a.copy(raw = b.raw) == b)

  /** `EncCBOR PParamsUpdate`, `Core/PParams.hs:250-264`, and Dijkstra's `eraPParams`: sparse
    * ascending update keys. The version-12 field decoders validate the key, value type and range.
    */
  val paramUpdate: Codec[DijkstraParamUpdate] =
    codec("PParamsUpdate")(DijkstraParamUpdate.decode) { (u, out) =>
      out.mapHead(u.fields.size)
      u.fields.toVector.sortBy(_._1).foreach { (key, value) =>
        out.uint(key.toLong)
        paramValue(value, out)
      }
    }(_ == _)

  private def paramValue(value: DijkstraParamValue, out: Writer): Unit =
    import DijkstraParamValue.*
    value match
      case Number(n)         => out.uint(n)
      case Ratio(r)          => Wire.nonNegativeInterval.encode(r, out)
      case OptionalRatio(r)  => optional(r, out)(Wire.nonNegativeInterval.encode)
      case OptionalNumber(n) => optional(n, out)((v, w) => w.uint(v))
      case CostModels(models) =>
        out.variableMap(models.size) {
          models.toVector.sortBy(_._1).foreach { (language, parameters) =>
            out.uint(language.toLong)
            out.variableArray(parameters.size)(parameters.foreach(n => out.int(BigInt(n))))
          }
        }
      case Pair(memory, steps) =>
        out.arrayHead(2)
        Wire.nonNegativeInterval.encode(memory, out)
        Wire.nonNegativeInterval.encode(steps, out)
      case ExUnits(memory, steps) =>
        out.arrayHead(2)
        out.uint(memory)
        out.uint(steps)
      case Thresholds(values) =>
        out.arrayHead(values.size)
        values.foreach(Wire.unitInterval.encode(_, out))

  /** `EncCBOR AccountBalanceInterval`, `Dijkstra/Scripts.hs:677-682`: a bare exact coin or a pair
    * of nullable bounds. The decoder refuses a pair with neither bound.
    */
  val balanceInterval: Codec[DijkstraBalanceInterval] =
    codec("AccountBalanceInterval")(DijkstraTx.balanceInterval) { (interval, out) =>
      interval match
        case DijkstraBalanceInterval.Exact(coin) => out.uint(coin)
        case DijkstraBalanceInterval.Lower(lower) =>
          out.arrayHead(2)
          out.uint(lower)
          out.nul()
        case DijkstraBalanceInterval.Upper(upper) =>
          out.arrayHead(2)
          out.nul()
          out.uint(upper)
        case DijkstraBalanceInterval.Both(lower, upper) =>
          out.arrayHead(2)
          out.uint(lower)
          out.uint(upper)
    }(_ == _)

  private def anchor(a: DijkstraAnchor, out: Writer): Unit =
    out.arrayHead(2)
    out.text(a.url)
    out.bytes(a.hash)

  private def actionId(id: DijkstraGovActionId, out: Writer): Unit =
    out.arrayHead(2)
    out.bytes(id.txId)
    out.uint(id.index.toLong)

  /** `EncCBOR GovAction`, `Conway/Governance/Procedures.hs`, and `PParamsUpdate` above. Sets and
    * maps use the reference's value order, including Script credentials before Key credentials.
    */
  private def action(a: DijkstraGovAction, out: Writer): Unit =
    import DijkstraGovAction.*
    a match
      case ParameterChange(previous, update, policy) =>
        alternative(out, 4, 0)
        optional(previous, out)(actionId)
        paramUpdate.encode(update, out)
        optional(policy, out)((p, w) => w.bytes(p))
      case HardForkInitiation(previous, major, minor) =>
        alternative(out, 3, 1)
        optional(previous, out)(actionId)
        out.arrayHead(2)
        out.uint(major)
        out.uint(minor)
      case TreasuryWithdrawals(withdrawals, policy) =>
        alternative(out, 3, 2)
        Wire.sortedMapOf(state.rewardAccount, Wire.word(64)).encode(withdrawals, out)
        optional(policy, out)((p, w) => w.bytes(p))
      case NoConfidence(previous) =>
        alternative(out, 2, 3)
        optional(previous, out)(actionId)
      case UpdateCommittee(previous, removed, added, threshold) =>
        alternative(out, 5, 4)
        optional(previous, out)(actionId)
        Wire.sortedSetOf(state.nodeCredential).encode(removed, out)
        Wire.sortedMapOf(state.nodeCredential, Wire.word(64)).encode(added, out)
        Wire.unitInterval.encode(threshold, out)
      case NewConstitution(previous, a, policy) =>
        alternative(out, 3, 5)
        optional(previous, out)(actionId)
        out.arrayHead(2)
        anchor(a, out)
        optional(policy, out)((p, w) => w.bytes(p))
      case InfoAction => alternative(out, 1, 6)

  /** `EncCBOR ProposalProcedure`, `Procedures.hs:582-589`: deposit, account, action and anchor. */
  val proposal: Codec[DijkstraProposal] = codec("ProposalProcedure")(DijkstraProposal.decode) {
    (p, out) =>
      out.arrayHead(4)
      out.uint(p.deposit)
      state.rewardAccount.encode(p.returnAccount, out)
      action(p.action, out)
      anchor(p.anchor, out)
  }(_ == _)

  /** `EncCBOR DijkstraTxCert`, `Dijkstra/TxCert.hs:98-140,255-259`: delegation, pool and Conway
    * governance certificates. Pool fields follow `withStakePoolParamsFlatEncoding`,
    * `State/StakePool.hs:716-739`; the BLS key slot is omitted when there is no key, even if the
    * decoder read an explicit null slot.
    */
  val certificate: Codec[DijkstraCert] = codec("DijkstraTxCert")(DijkstraCert.decode) { (c, out) =>
    import DijkstraCert.*
    def cred(c: xyz.kd5ujc.scarab.ledger.Credential): Unit = state.nodeCredential.encode(c, out)
    def delegatee(d: DijkstraDelegatee): Unit = d match
      case DijkstraDelegatee.Stake(pool) => out.bytes(pool)
      case DijkstraDelegatee.Vote(drep)  => state.nodeDRep.encode(drep, out)
      case DijkstraDelegatee.StakeVote(pool, drep) =>
        out.bytes(pool)
        state.nodeDRep.encode(drep, out)
    c match
      case Register(c, deposit) =>
        alternative(out, 3, 7); cred(c); out.uint(deposit)
      case Unregister(c, refund) =>
        alternative(out, 3, 8); cred(c); out.uint(refund)
      case Delegate(c, d) =>
        val (size, tag) = d match
          case DijkstraDelegatee.Stake(_)        => (3, 2)
          case DijkstraDelegatee.Vote(_)         => (3, 9)
          case DijkstraDelegatee.StakeVote(_, _) => (4, 10)
        alternative(out, size, tag); cred(c); delegatee(d)
      case RegisterDelegate(c, d, deposit) =>
        val (size, tag) = d match
          case DijkstraDelegatee.Stake(_)        => (4, 11)
          case DijkstraDelegatee.Vote(_)         => (4, 12)
          case DijkstraDelegatee.StakeVote(_, _) => (5, 13)
        alternative(out, size, tag); cred(c); delegatee(d); out.uint(deposit)
      case RegisterPool(p) =>
        alternative(out, 10 + p.blsKey.size, 3)
        out.bytes(p.operator)
        out.bytes(p.vrf)
        p.blsKey.foreach { key =>
          out.arrayHead(2)
          out.bytes(key.key)
          out.bytes(key.proofOfPossession)
        }
        out.uint(p.pledge)
        out.uint(p.cost)
        Wire.unitInterval.encode(p.margin, out)
        state.rewardAccount.encode(p.account, out)
        Wire.sortedSetOf(Wire.hash(28)).encode(p.owners, out)
        out.variableArray(p.relays.size)(p.relays.foreach(relay.encode(_, out)))
        optional(p.metadata, out) { (m, w) =>
          w.arrayHead(2)
          w.text(m.url)
          w.bytes(m.hash)
        }
      case RetirePool(pool, epoch) =>
        alternative(out, 3, 4); out.bytes(pool); out.uint(epoch)
      case AuthorizeCommitteeHotKey(cold, hot) =>
        alternative(out, 3, 14); cred(cold); cred(hot)
      case ResignCommitteeColdKey(cold, a) =>
        alternative(out, 3, 15); cred(cold); optional(a, out)(anchor)
      case RegisterDRep(c, deposit, a) =>
        alternative(out, 4, 16); cred(c); out.uint(deposit); optional(a, out)(anchor)
      case UnregisterDRep(c, refund) =>
        alternative(out, 3, 17); cred(c); out.uint(refund)
      case UpdateDRep(c, a) =>
        alternative(out, 3, 18); cred(c); optional(a, out)(anchor)
  }(_ == _)

  /** `EncCBOR OSet`, `Data/OSet/Strict.hs:112-113`: tag 258 and insertion order, with the
    * reference's sequence framing. Decoding enforces uniqueness by the decoded value.
    */
  val certificates: Codec[Vector[DijkstraCert]] = codec("OSet TxCert")(DijkstraCert.set) {
    (cs, out) =>
      out.tag(258)
      out.variableArray(cs.size)(cs.foreach(certificate.encode(_, out)))
  }(_ == _)

  val proposals: Codec[Vector[DijkstraProposal]] =
    codec("OSet ProposalProcedure")(DijkstraProposal.set) { (ps, out) =>
      out.tag(258)
      out.variableArray(ps.size)(ps.foreach(proposal.encode(_, out)))
    }(_ == _)
