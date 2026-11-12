// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import xyz.kd5ujc.scarab.uplc.DefaultFun.*

/** What a script of a ledger language may use at a protocol version: the ledger languages, the
  * builtins, the Plutus Core versions and the decoder's size bounds, transcribed from
  * `PlutusLedgerApi/Common/Versions.hs` of plutus 1.70.0.0 (`507cab8c`), with the refusals of the
  * ledger's decoder (`Common/SerialisedScript.hs:195-266`, `Common/Eval.hs:107-130`) and of the
  * Flat decoder (`UntypedPlutusCore/Core/Instance/Flat.hs:167-185`) as typed cases.
  *
  * This is the own evaluator's copy, independent of the Scalus backend's `PlutusAvailability` (ADR
  * 0027). A script's builtins and versions are those of the language it is evaluated as
  * ([[PlutusLanguage.evaluatedAs]]): the node of 1.70.0.0 decodes a V4 script with plutus's V3
  * functions. Plutus's own V4 rows are transcribed too ([[introductions]]); from protocol 12, the
  * first at which a V4 script decodes, they equal V3's.
  */
object Availability:

  /** The batches of builtins in the order they were released (`Versions.hs:156-313`). */
  enum Batch(val builtins: Vector[DefaultFun]):
    /** `Versions.hs:156-209`. */
    case Batch1
        extends Batch(
          Vector(
            AddInteger,
            SubtractInteger,
            MultiplyInteger,
            DivideInteger,
            QuotientInteger,
            RemainderInteger,
            ModInteger,
            EqualsInteger,
            LessThanInteger,
            LessThanEqualsInteger,
            AppendByteString,
            ConsByteString,
            SliceByteString,
            LengthOfByteString,
            IndexByteString,
            EqualsByteString,
            LessThanByteString,
            LessThanEqualsByteString,
            Sha2_256,
            Sha3_256,
            Blake2b_256,
            VerifyEd25519Signature,
            AppendString,
            EqualsString,
            EncodeUtf8,
            DecodeUtf8,
            IfThenElse,
            ChooseUnit,
            Trace,
            FstPair,
            SndPair,
            ChooseList,
            MkCons,
            HeadList,
            TailList,
            NullList,
            ChooseData,
            ConstrData,
            MapData,
            ListData,
            IData,
            BData,
            UnConstrData,
            UnMapData,
            UnListData,
            UnIData,
            UnBData,
            EqualsData,
            MkPairData,
            MkNilData,
            MkNilPairData
          )
        )

    /** `Versions.hs:212-214`. */
    case Batch2 extends Batch(Vector(SerialiseData))

    /** `Versions.hs:217-219`. */
    case Batch3
        extends Batch(Vector(VerifyEcdsaSecp256k1Signature, VerifySchnorrSecp256k1Signature))

    /** `Versions.hs:226-247`. */
    case Batch4a
        extends Batch(
          Vector(
            Bls12_381_G1_add,
            Bls12_381_G1_neg,
            Bls12_381_G1_scalarMul,
            Bls12_381_G1_equal,
            Bls12_381_G1_hashToGroup,
            Bls12_381_G1_compress,
            Bls12_381_G1_uncompress,
            Bls12_381_G2_add,
            Bls12_381_G2_neg,
            Bls12_381_G2_scalarMul,
            Bls12_381_G2_equal,
            Bls12_381_G2_hashToGroup,
            Bls12_381_G2_compress,
            Bls12_381_G2_uncompress,
            Bls12_381_millerLoop,
            Bls12_381_mulMlResult,
            Bls12_381_finalVerify,
            Keccak_256,
            Blake2b_224
          )
        )

    /** `Versions.hs:263-265`. */
    case Batch4b extends Batch(Vector(IntegerToByteString, ByteStringToInteger))

    /** `Versions.hs:272-286`. */
    case Batch5
        extends Batch(
          Vector(
            AndByteString,
            OrByteString,
            XorByteString,
            ComplementByteString,
            ReadBit,
            WriteBits,
            ReplicateByte,
            ShiftByteString,
            RotateByteString,
            CountSetBits,
            FindFirstSetBit,
            Ripemd_160
          )
        )

    /** `Versions.hs:291-307`. */
    case Batch6
        extends Batch(
          Vector(
            ExpModInteger,
            DropList,
            LengthOfArray,
            ListToArray,
            IndexArray,
            Bls12_381_G1_multiScalarMul,
            Bls12_381_G2_multiScalarMul,
            InsertCoin,
            LookupCoin,
            UnionValue,
            ValueContains,
            ValueData,
            UnValueData,
            ScaleValue
          )
        )

    /** Implemented but approved for no protocol version (`Versions.hs:311-313`). */
    case Batch7
        extends Batch(Vector(MultiIndexArray, Policies, AssetCount, KeepPolicies, DropPolicies))

  /** When a feature arrives: at a protocol version, or at `futurePV`
    * (`ProtocolVersions.hs:124-125`, `MajorProtocolVersion maxBound`). The reference's
    * `collectUpTo` includes a `futurePV` feature when asked at `maxBound` itself
    * (`Common/Versions.hs:127-135`); this model's protocol versions stop well below that (see
    * [[ProtocolMajor]]), so here no protocol version reaches it.
    */
  enum Arrival:
    case At(protocol: ProtocolMajor)
    case Future

    def reachedBy(protocol: ProtocolMajor): Boolean = this match
      case At(p)  => p.value <= protocol.value
      case Future => false

  import Batch.*
  import Arrival.*

  /** `builtinsIntroducedIn` (`Versions.hs:320-351`), for plutus's own ledger language. */
  def introductions(language: PlutusLanguage): Vector[(Arrival, Vector[Batch])] = language match
    case PlutusLanguage.V1 =>
      Vector(
        At(ProtocolMajor.Alonzo) -> Vector(Batch1),
        At(ProtocolMajor.VanRossem) -> Vector(Batch2, Batch3, Batch4a, Batch4b, Batch5, Batch6),
        Future -> Vector(Batch7)
      )
    case PlutusLanguage.V2 =>
      Vector(
        At(ProtocolMajor.Vasil) -> Vector(Batch1, Batch2),
        At(ProtocolMajor.Valentine) -> Vector(Batch3),
        At(ProtocolMajor.Plomin) -> Vector(Batch4b),
        At(ProtocolMajor.VanRossem) -> Vector(Batch4a, Batch5, Batch6),
        Future -> Vector(Batch7)
      )
    case PlutusLanguage.V3 =>
      Vector(
        At(ProtocolMajor.Chang) -> Vector(Batch1, Batch2, Batch3, Batch4a, Batch4b),
        At(ProtocolMajor.Plomin) -> Vector(Batch5),
        At(ProtocolMajor.VanRossem) -> Vector(Batch6),
        Future -> Vector(Batch7)
      )
    case PlutusLanguage.V4 =>
      Vector(
        At(ProtocolMajor.Dijkstra) -> Vector(
          Batch1,
          Batch2,
          Batch3,
          Batch4a,
          Batch4b,
          Batch5,
          Batch6
        ),
        Future -> Vector(Batch7)
      )

  /** `plcVersionsIntroducedIn` (`Versions.hs:362-382`), for plutus's own ledger language. */
  def versionIntroductions(language: PlutusLanguage): Vector[(Arrival, Vector[ProgramVersion])] =
    language match
      case PlutusLanguage.V1 | PlutusLanguage.V2 =>
        val first =
          if language == PlutusLanguage.V1 then ProtocolMajor.Alonzo else ProtocolMajor.Vasil
        Vector(
          At(first) -> Vector(ProgramVersion.V1_0_0),
          At(ProtocolMajor.VanRossem) -> Vector(ProgramVersion.V1_1_0)
        )
      case PlutusLanguage.V3 =>
        Vector(At(ProtocolMajor.Chang) -> Vector(ProgramVersion.V1_0_0, ProgramVersion.V1_1_0))
      case PlutusLanguage.V4 =>
        Vector(At(ProtocolMajor.Dijkstra) -> Vector(ProgramVersion.V1_0_0, ProgramVersion.V1_1_0))

  /** `ledgerLanguageIntroducedIn` (`Versions.hs:109-114`); for V4 also the ledger's own guard. */
  def languageIntroducedIn(language: PlutusLanguage): ProtocolMajor = language match
    case PlutusLanguage.V1 => ProtocolMajor.Alonzo
    case PlutusLanguage.V2 => ProtocolMajor.Vasil
    case PlutusLanguage.V3 => ProtocolMajor.Chang
    case PlutusLanguage.V4 => ProtocolMajor.Dijkstra

  /** `builtinsAvailableIn` (`Versions.hs:355-356`) of the language a script of `language` is
    * evaluated as.
    */
  def builtins(language: PlutusLanguage, protocol: ProtocolMajor): Set[DefaultFun] =
    introductions(language.evaluatedAs)
      .collect { case (arrival, batches) if arrival.reachedBy(protocol) => batches }
      .flatten
      .flatMap(_.builtins)
      .toSet

  def allows(language: PlutusLanguage, protocol: ProtocolMajor, fun: DefaultFun): Boolean =
    builtins(language, protocol).contains(fun)

  /** `plcVersionsAvailableIn` (`Versions.hs:386-387`) of the language a script is evaluated as. */
  def programVersions(language: PlutusLanguage, protocol: ProtocolMajor): Set[ProgramVersion] =
    versionIntroductions(language.evaluatedAs)
      .collect { case (arrival, vs) if arrival.reachedBy(protocol) => vs }
      .flatten
      .toSet

  /** The bounds of `maxBoundsByPV` (`Versions.hs:389-399`): the number of tags of a constant's type
    * ([[DefaultUni.size]]) and of fields of a `constr`. Before protocol 11 neither is bounded (the
    * reference's `maxBound`, which no script reaches).
    */
  final case class MaxBounds(typeTags: Option[Int], constrFields: Option[Int])

  def maxBounds(protocol: ProtocolMajor): MaxBounds =
    if protocol.value >= ProtocolMajor.VanRossem.value then MaxBounds(Some(32), Some(1024))
    else MaxBounds(None, None)

  /** Why a script is refused before it runs. */
  enum Refusal:
    /** `LedgerLanguageNotAvailableError` (`SerialisedScript.hs:256-259`). */
    case LanguageNotAvailable(
        language: PlutusLanguage,
        introducedIn: ProtocolMajor,
        protocol: ProtocolMajor
    )

    /** `checkBuiltin` (`SerialisedScript.hs:216-225`). */
    case BuiltinNotAvailable(fun: DefaultFun, language: PlutusLanguage, protocol: ProtocolMajor)

    /** `checkConstant` (`SerialisedScript.hs:207-214`): a constant's type has more tags than
      * allowed.
      */
    case ConstantTypeTooLarge(typeTags: Int, bound: Int, protocol: ProtocolMajor)

    /** `checkConstr` (`SerialisedScript.hs:227-234`). */
    case ConstrTooManyFields(fields: Int, bound: Int, protocol: ProtocolMajor)

    /** `constr` in a program older than 1.1.0 (`Flat.hs:167-171`). */
    case ConstrBeforeVersion(version: ProgramVersion)

    /** `case` in a program older than 1.1.0 (`Flat.hs:181-184`). */
    case CaseBeforeVersion(version: ProgramVersion)

    /** `PlutusCoreLanguageNotAvailableError` (`Eval.hs:124-127`), a phase-2 failure. */
    case ProgramVersionNotAvailable(
        version: ProgramVersion,
        language: PlutusLanguage,
        protocol: ProtocolMajor
    )

  def checkLanguage(language: PlutusLanguage, protocol: ProtocolMajor): Either[Refusal, Unit] =
    val introduced = languageIntroducedIn(language)
    Either.cond(
      introduced.value <= protocol.value,
      (),
      Refusal.LanguageNotAvailable(language, introduced, protocol)
    )

  def checkBuiltin(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      fun: DefaultFun
  ): Either[Refusal, Unit] =
    Either.cond(
      allows(language, protocol, fun),
      (),
      Refusal.BuiltinNotAvailable(fun, language, protocol)
    )

  def checkConstantType(protocol: ProtocolMajor, uni: DefaultUni): Either[Refusal, Unit] =
    maxBounds(protocol).typeTags.filter(bound => uni.size > bound) match
      case Some(bound) => Left(Refusal.ConstantTypeTooLarge(uni.size, bound, protocol))
      case None        => Right(())

  def checkConstrFields(protocol: ProtocolMajor, fields: Int): Either[Refusal, Unit] =
    maxBounds(protocol).constrFields.filter(bound => fields > bound) match
      case Some(bound) => Left(Refusal.ConstrTooManyFields(fields, bound, protocol))
      case None        => Right(())

  def checkConstr(version: ProgramVersion): Either[Refusal, Unit] =
    Either.cond(version.allowsConstrAndCase, (), Refusal.ConstrBeforeVersion(version))

  def checkCase(version: ProgramVersion): Either[Refusal, Unit] =
    Either.cond(version.allowsConstrAndCase, (), Refusal.CaseBeforeVersion(version))

  def checkProgramVersion(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      version: ProgramVersion
  ): Either[Refusal, Unit] =
    Either.cond(
      programVersions(language, protocol).contains(version),
      (),
      Refusal.ProgramVersionNotAvailable(version, language, protocol)
    )
