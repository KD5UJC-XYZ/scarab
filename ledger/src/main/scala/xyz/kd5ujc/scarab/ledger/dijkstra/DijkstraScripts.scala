// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, TreeFold}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Credential, DecodeField, LedgerDecodeError}

import V12.{Result, given}

/** A Plutus language of the Dijkstra era, with the tag that prefixes its scripts' hashes
  * (`plutusLanguageTag`) and the protocol version from which a block may carry it (`guardPlutus`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:643-651`, cardano-ledger
  * 9df9f98c): every one is allowed at version 12, Plutus V4 from 12 on.
  */
enum DijkstraLanguage(val hashTag: Int):
  case PlutusV1 extends DijkstraLanguage(1)
  case PlutusV2 extends DijkstraLanguage(2)
  case PlutusV3 extends DijkstraLanguage(3)
  case PlutusV4 extends DijkstraLanguage(4)

/** A script of the Dijkstra era, with the bytes its hash is taken over. */
enum DijkstraScript:
  /** A native script, kept as the bytes it was read from (`DijkstraNativeScript` memoizes them). */
  case Native(raw: Bytes)

  /** A Plutus script: its language and the bytes of the program (`PlutusBinary`, the content of the
    * byte string, its chunks joined).
    */
  case Plutus(language: DijkstraLanguage, code: Bytes)

  /** `hashScript`: Blake2b-224 of the language's prefix and the script's original bytes
    * (`alonzoScriptPrefixTag`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:574-580`).
    */
  def hash: Bytes = this match
    case Native(raw)        => Blake2b.hash224.hash(Bytes.wrap(Array(0.toByte)) ++ raw)
    case Plutus(lang, code) => Blake2b.hash224.hash(Bytes.wrap(Array(lang.hashTag.toByte)) ++ code)

object DijkstraScript:
  /** `DijkstraNativeScriptRaw`
    * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:295-319`, 9df9f98c):
    * `decodeRecordSum` of a signature `[0, keyhash]`, all of `[1, [* script]]`, any of
    * `[2, [* script]]`, `[3, int, [* script]]`, a validity start `[4, slot]` or end `[5, slot]`,
    * and a guard `[6, credential]`; the nested scripts are read by `decodeStrictSeq`, a definite or
    * an indefinite list. `m` is an `Int`. Nesting is unbounded, so the script is read on the heap
    * ([[TreeFold]], ADR 0011). The script is kept as its original bytes, which its hash is over.
    */
  def native(n: Node): Result[DijkstraScript] =
    def leaf(read: Result[?]): Result[Vector[Node]] = read.as(Vector.empty)
    TreeFold
      .foldE[Node, Unit, LedgerDecodeError](n) { node =>
        // each branch gives the nested scripts, read after the script's own fields
        V12
          .sum[Vector[Node]](node, DecodeField.NativeScript) {
            case t if t == 0 => (2, xs => leaf(V12.bytesN(xs(1), 28, DecodeField.ScriptKeyHash)))
            case t if t == 1 || t == 2 => (2, xs => V12.list(xs(1), DecodeField.Scripts))
            case t if t == 3 =>
              (
                3,
                xs => V12.int64(xs(1), DecodeField.ScriptN) *> V12.list(xs(2), DecodeField.Scripts)
              )
            case t if t == 4 => (2, xs => leaf(V12.word64(xs(1), DecodeField.InvalidBefore)))
            case t if t == 5 => (2, xs => leaf(V12.word64(xs(1), DecodeField.InvalidHereafter)))
            case t if t == 6 => (2, xs => leaf(Credential.decode(xs(1), DecodeField.ScriptKeyHash)))
          }
          .map(children =>
            if children.isEmpty then TreeFold.Step.Leaf(())
            else TreeFold.Step.Branch(children, _ => Right(()))
          )
      }
      .as(Native(n.original))

  /** A Plutus script of `language` as a witness set or auxiliary data holds it:
    * `decodePlutusScript` reads `PlutusBinary`, a byte string (`decodeByteArray`, chunked from
    * version 12), and `mkPlutusScript` takes every Dijkstra language (`Alonzo/Scripts.hs:695-702`,
    * `Dijkstra/Scripts.hs:487-492`). The program is not deserialised when a block is decoded.
    */
  def plutus(n: Node, language: DijkstraLanguage): Result[DijkstraScript] =
    V12.bytes(n, DecodeField.PlutusScript).map(code => Plutus(language, code))

  /** `AlonzoScript` from version 12 (`Alonzo/Scripts.hs:727-764`): `decodeRecordSum` of a native
    * script `[0, script]` or a Plutus script `[1..4, bytes]` of PlutusV1 to PlutusV4. A reference
    * script of an output is one (`decodeCIC "Script"`).
    */
  def script(n: Node): Result[DijkstraScript] =
    V12.sum(n, DecodeField.Script) {
      case t if t == 0 => (2, xs => native(xs(1)))
      case t if t == 1 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV1))
      case t if t == 2 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV2))
      case t if t == 3 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV3))
      case t if t == 4 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV4))
    }
end DijkstraScript
