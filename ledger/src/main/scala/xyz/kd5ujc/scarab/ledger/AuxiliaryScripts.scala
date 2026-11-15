// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.crypto.Blake2b

/** An original-byte view of auxiliary scripts, never added to the transaction's script witnesses.
  * cardano-ledger f649f975, Alonzo/TxAuxData.hs:220–306,369–373; Metadata.hs:153–222.
  */
private[ledger] object AuxiliaryScripts:
  final case class Plutus private[AuxiliaryScripts] (
      language: PlutusLanguage,
      code: Bytes,
      hash: Bytes
  )

  /** Metadata.hs:153-222: the 64-byte bound is checked when the decoder version is above 2. */
  private def metadatum(root: Node, bounded: Boolean): Either[LedgerDecodeError, Unit] =
    Metadatum.read(root, bounded).void

  private def metadata(node: Node, protocol: BigInt): Either[LedgerDecodeError, Unit] =
    metadata(node, protocol, protocol > 2)

  private def metadata(
      node: Node,
      protocol: BigInt,
      bounded: Boolean
  ): Either[LedgerDecodeError, Unit] =
    CborRead.map(node, DecodeField.Metadata).flatMap { pairs =>
      pairs
        .traverse { case (label, value) =>
          CborRead.uint(label, DecodeField.MetadataLabel).flatTap(_ => metadatum(value, bounded))
        }
        .flatMap(labels =>
          Either.cond(
            protocol < 9 || labels.distinct.size == labels.size,
            (),
            LedgerDecodeError.DuplicateKey(DecodeField.MetadataLabels)
          )
        )
    }

  /** The native scripts of auxiliary data, which holds scripts from Allegra on: time locks. */
  private def native(node: Node)(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    CborRead
      .array(node, DecodeField.AuxiliaryNativeScripts)
      .flatMap(_.traverse_(Script.native(_, NativeScript.Form.Timelock)))
      .as(Vector.empty)

  /** Plutus scripts of `language`, allowed from protocol `minimum` (`guardPlutus`,
    * Plutus/Language.hs:639-647, cardano-ledger f649f975). Hashes use the language prefix 1 to 4
    * (`hashPlutusScript`, Plutus/Language.hs:200-204,521,541,561,582 at that pin).
    */
  private def plutus(
      language: PlutusLanguage,
      minimum: Int,
      protocol: BigInt,
      node: Node
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    if protocol < minimum then
      Left(
        LedgerDecodeError
          .NumberOutOfRange(DecodeField.AuxiliaryScriptProtocol, protocol, minimum, 11)
      )
    else
      CborRead
        .array(node, DecodeField.AuxiliaryPlutusScripts)
        .flatMap(
          _.traverse(
            CborRead
              .bytes(_, DecodeField.AuxiliaryPlutusScript)
              .map(code =>
                Plutus(
                  language,
                  code,
                  Blake2b.hash224.hash(Bytes((language.id + 1).toByte +: code.value))
                )
              )
          )
        )

  /** Shelley (decoder version 2) reads only a metadata map; Allegra and Mary (3, 4) also read the
    * definite two-element `[metadata, native scripts]` list. Tag 259 and the Plutus fields are
    * Alonzo's: Shelley/TxAuxData.hs, Allegra/TxAuxData.hs:185-210 at f649f975 (`RecD`: a definite
    * list of two, checked before its fields, [[CborRead.record]]). The era's decoder version is
    * `eraProtVerLow` (Core/Internal/Definition/Era.hs 95-109).
    */
  private def historical(
      node: Node,
      protocol: BigInt
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    val lists = protocol >= 3
    node.value match
      case _: Value.Map          => metadata(node, protocol).as(Vector.empty)
      case _: Value.Arr if lists => record(node, protocol)
      case other =>
        Left(
          LedgerDecodeError.Expected(
            DecodeField.AuxiliaryData,
            if lists then LedgerDecodeError.Shape.ArrayOrMap else LedgerDecodeError.Shape.Map,
            other
          )
        )

  /** The auxiliary list: `RecD` before 12, `decodeRecordNamed` from 12
    * (`Alonzo/TxAuxData.hs:245-257`, cardano-ledger f649f975).
    */
  private def record(
      node: Node,
      protocol: BigInt
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    val fields =
      if protocol >= 12 then CborRead.arrayN(node, 2, DecodeField.AuxiliaryData)
      else CborRead.record(node, 2, DecodeField.AuxiliaryData)
    fields
      .flatMap(xs => metadata(xs(0), protocol) *> native(xs(1)))

  def read(bytes: Bytes, protocol: BigInt)(using
      ByteStrings
  ): Either[LedgerDecodeError, Vector[Plutus]] =
    DetachedRecords.read(
      bytes,
      Cbor.Limits.Unbounded,
      DetachedRecords.Shapes.auxiliaryDataAt(protocol)
    )(LedgerDecodeError.Syntax(_))(node =>
      if protocol < 5 then historical(node, protocol) else alonzoOn(node, protocol)
    )

  /** From Alonzo (decoder version 5) the token type picks the form (`decodeTxAuxDataByTokenType`,
    * Alonzo/TxAuxData.hs:302-312 at f649f975): a map is metadata; a list, definite or not, is the
    * `[metadata, native scripts]` record that `decodeAllegra` reads with `RecD` below decoder
    * version 12 (245-251), so only a definite list of two is one ([[CborRead.record]]); tag 259 is
    * the keyed form.
    */
  private def alonzoOn(
      node: Node,
      protocol: BigInt
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    node.value match
      case _: Value.Map => metadata(node, protocol).as(Vector.empty)
      case _: Value.Arr => record(node, protocol)
      case Value.Tag(tag, contents) if tag == 259 =>
        CborRead.map(contents, DecodeField.AuxiliaryData).flatMap { fields =>
          fields
            .traverse { case (key, value) =>
              CborRead.uint(key, DecodeField.AuxiliaryField).map(_ -> value)
            }
            .flatMap { keyed =>
              if keyed.map(_._1).distinct.size != keyed.size then
                Left(LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData))
              else
                keyed
                  .traverse {
                    case (n, value) if n == 0 =>
                      metadata(value, protocol).as(Vector.empty[Plutus])
                    case (n, value) if n == 1 => native(value)
                    case (n, value) if n == 2 =>
                      plutus(PlutusLanguage.V1, 5, protocol, value)
                    case (n, value) if n == 3 =>
                      plutus(PlutusLanguage.V2, 7, protocol, value)
                    case (n, value) if n == 4 =>
                      plutus(PlutusLanguage.V3, 9, protocol, value)
                    // `decoderByKey` and `guardPlutus` admit V4 from 12
                    // (Alonzo/TxAuxData.hs:280-286; Plutus/Language.hs:639-647, f649f975).
                    case (n, value) if n == 5 && protocol >= 12 =>
                      plutus(PlutusLanguage.V4, 12, protocol, value)
                    case (n, _) => Left(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryField, n))
                  }
                  .map(_.flatten)
            }
        }
      case _ =>
        Left(
          LedgerDecodeError
            .Expected(DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap, node.value)
        )
