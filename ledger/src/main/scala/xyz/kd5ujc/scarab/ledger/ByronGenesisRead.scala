// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.byron.{ByronCbor, ByronParams, SoftforkRule, TxFeePolicy}
import xyz.kd5ujc.scarab.ledger.json.{CanonicalJson, HaskellRead, StrictBase64}

/** A field of the Byron genesis, as `FromJSON GenesisData` reads it
  * (`Cardano/Chain/Genesis/Data.hs:85-102`, cardano-ledger f649f975), with the fields of
  * `blockVersionData` (`ProtocolParameters`) and of a delegation certificate.
  */
enum ByronGenesisField(val key: String):
  /** The whole file, which is an object. */
  case Top extends ByronGenesisField("")
  case BootStakeholders extends ByronGenesisField("bootStakeholders")
  case HeavyDelegation extends ByronGenesisField("heavyDelegation")
  case StartTime extends ByronGenesisField("startTime")
  case NonAvvmBalances extends ByronGenesisField("nonAvvmBalances")
  case BlockVersionData extends ByronGenesisField("blockVersionData")
  case ProtocolConsts extends ByronGenesisField("protocolConsts")
  case K extends ByronGenesisField("k")
  case ProtocolMagic extends ByronGenesisField("protocolMagic")
  case AvvmDistr extends ByronGenesisField("avvmDistr")
  case ScriptVersion extends ByronGenesisField("scriptVersion")
  case SlotDuration extends ByronGenesisField("slotDuration")
  case MaxBlockSize extends ByronGenesisField("maxBlockSize")
  case MaxHeaderSize extends ByronGenesisField("maxHeaderSize")
  case MaxTxSize extends ByronGenesisField("maxTxSize")
  case MaxProposalSize extends ByronGenesisField("maxProposalSize")
  case MpcThd extends ByronGenesisField("mpcThd")
  case HeavyDelThd extends ByronGenesisField("heavyDelThd")
  case UpdateVoteThd extends ByronGenesisField("updateVoteThd")
  case UpdateProposalThd extends ByronGenesisField("updateProposalThd")
  case UpdateImplicit extends ByronGenesisField("updateImplicit")
  case SoftforkRule extends ByronGenesisField("softforkRule")
  case InitThd extends ByronGenesisField("initThd")
  case MinThd extends ByronGenesisField("minThd")
  case ThdDecrement extends ByronGenesisField("thdDecrement")
  case TxFeePolicy extends ByronGenesisField("txFeePolicy")
  case Summand extends ByronGenesisField("summand")
  case Multiplier extends ByronGenesisField("multiplier")
  case UnlockStakeEpoch extends ByronGenesisField("unlockStakeEpoch")
  case Omega extends ByronGenesisField("omega")
  case IssuerPk extends ByronGenesisField("issuerPk")
  case DelegatePk extends ByronGenesisField("delegatePk")
  case Cert extends ByronGenesisField("cert")

/** Why the reference refuses a field of the Byron genesis: the `SchemaError` of its canonical-JSON
  * instance (cardano-prelude 0.2.2.0 `Cardano/Prelude/Json/Canonical.hs`, canonical-json 0.6.0.1
  * `Text/JSON/Canonical/Class.hs`, cardano-crypto-wrapper 1.7.0.0).
  */
enum ByronFieldError:
  /** `fromJSField` of an absent key (`unknownField`). */
  case Missing

  /** A value of another kind than the instance reads (`expectedButGotValue`); the value itself is
    * not kept, so a deep one costs nothing to report.
    */
  case Kind(expected: CanonicalJson.Kind, actual: CanonicalJson.Kind)

  /** A string `readEither` does not read as the field's `Word64`, `Natural` or `Integer`. */
  case NumberText(text: String)

  /** A number outside `0 .. 2^bits - 1`, the range of the field's `Word16`, `Word32` or `Word64`.
    * The reference takes it modulo 2^`bits` (`fromIntegral`, `fromInteger`); it is refused here
    * (overflow-improvements.md#byron-genesis-words).
    */
  case OutOfRange(value: BigInt, bits: Int)

  /** A `LovelacePortion` above 10^15 (`Cardano/Chain/Common/LovelacePortion.hs:91-100`). */
  case Portion(value: BigInt)

  /** Not base16 (`B16.decode`): a key hash or a signature. */
  case Hex(text: String, cause: Bytes.HexError)

  /** A key hash of other than 28 bytes (`decodeAbstractHash`, `Cardano/Crypto/Hashing.hs:196-208`).
    */
  case HashLength(text: String, actual: Int)

  /** Not base64 as base64-bytestring decodes it (`parseFullVerificationKey`). */
  case Base64(text: String)

  /** An extended verification key of other than 64 bytes (`CC.xpub`). */
  case KeyLength(actual: Int)

  /** A signature of other than 64 bytes (`CC.xsignature`). */
  case SignatureLength(actual: Int)

  /** `GenesisDelegationInvalidKey`: a delegation under `key`, whose issuer's hash is `issuer`. */
  case DelegationKey(key: Bytes, issuer: Bytes)

  /** `GenesisDelegationMultiLayerDelegation`: `delegate` is also an issuer of the delegation map.
    */
  case MultiLayer(delegate: Bytes)

  def message: String = this match
    case Missing => "missing"
    case Kind(expected, actual) =>
      s"expected ${expected.toString.toLowerCase} but got ${actual.toString.toLowerCase}"
    case NumberText(text)         => s"cannot read \"$text\" as a number"
    case OutOfRange(value, bits)  => s"$value is outside 0 to 2^$bits - 1"
    case Portion(value)           => s"$value is above 10^15"
    case Hex(text, _)             => s"\"$text\" is not base16"
    case HashLength(text, n)      => s"\"$text\" is $n bytes, a key hash is 28"
    case Base64(text)             => s"\"$text\" is not base64"
    case KeyLength(n)             => s"a verification key of $n bytes, not 64"
    case SignatureLength(n)       => s"a signature of $n bytes, not 64"
    case DelegationKey(key, hash) => s"under ${key.hex} a certificate of issuer ${hash.hex}"
    case MultiLayer(delegate)     => s"${delegate.hex} is a delegate and an issuer"

/** `FromJSON GenesisData` (`Cardano/Chain/Genesis/Data.hs:85-102`, cardano-ledger f649f975) over
  * the canonical JSON `readGenesisData` parsed. The fields are read in the reference's order, and
  * the first refusal is the reference's: `protocolConsts` is looked up first (`:88-90`), then
  * `bootStakeholders`, `heavyDelegation`, `startTime`, `nonAvvmBalances`, `blockVersionData`, `k`,
  * `protocolMagic` and `avvmDistr`. Measured field by field with
  * `scripts/oracle/byron-genesis-data` (#734).
  */
private[ledger] object ByronGenesisRead:
  import ByronGenesisField as F
  import ByronFieldError as E

  private type Read[A] = Either[ConfigError, A]

  private def refuse[A](field: ByronGenesisField, error: ByronFieldError): Read[A] =
    Left(ConfigError(ConfigProblem.ByronGenesisValue(field, error)))

  /** The fields of the genesis, in the order of `GenesisData`. */
  final case class Fields(
      genesisKeys: Vector[Bytes],
      delegations: Vector[ByronDelegation],
      startTime: Long,
      nonAvvm: Vector[(Bytes, BigInt)],
      params: ByronParams,
      securityParam: BigInt,
      protocolMagic: Long,
      avvm: Vector[(Bytes, BigInt)]
  )

  def fields(value: CanonicalJson): Read[Fields] =
    for
      top <- entries(F.Top, value)
      consts <- lookup(top, F.ProtocolConsts)
      keys <- field(top, F.BootStakeholders).flatMap(stakeholders)
      delegations <- field(top, F.HeavyDelegation).flatMap(heavyDelegation)
      start <- field(top, F.StartTime).flatMap(int54(F.StartTime, _))
      nonAvvm <- ByronGenesisFile.nonAvvmBalances(value)
      params <- field(top, F.BlockVersionData).flatMap(protocolParameters)
      k <- entries(F.ProtocolConsts, consts)
        .flatMap(field(_, F.K))
        .flatMap(word(F.K, _, 64))
      magic <- entries(F.ProtocolConsts, consts)
        .flatMap(field(_, F.ProtocolMagic))
        .flatMap(word(F.ProtocolMagic, _, 32))
      avvm <- ByronGenesisFile.avvmBalances(value)
    yield Fields(keys, delegations, start, nonAvvm, params, k, magic.toLong, avvm)

  /** `fromJSObject`: the entries of an object, in the order of the text. */
  private[ledger] def entries(
      field: ByronGenesisField,
      value: CanonicalJson
  ): Read[Vector[(Bytes, CanonicalJson)]] =
    value match
      case CanonicalJson.Obj(fields) => Right(fields)
      case other                     => refuse(field, E.Kind(CanonicalJson.Kind.Object, other.kind))

  /** `lookup` of the first entry under the field's key, as `fromJSField` does. */
  private def lookup(
      fields: Vector[(Bytes, CanonicalJson)],
      field: ByronGenesisField
  ): Read[CanonicalJson] =
    val key = Bytes.fromArray(field.key.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
    fields.collectFirst { case (`key`, v) => v }.fold(refuse(field, E.Missing))(Right(_))

  private def field(fields: Vector[(Bytes, CanonicalJson)], f: ByronGenesisField) =
    lookup(fields, f)

  /** `FromJSON Int54`: a number. */
  private def int54(field: ByronGenesisField, value: CanonicalJson): Read[Long] = value match
    case CanonicalJson.Num(n) => Right(n)
    case other                => refuse(field, E.Kind(CanonicalJson.Kind.Number, other.kind))

  /** `FromJSON Word16` and `FromJSON Word32`, and `k` (a `BlockCount`, a `Word64`, read as an
    * `Int54`): a number, which the reference takes modulo 2^`bits` (`fromIntegral`). A number
    * outside `0 .. 2^bits - 1` is refused instead (overflow-improvements.md#byron-genesis-words).
    */
  private def word(field: ByronGenesisField, value: CanonicalJson, bits: Int): Read[BigInt] =
    int54(field, value).flatMap(n => inRange(field, BigInt(n), bits))

  /** `n` if it is in `0 .. 2^bits - 1`, the field's word, else its refusal. */
  private[ledger] def inRange(field: ByronGenesisField, n: BigInt, bits: Int): Read[BigInt] =
    if n.signum < 0 || n.bitLength > bits then refuse(field, E.OutOfRange(n, bits)) else Right(n)

  /** A string's characters as `fromJSString` gives them: one per byte. */
  private def text(field: ByronGenesisField, value: CanonicalJson): Read[String] = value match
    case CanonicalJson.Str(b) => Right(CanonicalJson.string(b))
    case other                => refuse(field, E.Kind(CanonicalJson.Kind.String, other.kind))

  private def readText(read: String => Option[BigInt])(
      field: ByronGenesisField,
      value: CanonicalJson
  ): Read[BigInt] =
    text(field, value).flatMap(t => read(t).fold(refuse(field, E.NumberText(t)))(Right(_)))

  /** `FromJSON Word64`, `FromJSON Natural` and `FromJSON Integer`: `readEither` of a string. The
    * reference's `Read Word64` takes the integer modulo 2^64 (`fromInteger`); one outside
    * `0 .. 2^64 - 1` is refused here (overflow-improvements.md#byron-genesis-words).
    */
  private def word64(field: ByronGenesisField, value: CanonicalJson): Read[BigInt] =
    integer(field, value).flatMap(inRange(field, _, 64))
  private val natural = readText(HaskellRead.natural)
  private val integer = readText(HaskellRead.integer)

  /** `FromJSON LovelacePortion`: a `Word64` of at most 10^15. */
  private def portion(field: ByronGenesisField, value: CanonicalJson): Read[BigInt] =
    word64(field, value).flatMap(n =>
      if n > LovelacePortionDenominator then refuse(field, E.Portion(n)) else Right(n)
    )

  private val LovelacePortionDenominator = BigInt(10).pow(15)

  /** `FromObjectKey KeyHash` (`Cardano/Chain/Common/KeyHash.hs:49-53`): base16 of 28 bytes. */
  private def keyHash(field: ByronGenesisField, key: Bytes): Read[Bytes] =
    val t = CanonicalJson.string(key)
    Bytes
      .fromHex(t)
      .fold(
        e => refuse(field, E.Hex(t, e)),
        b => if b.size == 28 then Right(b) else refuse(field, E.HashLength(t, b.size))
      )

  /** `FromJSON (Map k a)` (canonical-json 0.6.0.1 `Text/JSON/Canonical/Class.hs:162-175`): each
    * entry in the order of the text, its key then its value; of two equal keys the first holds.
    */
  private def map[A](field: ByronGenesisField, value: CanonicalJson)(
      entry: CanonicalJson => Read[A]
  ): Read[Vector[(Bytes, A)]] =
    entries(field, value)
      .flatMap(_.traverse((k, v) => (keyHash(field, k), entry(v)).tupled))
      .map(_.distinctBy(_._1).sortBy(_._1))

  /** `FromJSON GenesisKeyHashes` (`Cardano/Chain/Genesis/KeyHashes.hs:40-42`): the keys of a
    * `Map KeyHash Word16`, in ascending order.
    */
  private def stakeholders(value: CanonicalJson): Read[Vector[Bytes]] =
    map(F.BootStakeholders, value)(word(F.BootStakeholders, _, 16)).map(_.map(_._1))

  /** `FromJSON GenesisDelegation` (`Cardano/Chain/Genesis/Delegation.hs:50-58`): a
    * `Map KeyHash Certificate`, then `recreateGenesisDelegation` (`:114-127`) in the order of the
    * keys: each key is the hash of its certificate's issuer, and no delegate is a key.
    */
  private def heavyDelegation(value: CanonicalJson): Read[Vector[ByronDelegation]] =
    for
      certs <- map(F.HeavyDelegation, value)(certificate)
      keys = certs.map(_._1).toSet
      _ <- certs.traverse_ { (key, cert) =>
        val issuer = ByronCbor.keyHash(cert.issuer)
        val delegate = ByronCbor.keyHash(cert.delegate)
        if key != issuer then refuse(F.HeavyDelegation, E.DelegationKey(key, issuer))
        else if keys.contains(delegate) then refuse(F.HeavyDelegation, E.MultiLayer(delegate))
        else Right(())
      }
    yield certs.map(_._2)

  /** `FromJSON Certificate` (`Cardano/Chain/Delegation/Certificate.hs:257-263`): `omega` an `Int54`
    * taken as an `EpochNumber` (`Word64`), the two keys, and the signature, which is not checked
    * here. A negative `omega`, which the reference takes modulo 2^64, is refused
    * (overflow-improvements.md#byron-genesis-words).
    */
  private def certificate(value: CanonicalJson): Read[ByronDelegation] =
    for
      fields <- entries(F.HeavyDelegation, value)
      omega <- field(fields, F.Omega).flatMap(word(F.Omega, _, 64))
      issuer <- field(fields, F.IssuerPk).flatMap(verificationKey(F.IssuerPk, _))
      delegate <- field(fields, F.DelegatePk).flatMap(verificationKey(F.DelegatePk, _))
      cert <- field(fields, F.Cert).flatMap(signature)
    yield ByronDelegation(omega, issuer, delegate, cert)

  /** `FromJSON VerificationKey` (`Cardano/Crypto/Signing/VerificationKey.hs:75-76, 137-140`):
    * base64 of a 64-byte extended key.
    */
  private def verificationKey(field: ByronGenesisField, value: CanonicalJson): Read[Bytes] =
    text(field, value).flatMap(t =>
      StrictBase64
        .decode(t)
        .fold(refuse(field, E.Base64(t)))(b =>
          if b.size == 64 then Right(b) else refuse(field, E.KeyLength(b.size))
        )
    )

  /** `FromJSON (Signature a)` (`Cardano/Crypto/Signing/Signature.hs:93-94, 120-124`): base16 of a
    * 64-byte signature.
    */
  private def signature(value: CanonicalJson): Read[Bytes] =
    text(F.Cert, value).flatMap(t =>
      Bytes
        .fromHex(t)
        .fold(
          e => refuse(F.Cert, E.Hex(t, e)),
          b => if b.size == 64 then Right(b) else refuse(F.Cert, E.SignatureLength(b.size))
        )
    )

  /** `FromJSON ProtocolParameters` (`Cardano/Chain/Update/ProtocolParameters.hs:144-160`). */
  private def protocolParameters(value: CanonicalJson): Read[ByronParams] =
    for
      fields <- entries(F.BlockVersionData, value)
      scriptVersion <- field(fields, F.ScriptVersion).flatMap(word(F.ScriptVersion, _, 16))
      slotDuration <- field(fields, F.SlotDuration).flatMap(natural(F.SlotDuration, _))
      maxBlockSize <- field(fields, F.MaxBlockSize).flatMap(natural(F.MaxBlockSize, _))
      maxHeaderSize <- field(fields, F.MaxHeaderSize).flatMap(natural(F.MaxHeaderSize, _))
      maxTxSize <- field(fields, F.MaxTxSize).flatMap(natural(F.MaxTxSize, _))
      maxProposalSize <- field(fields, F.MaxProposalSize).flatMap(natural(F.MaxProposalSize, _))
      mpcThd <- field(fields, F.MpcThd).flatMap(portion(F.MpcThd, _))
      heavyDelThd <- field(fields, F.HeavyDelThd).flatMap(portion(F.HeavyDelThd, _))
      updateVoteThd <- field(fields, F.UpdateVoteThd).flatMap(portion(F.UpdateVoteThd, _))
      updateProposalThd <- field(fields, F.UpdateProposalThd)
        .flatMap(portion(F.UpdateProposalThd, _))
      // `ppUpdateProposalTTL` is a `SlotNumber`, a `Word64`
      updateImplicit <- field(fields, F.UpdateImplicit).flatMap(word64(F.UpdateImplicit, _))
      softfork <- field(fields, F.SoftforkRule).flatMap(softforkRule)
      fee <- field(fields, F.TxFeePolicy).flatMap(txFeePolicy)
      unlock <- field(fields, F.UnlockStakeEpoch).flatMap(word64(F.UnlockStakeEpoch, _))
    yield ByronParams(
      scriptVersion,
      slotDuration,
      maxBlockSize,
      maxHeaderSize,
      maxTxSize,
      maxProposalSize,
      mpcThd,
      heavyDelThd,
      updateVoteThd,
      updateProposalThd,
      updateImplicit,
      softfork,
      fee,
      unlock
    )

  /** `FromJSON SoftforkRule` (`Cardano/Chain/Update/SoftforkRule.hs:90-95`): three portions. */
  private def softforkRule(value: CanonicalJson): Read[SoftforkRule] =
    for
      fields <- entries(F.SoftforkRule, value)
      init <- field(fields, F.InitThd).flatMap(portion(F.InitThd, _))
      min <- field(fields, F.MinThd).flatMap(portion(F.MinThd, _))
      decrement <- field(fields, F.ThdDecrement).flatMap(portion(F.ThdDecrement, _))
    yield SoftforkRule(init, min, decrement)

  /** `FromJSON TxFeePolicy` (`Cardano/Chain/Common/TxFeePolicy.hs:109-128`): `summand` a `Word64`
    * of nano-lovelace divided by 10^9 into lovelace (which cannot then exceed `maxLovelaceVal`),
    * `multiplier` an `Integer` of nano-lovelace per byte, kept here in nano-lovelace.
    */
  private def txFeePolicy(value: CanonicalJson): Read[TxFeePolicy] =
    for
      fields <- entries(F.TxFeePolicy, value)
      summand <- field(fields, F.Summand).flatMap(word64(F.Summand, _))
      multiplier <- field(fields, F.Multiplier).flatMap(integer(F.Multiplier, _))
    yield TxFeePolicy((summand / TxFeePolicy.Nano) * TxFeePolicy.Nano, multiplier)
