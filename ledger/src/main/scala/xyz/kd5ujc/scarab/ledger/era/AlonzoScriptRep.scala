// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{ByteStrings, CborRead, DecodeField, LedgerDecodeError, ScriptKind}

/** The script the reference shares from Alonzo on (`data AlonzoScript era = NativeScript … |
  * PlutusScript …`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:473-475`): a native script `N` or a
  * Plutus script `P`, both the era's own types. Each era's script is an opaque type over it
  * ([[babbage.BabbageScript]], [[conway.ConwayScript]]).
  */
enum AlonzoScriptRep[+N, +P]:
  case NativeScript(script: N)
  case PlutusScript(script: P)

object AlonzoScriptRep:
  given [N, P](using
      CanEqual[N, N],
      CanEqual[P, P]
  ): CanEqual[
    AlonzoScriptRep[N, P],
    AlonzoScriptRep[N, P]
  ] = CanEqual.derived

  /** `script = [0, native_script] / [1, plutus_v1] / [2, plutus_v2] / [3, plutus_v3]`, of a
    * language no newer than `newest` (`decodeAlonzoScript`, `Alonzo/Scripts.hs:705-720`), as the
    * flat decoder reads it ([[xyz.kd5ujc.scarab.ledger.Script.decode]]): the same checks in the
    * same order, so the same bytes are refused with the same error.
    *
    * @param native
    *   the era's native-script decoder, for `[0, native_script]`
    * @param plutus
    *   the era's Plutus script of a language, if the era has the language: exactly the languages no
    *   newer than `newest`, so a language it has not is refused as the flat decoder refuses it
    */
  def decode[N, P](n: Node, newest: ScriptKind)(
      native: Node => Either[LedgerDecodeError, N],
      plutus: ScriptKind => Option[Bytes => P]
  )(using ByteStrings): Either[LedgerDecodeError, AlonzoScriptRep[N, P]] =
    def plutusOf(kind: ScriptKind, field: DecodeField, bytes: Node) =
      plutus(kind)
        .toRight(LedgerDecodeError.UnsupportedLanguage(kind, newest))
        .flatMap(make => CborRead.bytes(bytes, field).map(b => PlutusScript(make(b))))
    CborRead.arrayN(n, 2, DecodeField.Script).flatMap { xs =>
      CborRead.uint(xs(0), DecodeField.ScriptType).flatMap {
        case t if t == 0 => native(xs(1)).map(NativeScript(_))
        case t if t == 1 => plutusOf(ScriptKind.PlutusV1, DecodeField.PlutusV1, xs(1))
        case t if t == 2 => plutusOf(ScriptKind.PlutusV2, DecodeField.PlutusV2, xs(1))
        case t if t == 3 => plutusOf(ScriptKind.PlutusV3, DecodeField.PlutusV3, xs(1))
        case t           => Left(LedgerDecodeError.UnknownTag(DecodeField.ScriptType, t))
      }
    }
