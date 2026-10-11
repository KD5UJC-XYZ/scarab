// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import scalus.uplc.DefaultFun
import scalus.uplc.DefaultFun.*

/** What a Plutus script may use in each (ledger language, protocol major version): the builtin
  * functions and the UPLC program versions the reference ledger accepts. It transcribes
  * `PlutusLedgerApi.Common.Versions` of plutus-ledger-api 1.70.0.0 (`builtinsIntroducedIn`,
  * `plcVersionsIntroducedIn`, `ledgerLanguageIntroducedIn`); the tables of 1.68.0.0 and the default
  * branch are identical. Batches that were deployed never change, new builtins are a new batch.
  *
  * The ledger applies the builtin table when it deserialises a script (`deserialiseScript`): a
  * script that uses a builtin outside its table does not decode. From Babbage that is a phase-1
  * failure for the scripts of the witness set and of the outputs (`MalformedScriptWitnesses`,
  * `MalformedReferenceScripts`); in Alonzo an undecodable script that a transaction needs fails
  * phase 2. The program version is checked when the script is evaluated (`mkTermToEvaluate`).
  */
object PlutusAvailability:
  // The batches as plutus-ledger-api defines them; the order within a batch is the reference's.
  val batch1: Vector[DefaultFun] = Vector(
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

  val batch2: Vector[DefaultFun] = Vector(SerialiseData)

  val batch3: Vector[DefaultFun] =
    Vector(VerifyEcdsaSecp256k1Signature, VerifySchnorrSecp256k1Signature)

  /** Batch 4 without `IntegerToByteString` and `ByteStringToInteger`. */
  val batch4a: Vector[DefaultFun] = Vector(
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

  val batch4b: Vector[DefaultFun] = Vector(IntegerToByteString, ByteStringToInteger)

  val batch5: Vector[DefaultFun] = Vector(
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

  val batch6: Vector[DefaultFun] = Vector(
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

  /** The protocol major versions the table names: Alonzo, Vasil, Valentine, Chang, Plomin and van
    * Rossem.
    */
  val Alonzo = 5
  val Vasil = 7
  val Valentine = 8
  val Chang = 9
  val Plomin = 10
  val VanRossem = 11

  /** The builtins a language gained, by the protocol major version that introduced them. */
  def introducedIn(language: PlutusLanguage): Map[Int, Set[DefaultFun]] = language match
    case PlutusLanguage.V1 =>
      Map(
        Alonzo -> batch1.toSet,
        VanRossem -> (batch2 ++ batch3 ++ batch4a ++ batch4b ++ batch5 ++ batch6).toSet
      )
    case PlutusLanguage.V2 =>
      Map(
        Vasil -> (batch1 ++ batch2).toSet,
        Valentine -> batch3.toSet,
        Plomin -> batch4b.toSet,
        VanRossem -> (batch4a ++ batch5 ++ batch6).toSet
      )
    case PlutusLanguage.V3 =>
      Map(
        Chang -> (batch1 ++ batch2 ++ batch3 ++ batch4a ++ batch4b).toSet,
        Plomin -> batch5.toSet,
        VanRossem -> batch6.toSet
      )

  /** The builtins `language` may use at protocol major version `protocolMajor`. */
  def builtinsAvailableIn(language: PlutusLanguage, protocolMajor: Int): Set[DefaultFun] =
    introducedIn(language).collect { case (pv, fs) if pv <= protocolMajor => fs }.flatten.toSet

  /** The protocol major version a ledger language first exists in. */
  def languageIntroducedIn(language: PlutusLanguage): Int = language match
    case PlutusLanguage.V1 => Alonzo
    case PlutusLanguage.V2 => Vasil
    case PlutusLanguage.V3 => Chang

  /** UPLC program versions by the protocol major version that introduced them (`1.1.0` adds
    * `constr` and `case`). Version `1.2.0`, introduced for Dijkstra on the plutus default branch,
    * is in no released table the reference node 11.1.3 can contain, so it is never available.
    */
  private val V100 = (1, 0, 0)
  private val V110 = (1, 1, 0)

  def programVersionsIntroducedIn(language: PlutusLanguage): Map[Int, Set[(Int, Int, Int)]] =
    language match
      case PlutusLanguage.V1 => Map(Alonzo -> Set(V100), VanRossem -> Set(V110))
      case PlutusLanguage.V2 => Map(Vasil -> Set(V100), VanRossem -> Set(V110))
      case PlutusLanguage.V3 => Map(Chang -> Set(V100, V110))

  def programVersionsAvailableIn(
      language: PlutusLanguage,
      protocolMajor: Int
  ): Set[(Int, Int, Int)] =
    programVersionsIntroducedIn(language)
      .collect { case (pv, vs) if pv <= protocolMajor => vs }
      .flatten
      .toSet

  /** Why a script with these builtins cannot be deserialised, as the reference words it. */
  def decodeFailure(
      language: PlutusLanguage,
      protocolMajor: Int,
      builtins: Set[DefaultFun]
  ): Option[String] =
    if protocolMajor < languageIntroducedIn(language) then
      Some(s"$language is not available in protocol version $protocolMajor")
    else
      val available = builtinsAvailableIn(language, protocolMajor)
      builtins.filterNot(available).toVector.sortBy(_.ordinal).headOption.map { f =>
        s"Builtin function $f is not available in language $language at and protocol version $protocolMajor"
      }

  /** Why a program of this UPLC version cannot be evaluated, if it cannot. */
  def versionFailure(
      language: PlutusLanguage,
      protocolMajor: Int,
      version: (Int, Int, Int)
  ): Option[String] =
    if programVersionsAvailableIn(language, protocolMajor).contains(version) then None
    else
      Some(
        s"Plutus Core version ${version._1}.${version._2}.${version._3} is not available in " +
          s"$language at protocol version $protocolMajor"
      )
