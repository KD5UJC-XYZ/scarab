// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Credential as FlatCredential,
  DataSite,
  Ptr,
  RewardAccount,
  TxIn
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Credential, EpochNo, KeyHash, KeyRole, ScriptHash}
import xyz.kd5ujc.scarab.plutus.PlutusData

/** The translations of the core's values to the script context's
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/TxInfo.hs`, cardano-ledger f649f975). A
  * credential, a key hash or a script hash arrives either as the era types' typed value or as the
  * bytes an address, an account or a voter holds; each has its translation.
  */
object PlutusTxInfos:
  /** `transKeyHash` (`Plutus/TxInfo.hs:124-125`, cardano-ledger f649f975). */
  def transKeyHash[R <: KeyRole](h: KeyHash[R]): PV1.PubKeyHash = PV1.PubKeyHash(h.bytes)

  /** `transKeyHash` of a key hash held as its bytes. */
  def transKeyHashBytes(h: Bytes): PV1.PubKeyHash = PV1.PubKeyHash(h)

  /** `transScriptHash` (`Plutus/TxInfo.hs:130-131`, cardano-ledger f649f975). */
  def transScriptHash(h: ScriptHash): PV1.ScriptHash = PV1.ScriptHash(h.bytes)

  /** `transSafeHash` (`Plutus/TxInfo.hs:127-128`, cardano-ledger f649f975): the hash's bytes. */
  def transSafeHash(h: Bytes): Bytes = h

  /** `transDataHash` (`Plutus/TxInfo.hs:121-122`, cardano-ledger f649f975). */
  def transDataHash(h: Bytes): PV1.DatumHash = PV1.DatumHash(transSafeHash(h))

  /** `transCred` (`Plutus/TxInfo.hs:139-143`, cardano-ledger f649f975). */
  def transCred[R <: KeyRole](c: Credential[R]): PV1.Credential = c match
    case Credential.KeyHashObj(h) => PV1.Credential.PubKeyCredential(transKeyHash(h))
    case Credential.ScriptHashObj(h) =>
      PV1.Credential.ScriptCredential(transScriptHash(h))

  /** `transCred` of a credential an address, an account or a voter holds. */
  def transCredFlat(c: FlatCredential): PV1.Credential = c match
    case FlatCredential.Key(h)    => PV1.Credential.PubKeyCredential(PV1.PubKeyHash(h))
    case FlatCredential.Script(h) => PV1.Credential.ScriptCredential(PV1.ScriptHash(h))

  /** `transStakeReference` (`Plutus/TxInfo.hs:133-137`, cardano-ledger f649f975): a base address's
    * credential, a pointer address's pointer, or none.
    */
  def transStakeReference(
      stake: Option[FlatCredential],
      pointer: Option[Ptr]
  ): Option[PV1.StakingCredential] =
    stake
      .map(c => PV1.StakingCredential.StakingHash(transCredFlat(c)))
      .orElse(pointer.map(p => PV1.StakingCredential.StakingPtr(p.slot, p.txIx, p.certIx)))

  /** `transAddr` (`Plutus/TxInfo.hs:147-153`, cardano-ledger f649f975): none for a Byron address.
    */
  def transAddr(a: Address): Option[PV1.Address] = a.kind match
    case Address.Kind.Byron(_) => None
    case Address.Kind.Shelley(_, payment, stake, pointer) =>
      Some(PV1.Address(transCredFlat(payment), transStakeReference(stake, pointer)))

  /** `transAccountAddress` (`Plutus/TxInfo.hs:157-158`, cardano-ledger f649f975): the account's
    * credential, its network dropped.
    */
  def transAccountAddress(a: RewardAccount): PV1.Credential = transCredFlat(a.credential)

  /** `transTxId` (`Plutus/TxInfo.hs:176-177`, cardano-ledger f649f975). */
  def transTxId(id: Bytes): PV1.TxId = PV1.TxId(transSafeHash(id))

  /** `transTxIn` (`Plutus/TxInfo.hs:179-180`, cardano-ledger f649f975). */
  def transTxIn(in: TxIn): PV1.TxOutRef = PV1.TxOutRef(transTxId(in.id), in.index)

  /** `transCoinToValue` (`Plutus/TxInfo.hs:182-183`, cardano-ledger f649f975). */
  def transCoinToValue(c: Coin): PV1.Value =
    PV1.Value.singleton(PV1.adaSymbol, PV1.adaToken, c.value)

  /** `transDataPair` (`Plutus/TxInfo.hs:185-186`, cardano-ledger f649f975). */
  def transDataPair(hash: Bytes, data: PlutusData): (PV1.DatumHash, PV1.Datum) =
    (transDataHash(hash), PV1.Datum(data))

  /** `transCoinToLovelace` (`Plutus/TxInfo.hs:203-204`, cardano-ledger f649f975). */
  def transCoinToLovelace(c: Coin): PV1.Lovelace = PV1.Lovelace(c.value)

  /** `transEpochNo` (`Plutus/TxInfo.hs:206-207`, cardano-ledger f649f975). */
  def transEpochNo(e: EpochNo): BigInt = e.toBigInt

  /** `transBoundedRational` (`Plutus/TxInfo.hs:118-119`, cardano-ledger f649f975): the reduced
    * ratio.
    */
  def transBoundedRational(r: UnitInterval): PV3.Rational =
    PV3.Rational(r.rational.numerator, r.rational.denominator)

  /** `transDatum` (`Plutus/TxInfo.hs:212-213`, cardano-ledger f649f975). */
  def transDatum(data: PlutusData): PV1.Datum = PV1.Datum(data)

  /** An output's inline datum read from the bytes it arrived as, where the reference's
    * `binaryDataToData` takes its `BinaryData era` as data: the node's own, failing at `site`.
    */
  def decodeData(raw: Bytes, site: DataSite): Either[DataSite, PlutusData] =
    PlutusData.decode(raw).left.map(_ => site)
