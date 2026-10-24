// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage, ScalusEvaluator}
import munit.FunSuite
import scalus.cardano.ledger as sl
import scalus.cardano.ledger.LedgerToPlutusTranslation as Oracle
import scalus.cardano.onchain.plutus.{v1, v2, v3}
import scalus.serialization.cbor.Cbor as ScalusCbor
import scalus.uplc.builtin.{ByteString, Data}
import scalus.uplc.builtin.Data.toData as dataOf

/** Cross-check: the script contexts this node builds from recorded Preview transactions equal the
  * contexts of Scalus's own ledger-to-Plutus translation, byte for byte. Scalus is only the oracle
  * here; the node never calls it for contexts.
  */
class ScriptContextOracleSuite extends FunSuite:
  private def byteString(b: Bytes): ByteString = ByteString.fromArray(b.toArray)

  private def scalusTx(c: PlutusCase): sl.Transaction =
    val t = c.tx
    val bytes =
      Vector(0x84.toByte) ++ t.body.raw.value ++ t.witnesses.raw.get.value ++
        Vector((if t.isValid then 0xf5 else 0xf4).toByte) ++
        t.auxiliary.map(_.value).getOrElse(Vector(0xf6.toByte))
    sl.Transaction.fromCbor(bytes.toArray)(using sl.ProtocolVersion(c.protocolMajor, 0))

  private def scalusInput(in: TxIn): sl.TransactionInput =
    sl.TransactionInput(sl.TransactionHash.fromByteString(byteString(in.id)), in.index.toInt)

  private def scalusUtxos(c: PlutusCase): sl.Utxos =
    (c.spent ++ c.references).map { case (in, out) =>
      scalusInput(in) -> ScalusCbor.decode[sl.TransactionOutput](out.raw.toArray)
    }

  private def slotConfig(c: PlutusCase): sl.SlotConfig =
    c.clock.headOption.fold(sl.SlotConfig.preview) { case (slot, ms) =>
      sl.SlotConfig(ms.toLong - slot.toLong * 1000, 0, 1000)
    }

  private def hex(d: Data): String =
    d.toCbor.map(b => f"${b & 0xff}%02x").mkString

  private def language(c: PlutusCase, p: Purpose): PlutusLanguage =
    val hash = p.scriptHash.get
    val kind = c.tx.witnesses.scriptsByHash
      .get(hash)
      .orElse(
        (c.spent.values ++ c.references.values).flatMap(_.referenceScript).find(_.hash == hash)
      )
      .get
      .kind
    kind match
      case ScriptKind.PlutusV1 => PlutusLanguage.V1
      case ScriptKind.PlutusV2 => PlutusLanguage.V2
      case _                   => PlutusLanguage.V3

  PlutusCase.all.foreach { c =>
    test(s"${c.name} (${c.kind}): contexts equal Scalus's translation") {
      // Scalus translates for protocol 10: its open-lower validity interval has an exclusive upper
      // bound, which the ledger had only from protocol 9 (see ScriptContextGoldenSuite for the
      // earlier, inclusive form). Everything else shown here is protocol independent; what a V1
      // context refuses per era is not Scalus's to say (ScriptContextV1EraSuite, V1ContextSuite).
      val env = ScriptContexts.Environment(10, c.spent, c.references, c.posix)
      val tx = scalusTx(c)
      val utxos = scalusUtxos(c)
      val pv = sl.MajorProtocolVersion(c.protocolMajor)
      val cfg = slotConfig(c)
      c.tx.witnesses.redeemers.foreach { r =>
        val purpose = Purpose.resolve(c.tx.body, c.spent, r.tag, r.index).get
        val lang = language(c, purpose)
        val ours = for
          info <- ScriptContexts.txInfo(lang, c.tx, env)
          args <- ScriptContexts.arguments(lang, info, c.tx, r, purpose)
        yield ScalusEvaluator.toData(args.last)
        val theirs: Data =
          val sr = sl.Redeemer(
            sl.RedeemerTag.fromOrdinal(r.tag),
            r.index.toInt,
            ScalusEvaluator.toData(PlutusData.decode(r.data).toOption.get),
            sl.ExUnits(r.exUnits.mem.toLong, r.exUnits.steps.toLong)
          )
          lang match
            case PlutusLanguage.V1 =>
              dataOf(Oracle.getScriptContextV1(sr, tx, utxos, cfg, pv))
            case PlutusLanguage.V2 =>
              dataOf(Oracle.getScriptContextV2(sr, tx, utxos, cfg, pv))
            case PlutusLanguage.V3 =>
              val datum = purpose match
                case Purpose.Spending(_, _, out) =>
                  out.datum match
                    case Datum.Inline(d) => PlutusData.decode(d).toOption
                    case Datum.Hash(h) =>
                      c.tx.witnesses.datums
                        .find(d => xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(d) == h)
                        .flatMap(PlutusData.decode(_).toOption)
                    case _ => None
                case _ => None
              dataOf(
                Oracle.getScriptContextV3(sr, datum.map(ScalusEvaluator.toData), tx, utxos, cfg, pv)
              )
        assertEquals(
          ours.map(hex),
          Right(hex(theirs)),
          s"redeemer ${r.tag}.${r.index}"
        )
      }
    }
  }
