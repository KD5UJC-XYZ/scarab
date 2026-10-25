// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, TreeFold, Value}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

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
    TreeFold.foldE[Node, Unit, LedgerDecodeError](root) { node =>
      import TreeFold.Step
      def length(actual: Int): Either[LedgerDecodeError, Step[Node, Unit, LedgerDecodeError]] =
        Either.cond(
          !bounded || actual <= 64,
          Step.Leaf(()),
          LedgerDecodeError.NumberOutOfRange(DecodeField.MetadataByteLength, actual, 0, 64)
        )
      node.value match
        case _: Value.UInt | _: Value.NInt => Right(Step.Leaf(()))
        case Value.ByteString(bytes)       => length(bytes.size)
        case Value.Text(text) =>
          length(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        case Value.Arr(children) => Right(Step.Branch(children, _ => Right(())))
        case Value.Map(pairs)    => Right(Step.Branch(TreeFold.flatten(pairs), _ => Right(())))
        case other =>
          Left(
            LedgerDecodeError.Expected(
              DecodeField.MetadataValue,
              LedgerDecodeError.Shape.Metadatum,
              other
            )
          )
    }

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

  private def native(node: Node)(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    CborRead
      .array(node, DecodeField.AuxiliaryNativeScripts)
      .flatMap(_.traverse_(Script.native))
      .as(Vector.empty)

  private def plutus(
      language: PlutusLanguage,
      protocol: BigInt,
      node: Node
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    val (kind, minimum) = language match
      case PlutusLanguage.V1 => (ScriptKind.PlutusV1, 5)
      case PlutusLanguage.V2 => (ScriptKind.PlutusV2, 7)
      case PlutusLanguage.V3 => (ScriptKind.PlutusV3, 9)
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
              .map(code => Plutus(language, code, Script.plutus(kind, code).hash))
          )
        )

  /** Shelley (decoder version 2) reads only a metadata map; Allegra and Mary (3, 4) also read the
    * definite two-element `[metadata, native scripts]` list. Tag 259 and the Plutus fields are
    * Alonzo's: Shelley/TxAuxData.hs, Allegra/TxAuxData.hs:185-210 at f649f975 (`RecD` needs a
    * definite list). The era's decoder version is `eraProtVerLow` (Core/Internal/Definition/Era.hs
    * 95-109).
    */
  private def historical(
      node: Node,
      protocol: BigInt
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    val lists = protocol >= 3
    node.value match
      case _: Value.Map => metadata(node, protocol).as(Vector.empty)
      case Value.Arr(Vector(data, scripts)) if lists =>
        if node.original.value.headOption.contains(0x9f.toByte) then
          Left(LedgerDecodeError.IndefiniteLength(DecodeField.AuxiliaryData))
        else metadata(data, protocol) *> native(scripts)
      case other =>
        Left(
          LedgerDecodeError.Expected(
            DecodeField.AuxiliaryData,
            if lists then LedgerDecodeError.Shape.ArrayOrMap else LedgerDecodeError.Shape.Map,
            other
          )
        )

  def read(bytes: Bytes, protocol: BigInt)(using
      ByteStrings
  ): Either[LedgerDecodeError, Vector[Plutus]] =
    CborRead
      .decode(bytes)
      .flatMap(node =>
        if protocol < 5 then historical(node, protocol) else alonzoOn(node, protocol)
      )

  private def alonzoOn(
      node: Node,
      protocol: BigInt
  )(using ByteStrings): Either[LedgerDecodeError, Vector[Plutus]] =
    node.value match
      case _: Value.Map                     => metadata(node, protocol).as(Vector.empty)
      case Value.Arr(Vector(data, scripts)) => metadata(data, protocol) *> native(scripts)
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
                    case (n, value) if n == 2 => plutus(PlutusLanguage.V1, protocol, value)
                    case (n, value) if n == 3 => plutus(PlutusLanguage.V2, protocol, value)
                    case (n, value) if n == 4 => plutus(PlutusLanguage.V3, protocol, value)
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
