// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.{B, I}

// The types of the script context that the ledger translates a transaction to, as plutus-ledger-api
// defines them (plutus 507cab8c, `plutus-ledger-api/src/PlutusLedgerApi/V1`, `V2` and `V3`), with
// their `ToData` encodings (`makeIsDataIndexed` and `makeIsDataSchemaIndexed`: a constructor is
// `Constr` of its index with its fields in order; a newtype derives its field's).

/** `class ToData a` (plutus 507cab8c `plutus-tx/src/PlutusTx/IsData/Class.hs:43-45`): a value as
  * Plutus data.
  */
trait ToData[A]:
  def toBuiltinData(a: A): PlutusData

object ToData:
  def apply[A](using t: ToData[A]): ToData[A] = t

  extension [A](a: A)(using t: ToData[A]) def toData: PlutusData = t.toBuiltinData(a)

  /** `instance ToData BuiltinData` (`PlutusTx/IsData/Class.hs:63-65`, plutus 507cab8c). */
  given builtinData: ToData[PlutusData] = d => d

  /** `instance ToData Integer` (`PlutusTx/IsData/Class.hs:89-91`, plutus 507cab8c). */
  given integer: ToData[BigInt] = I(_)

  /** `instance ToData BuiltinByteString` (`PlutusTx/IsData/Class.hs:100-102`, plutus 507cab8c). */
  given builtinByteString: ToData[Bytes] = B(_)

  /** `instance ToData [a]` (`PlutusTx/IsData/Class.hs:111-120`, plutus 507cab8c): a list. */
  given list[A](using t: ToData[A]): ToData[Vector[A]] = xs =>
    PlutusData.List(xs.map(t.toBuiltinData))

  /** `Bool`'s (`PlutusTx/IsData/Instances.hs:23`, plutus 507cab8c): `False` 0, `True` 1. */
  given bool: ToData[Boolean] = PlutusData.bool(_)

  /** `Maybe`'s (`PlutusTx/IsData/Instances.hs:24`, plutus 507cab8c): `Just` 0, `Nothing` 1. */
  given maybe[A](using t: ToData[A]): ToData[Option[A]] = m =>
    PlutusData.maybe(m.map(t.toBuiltinData))

  /** The pair's (`PlutusTx/IsData/Instances.hs:30`, plutus 507cab8c): constructor 0 of both. */
  given pair[A, C](using a: ToData[A], c: ToData[C]): ToData[(A, C)] = (x, y) =>
    PlutusData.constr(0, a.toBuiltinData(x), c.toBuiltinData(y))

/** `newtype Map k v = Map [(k, v)]` (plutus 507cab8c `plutus-tx/src/PlutusTx/AssocMap.hs:87-89`):
  * an association list, its order and any repeated key kept as built (`unsafeFromList`).
  */
final case class AssocMap[K, V](toList: Vector[(K, V)])

object AssocMap:
  /** `unionWith` (`PlutusTx/AssocMap.hs:313-328`, plutus 507cab8c): the left's entries in order,
    * each merged with the right's value of its key, then the right's entries of the keys the left
    * lacks.
    */
  def unionWith[K, V](merge: (V, V) => V)(ls: AssocMap[K, V], rs: AssocMap[K, V]): AssocMap[K, V] =
    def lookup(k: K): Option[V] = rs.toList.collectFirst { case (c, v) if c == k => v }
    val left = ls.toList.map((c, i) => (c, lookup(c).fold(i)(merge(i, _))))
    val right = rs.toList.filterNot((c, _) => ls.toList.exists(_._1 == c))
    AssocMap(left ++ right)

  /** `instance ToData (Map k v)` (`PlutusTx/AssocMap.hs:101-110`, plutus 507cab8c): a map of the
    * entries in order.
    */
  given toData[K, V](using k: ToData[K], v: ToData[V]): ToData[AssocMap[K, V]] = m =>
    PlutusData.Map(m.toList.map((a, b) => (k.toBuiltinData(a), v.toBuiltinData(b))))

/** The types of `PlutusLedgerApi.V1` (plutus 507cab8c), and those V2 and V3 take from it. */
object PV1:
  import ToData.toData

  /** `newtype PubKeyHash` (`V1/Crypto.hs:36-39`, plutus 507cab8c), its bytes'. */
  final case class PubKeyHash(getPubKeyHash: Bytes)

  /** `newtype ScriptHash` (`V1/Scripts.hs:96-107`, plutus 507cab8c), its bytes'. */
  final case class ScriptHash(getScriptHash: Bytes)

  /** `newtype DatumHash` (`V1/Scripts.hs:118-129`, plutus 507cab8c), its bytes'. */
  final case class DatumHash(bytes: Bytes)

  /** `newtype Datum` (`V1/Scripts.hs:64-66`, plutus 507cab8c), its data. */
  final case class Datum(getDatum: PlutusData)

  /** `newtype Redeemer` (`V1/Scripts.hs:78-80`, plutus 507cab8c), its data. */
  final case class Redeemer(getRedeemer: PlutusData)

  /** `newtype CurrencySymbol` (`V1/Value.hs:114-117`, plutus 507cab8c), its bytes'. */
  final case class CurrencySymbol(unCurrencySymbol: Bytes)

  /** `newtype TokenName` (`V1/Value.hs:154-156`, plutus 507cab8c), its bytes'. */
  final case class TokenName(unTokenName: Bytes)

  /** `newtype Lovelace` (`V1/Value.hs:621-625`, plutus 507cab8c), its integer's. */
  final case class Lovelace(getLovelace: BigInt)

  /** `newtype POSIXTime` (`V1/Time.hs:62-65`, plutus 507cab8c), its integer's. */
  final case class POSIXTime(getPOSIXTime: BigInt)

  /** `data Credential` (`V1/Credential.hs:30-37,75`, plutus 507cab8c). */
  enum Credential:
    case PubKeyCredential(hash: PubKeyHash)
    case ScriptCredential(hash: ScriptHash)

  /** `data StakingCredential` (`V1/Credential.hs:43-54,76`, plutus 507cab8c). */
  enum StakingCredential:
    case StakingHash(credential: Credential)
    case StakingPtr(slot: BigInt, txIx: BigInt, certIx: BigInt)

  /** `data Address` (`V1/Address.hs:34-38,87`, plutus 507cab8c). */
  final case class Address(
      addressCredential: Credential,
      addressStakingCredential: Option[StakingCredential]
  )

  /** `newtype TxId` of V1 and V2 (`V1/Tx.hs:62-65,169`, plutus 507cab8c): constructor 0 of the
    * bytes.
    */
  final case class TxId(getTxId: Bytes)

  /** `data TxOutRef` of V1 and V2 (`V1/Tx.hs:95-99,171`, plutus 507cab8c). */
  final case class TxOutRef(txOutRefId: TxId, txOutRefIdx: BigInt)

  /** `newtype Value` (`V1/Value.hs:275-278`, plutus 507cab8c): per currency, per token, the
    * quantity, its map's.
    */
  final case class Value(getValue: AssocMap[CurrencySymbol, AssocMap[TokenName, BigInt]]):
    /** `(<>) = unionWith (+)` (`V1/Value.hs:316,434-444`, plutus 507cab8c): both maps' entries, a
      * key the two share added up.
      */
    def <>(that: Value): Value =
      Value(
        AssocMap.unionWith[CurrencySymbol, AssocMap[TokenName, BigInt]]((a, b) =>
          AssocMap.unionWith[TokenName, BigInt](_ + _)(a, b)
        )(getValue, that.getValue)
      )

  object Value:
    /** `singleton` (`V1/Value.hs:386-387`, plutus 507cab8c). */
    def singleton(c: CurrencySymbol, tn: TokenName, i: BigInt): Value =
      Value(AssocMap(Vector(c -> AssocMap(Vector(tn -> i)))))

  /** `adaSymbol` and `adaToken` (`V1/Value.hs:197-203`, plutus 507cab8c): the empty bytes. */
  val adaSymbol: CurrencySymbol = CurrencySymbol(Bytes.empty)
  val adaToken: TokenName = TokenName(Bytes.empty)

  /** `data Extended a` (`V1/Interval.hs:117,460`, plutus 507cab8c). */
  enum Extended[+A]:
    case NegInf
    case Finite(a: A)
    case PosInf

  /** `data LowerBound a` (`V1/Interval.hs:192,462`, plutus 507cab8c): the bound and whether it is
    * closed.
    */
  final case class LowerBound[A](bound: Extended[A], closed: Boolean)

  /** `data UpperBound a` (`V1/Interval.hs:140,461`, plutus 507cab8c). */
  final case class UpperBound[A](bound: Extended[A], closed: Boolean)

  /** `data Interval a` (`V1/Interval.hs:82,463`, plutus 507cab8c). */
  final case class Interval[A](ivFrom: LowerBound[A], ivTo: UpperBound[A])

  /** `type POSIXTimeRange = Interval POSIXTime` (`V1/Time.hs:88`, plutus 507cab8c). */
  type POSIXTimeRange = Interval[POSIXTime]

  object Interval:
    /** `always` (`V1/Interval.hs:366-367`, plutus 507cab8c). */
    def always[A]: Interval[A] =
      Interval(LowerBound(Extended.NegInf, true), UpperBound(Extended.PosInf, true))

    /** `from` (`V1/Interval.hs:355-356`, plutus 507cab8c). */
    def from[A](s: A): Interval[A] = Interval(lowerBound(s), UpperBound(Extended.PosInf, true))

    /** `to` (`V1/Interval.hs:361-362`, plutus 261766ad): up to `s`, closed. */
    def to[A](s: A): Interval[A] = Interval(LowerBound(Extended.NegInf, true), upperBound(s))

  /** `lowerBound` (`V1/Interval.hs:298-299`, plutus 507cab8c): closed. */
  def lowerBound[A](a: A): LowerBound[A] = LowerBound(Extended.Finite(a), true)

  /** `upperBound` (`V1/Interval.hs:304-305`, plutus 261766ad): closed. */
  def upperBound[A](a: A): UpperBound[A] = UpperBound(Extended.Finite(a), true)

  /** `strictUpperBound` (`V1/Interval.hs:286-287`, plutus 507cab8c): open. */
  def strictUpperBound[A](a: A): UpperBound[A] = UpperBound(Extended.Finite(a), false)

  given pubKeyHash: ToData[PubKeyHash] = h => B(h.getPubKeyHash)
  given scriptHash: ToData[ScriptHash] = h => B(h.getScriptHash)
  given datumHash: ToData[DatumHash] = h => B(h.bytes)
  given datum: ToData[Datum] = _.getDatum
  given redeemer: ToData[Redeemer] = _.getRedeemer
  given currencySymbol: ToData[CurrencySymbol] = c => B(c.unCurrencySymbol)
  given tokenName: ToData[TokenName] = t => B(t.unTokenName)
  given lovelace: ToData[Lovelace] = l => I(l.getLovelace)
  given posixTime: ToData[POSIXTime] = t => I(t.getPOSIXTime)

  given credential: ToData[Credential] =
    case Credential.PubKeyCredential(h) => PlutusData.constr(0, h.toData)
    case Credential.ScriptCredential(h) => PlutusData.constr(1, h.toData)

  given stakingCredential: ToData[StakingCredential] =
    case StakingCredential.StakingHash(c) => PlutusData.constr(0, c.toData)
    case StakingCredential.StakingPtr(a, b, c) =>
      PlutusData.constr(1, a.toData, b.toData, c.toData)

  given address: ToData[Address] = a =>
    PlutusData.constr(0, a.addressCredential.toData, a.addressStakingCredential.toData)

  given txId: ToData[TxId] = t => PlutusData.constr(0, B(t.getTxId))

  given txOutRef: ToData[TxOutRef] = r =>
    PlutusData.constr(0, r.txOutRefId.toData, r.txOutRefIdx.toData)

  given value: ToData[Value] = _.getValue.toData

  given extended[A](using a: ToData[A]): ToData[Extended[A]] =
    case Extended.NegInf    => PlutusData.constr(0)
    case Extended.Finite(x) => PlutusData.constr(1, a.toBuiltinData(x))
    case Extended.PosInf    => PlutusData.constr(2)

  given lowerBoundData[A](using ToData[A]): ToData[LowerBound[A]] = b =>
    PlutusData.constr(0, b.bound.toData, b.closed.toData)

  given upperBoundData[A](using ToData[A]): ToData[UpperBound[A]] = b =>
    PlutusData.constr(0, b.bound.toData, b.closed.toData)

  given interval[A](using ToData[A]): ToData[Interval[A]] = i =>
    PlutusData.constr(0, i.ivFrom.toData, i.ivTo.toData)

  /** The derived `Ord StakingCredential` (`V1/Credential.hs:43-54`, plutus 507cab8c): a hash before
    * a pointer; a key's credential before a script's (`V1/Credential.hs:30-37`), each by its bytes
    * (`deriving stock Ord` of the hashes, `V1/Crypto.hs:36-37`, `V1/Scripts.hs:96-107`); a pointer
    * by its three integers.
    */
  given stakingCredentialOrdering: Ordering[StakingCredential] =
    def credential(c: Credential): (Int, Bytes) = c match
      case Credential.PubKeyCredential(h) => (0, h.getPubKeyHash)
      case Credential.ScriptCredential(h) => (1, h.getScriptHash)
    (a, b) =>
      (a, b) match
        case (StakingCredential.StakingHash(x), StakingCredential.StakingHash(y)) =>
          summon[Ordering[(Int, Bytes)]].compare(credential(x), credential(y))
        case (StakingCredential.StakingHash(_), StakingCredential.StakingPtr(_, _, _)) => -1
        case (StakingCredential.StakingPtr(_, _, _), StakingCredential.StakingHash(_)) => 1
        case (StakingCredential.StakingPtr(a1, a2, a3), StakingCredential.StakingPtr(b1, b2, b3)) =>
          summon[Ordering[(BigInt, BigInt, BigInt)]].compare((a1, a2, a3), (b1, b2, b3))

  /** `data TxOut` of V1 (`V1/Tx.hs:111-115,170`, plutus 507cab8c): no inline datum and no reference
    * script.
    */
  final case class TxOut(
      txOutAddress: Address,
      txOutValue: Value,
      txOutDatumHash: Option[DatumHash]
  )

  /** `data DCert` (`V1/DCert.hs:53-73,83-93`, plutus 507cab8c), the certificate of V1 and V2. */
  enum DCert:
    case DCertDelegRegKey(credential: StakingCredential)
    case DCertDelegDeRegKey(credential: StakingCredential)
    case DCertDelegDelegate(delegator: StakingCredential, delegatee: PubKeyHash)
    case DCertPoolRegister(poolId: PubKeyHash, poolVfr: PubKeyHash)
    case DCertPoolRetire(poolId: PubKeyHash, epoch: BigInt)
    case DCertGenesis
    case DCertMir

  /** `data ScriptPurpose` of V1 and V2 (`V1/Contexts.hs:88-94,306-313`, plutus 507cab8c). */
  enum ScriptPurpose:
    case Minting(currencySymbol: CurrencySymbol)
    case Spending(txOutRef: TxOutRef)
    case Rewarding(credential: StakingCredential)
    case Certifying(dCert: DCert)

  /** `data TxInInfo` of V1 (`V1/Contexts.hs:74-77,304`, plutus 507cab8c). */
  final case class TxInInfo(txInInfoOutRef: TxOutRef, txInInfoResolved: TxOut)

  /** `data TxInfo` of V1 (`V1/Contexts.hs:100-121,305`, plutus 507cab8c). */
  final case class TxInfo(
      txInfoInputs: Vector[TxInInfo],
      txInfoOutputs: Vector[TxOut],
      txInfoFee: Value,
      txInfoMint: Value,
      txInfoDCert: Vector[DCert],
      txInfoWdrl: Vector[(StakingCredential, BigInt)],
      txInfoValidRange: POSIXTimeRange,
      txInfoSignatories: Vector[PubKeyHash],
      txInfoData: Vector[(DatumHash, Datum)],
      txInfoId: TxId
  ):
    /** The info as data (`toData`), made once however many of its transaction's scripts read it in
      * their contexts: each script's context holds the transaction's one info (#85).
      */
    lazy val data: PlutusData = txInfo.toBuiltinData(this)

  /** `data ScriptContext` of V1 (`V1/Contexts.hs:154-158,314`, plutus 507cab8c). */
  final case class ScriptContext(scriptContextTxInfo: TxInfo, scriptContextPurpose: ScriptPurpose)

  given txOut: ToData[TxOut] = o =>
    PlutusData.constr(0, o.txOutAddress.toData, o.txOutValue.toData, o.txOutDatumHash.toData)

  given dCert: ToData[DCert] =
    case DCert.DCertDelegRegKey(c)      => PlutusData.constr(0, c.toData)
    case DCert.DCertDelegDeRegKey(c)    => PlutusData.constr(1, c.toData)
    case DCert.DCertDelegDelegate(c, p) => PlutusData.constr(2, c.toData, p.toData)
    case DCert.DCertPoolRegister(p, v)  => PlutusData.constr(3, p.toData, v.toData)
    case DCert.DCertPoolRetire(p, e)    => PlutusData.constr(4, p.toData, e.toData)
    case DCert.DCertGenesis             => PlutusData.constr(5)
    case DCert.DCertMir                 => PlutusData.constr(6)

  given scriptPurpose: ToData[ScriptPurpose] =
    case ScriptPurpose.Minting(c)    => PlutusData.constr(0, c.toData)
    case ScriptPurpose.Spending(r)   => PlutusData.constr(1, r.toData)
    case ScriptPurpose.Rewarding(c)  => PlutusData.constr(2, c.toData)
    case ScriptPurpose.Certifying(d) => PlutusData.constr(3, d.toData)

  given txInInfo: ToData[TxInInfo] = i =>
    PlutusData.constr(0, i.txInInfoOutRef.toData, i.txInInfoResolved.toData)

  given txInfo: ToData[TxInfo] = t =>
    PlutusData.constr(
      0,
      t.txInfoInputs.toData,
      t.txInfoOutputs.toData,
      t.txInfoFee.toData,
      t.txInfoMint.toData,
      t.txInfoDCert.toData,
      t.txInfoWdrl.toData,
      t.txInfoValidRange.toData,
      t.txInfoSignatories.toData,
      t.txInfoData.toData,
      t.txInfoId.toData
    )

  given scriptContext: ToData[ScriptContext] = c =>
    PlutusData.constr(0, c.scriptContextTxInfo.data, c.scriptContextPurpose.toData)

/** The types of `PlutusLedgerApi.V2` (plutus 507cab8c) that V1 lacks. */
object PV2:
  import ToData.toData
  import PV1.given

  /** `data OutputDatum` (`V2/Tx.hs:66,155-161`, plutus 507cab8c). */
  enum OutputDatum:
    case NoOutputDatum
    case OutputDatumHash(hash: PV1.DatumHash)
    case OutputDatum(datum: PV1.Datum)

  /** `data TxOut` of V2 and V3 (`V2/Tx.hs:79-85,162`, plutus 507cab8c). */
  final case class TxOut(
      txOutAddress: PV1.Address,
      txOutValue: PV1.Value,
      txOutDatum: OutputDatum,
      txOutReferenceScript: Option[PV1.ScriptHash]
  )

  given outputDatum: ToData[OutputDatum] =
    case OutputDatum.NoOutputDatum      => PlutusData.constr(0)
    case OutputDatum.OutputDatumHash(h) => PlutusData.constr(1, h.toData)
    case OutputDatum.OutputDatum(d)     => PlutusData.constr(2, d.toData)

  given txOut: ToData[TxOut] = o =>
    PlutusData.constr(
      0,
      o.txOutAddress.toData,
      o.txOutValue.toData,
      o.txOutDatum.toData,
      o.txOutReferenceScript.toData
    )

  /** `data TxInInfo` of V2 (`V2/Contexts.hs:66-69,279`, plutus 507cab8c). */
  final case class TxInInfo(txInInfoOutRef: PV1.TxOutRef, txInInfoResolved: TxOut)

  /** `data TxInfo` of V2 (`V2/Contexts.hs:80-107,280`, plutus 507cab8c). */
  final case class TxInfo(
      txInfoInputs: Vector[TxInInfo],
      txInfoReferenceInputs: Vector[TxInInfo],
      txInfoOutputs: Vector[TxOut],
      txInfoFee: PV1.Value,
      txInfoMint: PV1.Value,
      txInfoDCert: Vector[PV1.DCert],
      txInfoWdrl: AssocMap[PV1.StakingCredential, BigInt],
      txInfoValidRange: PV1.POSIXTimeRange,
      txInfoSignatories: Vector[PV1.PubKeyHash],
      txInfoRedeemers: AssocMap[PV1.ScriptPurpose, PV1.Redeemer],
      txInfoData: AssocMap[PV1.DatumHash, PV1.Datum],
      txInfoId: PV1.TxId
  ):
    /** The info as data (`toData`), made once however many of its transaction's scripts read it in
      * their contexts: each script's context holds the transaction's one info (#85).
      */
    lazy val data: PlutusData = txInfo.toBuiltinData(this)

  /** `data ScriptContext` of V2 (`V2/Contexts.hs:155-159,281`, plutus 507cab8c), its purpose V1's.
    */
  final case class ScriptContext(
      scriptContextTxInfo: TxInfo,
      scriptContextPurpose: PV1.ScriptPurpose
  )

  given txInInfo: ToData[TxInInfo] = i =>
    PlutusData.constr(0, i.txInInfoOutRef.toData, i.txInInfoResolved.toData)

  given txInfo: ToData[TxInfo] = t =>
    PlutusData.constr(
      0,
      t.txInfoInputs.toData,
      t.txInfoReferenceInputs.toData,
      t.txInfoOutputs.toData,
      t.txInfoFee.toData,
      t.txInfoMint.toData,
      t.txInfoDCert.toData,
      t.txInfoWdrl.toData,
      t.txInfoValidRange.toData,
      t.txInfoSignatories.toData,
      t.txInfoRedeemers.toData,
      t.txInfoData.toData,
      t.txInfoId.toData
    )

  given scriptContext: ToData[ScriptContext] = c =>
    PlutusData.constr(0, c.scriptContextTxInfo.data, c.scriptContextPurpose.toData)

/** The types of `PlutusLedgerApi.V3` (plutus 507cab8c, `V3/Contexts.hs`, `V3/Tx.hs`,
  * `V3/MintValue.hs`).
  */
object PV3:
  import ToData.toData
  import PV1.given
  import PV2.given

  /** `newtype TxId` of V3 (`V3/Tx.hs:40-43`, plutus 507cab8c): the bytes', unwrapped. */
  final case class TxId(getTxId: Bytes)

  /** `data TxOutRef` of V3 (`V3/Tx.hs:63-67,81`, plutus 507cab8c). */
  final case class TxOutRef(txOutRefId: TxId, txOutRefIdx: BigInt)

  /** `newtype MintValue` (`V3/MintValue.hs:64-67`, plutus 507cab8c), its map's. */
  final case class MintValue(
      unMintValue: AssocMap[PV1.CurrencySymbol, AssocMap[PV1.TokenName, BigInt]]
  )

  /** `data Rational` (plutus 507cab8c `plutus-tx/src/PlutusTx/Ratio.hs:74,156-158`): the pair of
    * the numerator and the denominator, coprime and the denominator positive, the invariants the
    * reference keeps (`Ratio.hs:68-73`, plutus 261766ad); so it is made only by [[Rational.ratio]]
    * and [[Rational.fromHaskellRatio]] (TYP-1). Not a case class, whose product mirror would make
    * one past them; equal as the pair is, as the reference's derived `Eq`.
    */
  final class Rational private (val numerator: BigInt, val denominator: BigInt):
    override def equals(that: Any): Boolean = that match
      case r: Rational => numerator == r.numerator && denominator == r.denominator
      case _           => false
    override def hashCode: Int = (numerator, denominator).hashCode
    override def toString: String = s"Rational($numerator, $denominator)"

  object Rational:
    /** `ratio` (`plutus-tx/src/PlutusTx/Ratio.hs:216-225`, plutus 261766ad): the ratio reduced, its
      * denominator positive, none for a zero denominator.
      */
    def ratio(n: BigInt, d: BigInt): Option[Rational] =
      Option.when(d != 0) {
        val g = n.gcd(d) * d.signum
        new Rational(n / g, d / g)
      }

    /** `fromHaskellRatio` (`plutus-tx/src/PlutusTx/Ratio.hs:389-390`, plutus 261766ad): the node's
      * exact ratio, which is reduced with a positive denominator already, as `unsafeRatio` of its
      * numerator and denominator leaves it.
      */
    def fromHaskellRatio(r: xyz.kd5ujc.scarab.codec.Rational): Rational =
      new Rational(r.numerator, r.denominator)

  /** `newtype ColdCommitteeCredential` (`V3/Contexts.hs:86`, plutus 507cab8c), the credential's. */
  final case class ColdCommitteeCredential(credential: PV1.Credential)

  /** `newtype HotCommitteeCredential` (`V3/Contexts.hs:110`, plutus 507cab8c). */
  final case class HotCommitteeCredential(credential: PV1.Credential)

  /** `newtype DRepCredential` (`V3/Contexts.hs:134`, plutus 507cab8c). */
  final case class DRepCredential(credential: PV1.Credential)

  /** `data DRep` (`V3/Contexts.hs:158-161,633-639`, plutus 507cab8c). */
  enum DRep:
    case DRep(credential: DRepCredential)
    case DRepAlwaysAbstain
    case DRepAlwaysNoConfidence

  /** `data Delegatee` (`V3/Contexts.hs:168-171,642-648`, plutus 507cab8c). */
  enum Delegatee:
    case DelegStake(pool: PV1.PubKeyHash)
    case DelegVote(drep: DRep)
    case DelegStakeVote(pool: PV1.PubKeyHash, drep: DRep)

  /** `data TxCert` (`V3/Contexts.hs:178-205,651-665`, plutus 507cab8c). */
  enum TxCert:
    case TxCertRegStaking(credential: PV1.Credential, deposit: Option[PV1.Lovelace])
    case TxCertUnRegStaking(credential: PV1.Credential, refund: Option[PV1.Lovelace])
    case TxCertDelegStaking(credential: PV1.Credential, delegatee: Delegatee)
    case TxCertRegDeleg(credential: PV1.Credential, delegatee: Delegatee, deposit: PV1.Lovelace)
    case TxCertRegDRep(credential: DRepCredential, deposit: PV1.Lovelace)
    case TxCertUpdateDRep(credential: DRepCredential)
    case TxCertUnRegDRep(credential: DRepCredential, refund: PV1.Lovelace)
    case TxCertPoolRegister(poolId: PV1.PubKeyHash, poolVfr: PV1.PubKeyHash)
    case TxCertPoolRetire(poolId: PV1.PubKeyHash, epoch: BigInt)
    case TxCertAuthHotCommittee(cold: ColdCommitteeCredential, hot: HotCommitteeCredential)
    case TxCertResignColdCommittee(cold: ColdCommitteeCredential)

  /** `data Voter` (`V3/Contexts.hs:211-214,668-674`, plutus 507cab8c). */
  enum Voter:
    case CommitteeVoter(credential: HotCommitteeCredential)
    case DRepVoter(credential: DRepCredential)
    case StakePoolVoter(pool: PV1.PubKeyHash)

  /** `data Vote` (`V3/Contexts.hs:222-225,677-683`, plutus 507cab8c). */
  enum Vote:
    case VoteNo, VoteYes, Abstain

  /** `data GovernanceActionId` (`V3/Contexts.hs:233-236,686`, plutus 507cab8c). */
  final case class GovernanceActionId(gaidTxId: TxId, gaidGovActionIx: BigInt)

  /** `newtype Constitution` (`V3/Contexts.hs:266-268,692`, plutus 507cab8c), indexed as a record of
    * one field.
    */
  final case class Constitution(constitutionScript: Option[PV1.ScriptHash])

  /** `data ProtocolVersion` (`V3/Contexts.hs:278-281,695`, plutus 507cab8c). */
  final case class ProtocolVersion(pvMajor: BigInt, pvMinor: BigInt)

  /** `newtype ChangedParameters` (`V3/Contexts.hs:321`, plutus 507cab8c), its data. */
  final case class ChangedParameters(getChangedParameters: PlutusData)

  /** `data GovernanceAction` (`V3/Contexts.hs:337-360,699-709`, plutus 507cab8c). */
  enum GovernanceAction:
    case ParameterChange(
        parent: Option[GovernanceActionId],
        changed: ChangedParameters,
        script: Option[PV1.ScriptHash]
    )
    case HardForkInitiation(parent: Option[GovernanceActionId], version: ProtocolVersion)
    case TreasuryWithdrawals(
        withdrawals: AssocMap[PV1.Credential, PV1.Lovelace],
        script: Option[PV1.ScriptHash]
    )
    case NoConfidence(parent: Option[GovernanceActionId])
    case UpdateCommittee(
        parent: Option[GovernanceActionId],
        removed: Vector[ColdCommitteeCredential],
        added: AssocMap[ColdCommitteeCredential, BigInt],
        quorum: Rational
    )
    case NewConstitution(parent: Option[GovernanceActionId], constitution: Constitution)
    case InfoAction

  /** `data ProposalProcedure` (`V3/Contexts.hs:367-371,712`, plutus 507cab8c). */
  final case class ProposalProcedure(
      ppDeposit: PV1.Lovelace,
      ppReturnAddr: PV1.Credential,
      ppGovernanceAction: GovernanceAction
  )

  /** `data ScriptPurpose` of V3 (`V3/Contexts.hs:386-400,715-724`, plutus 507cab8c). */
  enum ScriptPurpose:
    case Minting(currencySymbol: PV1.CurrencySymbol)
    case Spending(txOutRef: TxOutRef)
    case Rewarding(credential: PV1.Credential)
    case Certifying(index: BigInt, txCert: TxCert)
    case Voting(voter: Voter)
    case Proposing(index: BigInt, proposal: ProposalProcedure)

  /** `data ScriptInfo` (`V3/Contexts.hs:404-418,727-736`, plutus 507cab8c): the purpose, with the
    * datum of a spending script.
    */
  enum ScriptInfo:
    case MintingScript(currencySymbol: PV1.CurrencySymbol)
    case SpendingScript(txOutRef: TxOutRef, datum: Option[PV1.Datum])
    case RewardingScript(credential: PV1.Credential)
    case CertifyingScript(index: BigInt, txCert: TxCert)
    case VotingScript(voter: Voter)
    case ProposingScript(index: BigInt, proposal: ProposalProcedure)

  /** `data TxInInfo` of V3 (`V3/Contexts.hs:422-425,742`, plutus 507cab8c). */
  final case class TxInInfo(txInInfoOutRef: TxOutRef, txInInfoResolved: PV2.TxOut)

  /** `data TxInfo` of V3 (`V3/Contexts.hs:436-457,745`, plutus 507cab8c). */
  final case class TxInfo(
      txInfoInputs: Vector[TxInInfo],
      txInfoReferenceInputs: Vector[TxInInfo],
      txInfoOutputs: Vector[PV2.TxOut],
      txInfoFee: PV1.Lovelace,
      txInfoMint: MintValue,
      txInfoTxCerts: Vector[TxCert],
      txInfoWdrl: AssocMap[PV1.Credential, PV1.Lovelace],
      txInfoValidRange: PV1.POSIXTimeRange,
      txInfoSignatories: Vector[PV1.PubKeyHash],
      txInfoRedeemers: AssocMap[ScriptPurpose, PV1.Redeemer],
      txInfoData: AssocMap[PV1.DatumHash, PV1.Datum],
      txInfoId: TxId,
      txInfoVotes: AssocMap[Voter, AssocMap[GovernanceActionId, Vote]],
      txInfoProposalProcedures: Vector[ProposalProcedure],
      txInfoCurrentTreasuryAmount: Option[PV1.Lovelace],
      txInfoTreasuryDonation: Option[PV1.Lovelace]
  ):
    /** The info as data (`toData`), made once however many of its transaction's scripts read it in
      * their contexts: each script's context holds the transaction's one info (#85).
      */
    lazy val data: PlutusData = txInfo.toBuiltinData(this)

  /** `data ScriptContext` of V3 (`V3/Contexts.hs:485-491,748`, plutus 507cab8c). */
  final case class ScriptContext(
      scriptContextTxInfo: TxInfo,
      scriptContextRedeemer: PV1.Redeemer,
      scriptContextScriptInfo: ScriptInfo
  )

  given txId: ToData[TxId] = t => B(t.getTxId)

  given txOutRef: ToData[TxOutRef] = r =>
    PlutusData.constr(0, r.txOutRefId.toData, r.txOutRefIdx.toData)

  given mintValue: ToData[MintValue] = _.unMintValue.toData

  given rational: ToData[Rational] = r => (r.numerator, r.denominator).toData

  given coldCommitteeCredential: ToData[ColdCommitteeCredential] = _.credential.toData
  given hotCommitteeCredential: ToData[HotCommitteeCredential] = _.credential.toData
  given dRepCredential: ToData[DRepCredential] = _.credential.toData

  given dRep: ToData[DRep] =
    case DRep.DRep(c)                => PlutusData.constr(0, c.toData)
    case DRep.DRepAlwaysAbstain      => PlutusData.constr(1)
    case DRep.DRepAlwaysNoConfidence => PlutusData.constr(2)

  given delegatee: ToData[Delegatee] =
    case Delegatee.DelegStake(p)        => PlutusData.constr(0, p.toData)
    case Delegatee.DelegVote(d)         => PlutusData.constr(1, d.toData)
    case Delegatee.DelegStakeVote(p, d) => PlutusData.constr(2, p.toData, d.toData)

  given txCert: ToData[TxCert] =
    case TxCert.TxCertRegStaking(c, d)       => PlutusData.constr(0, c.toData, d.toData)
    case TxCert.TxCertUnRegStaking(c, r)     => PlutusData.constr(1, c.toData, r.toData)
    case TxCert.TxCertDelegStaking(c, d)     => PlutusData.constr(2, c.toData, d.toData)
    case TxCert.TxCertRegDeleg(c, d, l)      => PlutusData.constr(3, c.toData, d.toData, l.toData)
    case TxCert.TxCertRegDRep(c, l)          => PlutusData.constr(4, c.toData, l.toData)
    case TxCert.TxCertUpdateDRep(c)          => PlutusData.constr(5, c.toData)
    case TxCert.TxCertUnRegDRep(c, l)        => PlutusData.constr(6, c.toData, l.toData)
    case TxCert.TxCertPoolRegister(p, v)     => PlutusData.constr(7, p.toData, v.toData)
    case TxCert.TxCertPoolRetire(p, e)       => PlutusData.constr(8, p.toData, e.toData)
    case TxCert.TxCertAuthHotCommittee(c, h) => PlutusData.constr(9, c.toData, h.toData)
    case TxCert.TxCertResignColdCommittee(c) => PlutusData.constr(10, c.toData)

  given voter: ToData[Voter] =
    case Voter.CommitteeVoter(c) => PlutusData.constr(0, c.toData)
    case Voter.DRepVoter(c)      => PlutusData.constr(1, c.toData)
    case Voter.StakePoolVoter(p) => PlutusData.constr(2, p.toData)

  given vote: ToData[Vote] =
    case Vote.VoteNo  => PlutusData.constr(0)
    case Vote.VoteYes => PlutusData.constr(1)
    case Vote.Abstain => PlutusData.constr(2)

  given governanceActionId: ToData[GovernanceActionId] = g =>
    PlutusData.constr(0, g.gaidTxId.toData, g.gaidGovActionIx.toData)

  given constitution: ToData[Constitution] = c => PlutusData.constr(0, c.constitutionScript.toData)

  given protocolVersion: ToData[ProtocolVersion] = v =>
    PlutusData.constr(0, v.pvMajor.toData, v.pvMinor.toData)

  given changedParameters: ToData[ChangedParameters] = _.getChangedParameters

  given governanceAction: ToData[GovernanceAction] =
    case GovernanceAction.ParameterChange(p, c, s) =>
      PlutusData.constr(0, p.toData, c.toData, s.toData)
    case GovernanceAction.HardForkInitiation(p, v)  => PlutusData.constr(1, p.toData, v.toData)
    case GovernanceAction.TreasuryWithdrawals(w, s) => PlutusData.constr(2, w.toData, s.toData)
    case GovernanceAction.NoConfidence(p)           => PlutusData.constr(3, p.toData)
    case GovernanceAction.UpdateCommittee(p, r, a, q) =>
      PlutusData.constr(4, p.toData, r.toData, a.toData, q.toData)
    case GovernanceAction.NewConstitution(p, c) => PlutusData.constr(5, p.toData, c.toData)
    case GovernanceAction.InfoAction            => PlutusData.constr(6)

  given proposalProcedure: ToData[ProposalProcedure] = p =>
    PlutusData.constr(
      0,
      p.ppDeposit.toData,
      p.ppReturnAddr.toData,
      p.ppGovernanceAction.toData
    )

  given scriptPurpose: ToData[ScriptPurpose] =
    case ScriptPurpose.Minting(c)       => PlutusData.constr(0, c.toData)
    case ScriptPurpose.Spending(r)      => PlutusData.constr(1, r.toData)
    case ScriptPurpose.Rewarding(c)     => PlutusData.constr(2, c.toData)
    case ScriptPurpose.Certifying(i, c) => PlutusData.constr(3, i.toData, c.toData)
    case ScriptPurpose.Voting(v)        => PlutusData.constr(4, v.toData)
    case ScriptPurpose.Proposing(i, p)  => PlutusData.constr(5, i.toData, p.toData)

  given scriptInfo: ToData[ScriptInfo] =
    case ScriptInfo.MintingScript(c)       => PlutusData.constr(0, c.toData)
    case ScriptInfo.SpendingScript(r, d)   => PlutusData.constr(1, r.toData, d.toData)
    case ScriptInfo.RewardingScript(c)     => PlutusData.constr(2, c.toData)
    case ScriptInfo.CertifyingScript(i, c) => PlutusData.constr(3, i.toData, c.toData)
    case ScriptInfo.VotingScript(v)        => PlutusData.constr(4, v.toData)
    case ScriptInfo.ProposingScript(i, p)  => PlutusData.constr(5, i.toData, p.toData)

  given txInInfo: ToData[TxInInfo] = i =>
    PlutusData.constr(0, i.txInInfoOutRef.toData, i.txInInfoResolved.toData)

  given txInfo: ToData[TxInfo] = t =>
    PlutusData.constr(
      0,
      t.txInfoInputs.toData,
      t.txInfoReferenceInputs.toData,
      t.txInfoOutputs.toData,
      t.txInfoFee.toData,
      t.txInfoMint.toData,
      t.txInfoTxCerts.toData,
      t.txInfoWdrl.toData,
      t.txInfoValidRange.toData,
      t.txInfoSignatories.toData,
      t.txInfoRedeemers.toData,
      t.txInfoData.toData,
      t.txInfoId.toData,
      t.txInfoVotes.toData,
      t.txInfoProposalProcedures.toData,
      t.txInfoCurrentTreasuryAmount.toData,
      t.txInfoTreasuryDonation.toData
    )

  given scriptContext: ToData[ScriptContext] = c =>
    PlutusData.constr(
      0,
      c.scriptContextTxInfo.data,
      c.scriptContextRedeemer.toData,
      c.scriptContextScriptInfo.toData
    )
