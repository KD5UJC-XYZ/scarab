// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, LedgerDecodeError, TxIn, TxOut, Utxo, Value}
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Empty, Shelley, ShelleyBasedEra, UtxoTables}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, HaskellStateCodecs}

/** The Shelley new-epoch state's stashed redeem (AVVM) outputs in the state file (#1448): written
  * whole, as `EncCBOR (UTxO era)` writes the last field of `NewEpochState` (cardano-ledger f649f975
  * `Shelley/LedgerState/Types.hs:388-405`, `State/UTxO.hs:112`), read as `DecCBOR (ShelleyTxOut
  * era)` reads an output (`Shelley/TxOut.hs:156-160`), and the empty stash still the empty map, so
  * a snapshot without one keeps its bytes. The ledger state's own UTxO stays the empty map whatever
  * it holds: its entries are in the tables file.
  */
class ShelleyAvvmStashSuite extends FunSuite:
  private val codec = HaskellStateCodecs.stashedAvvm[Shelley](using ShelleyBasedEra.SShelley)

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def read[A](c: Codec[A], bytes: Bytes): Either[DecodeError, A] =
    c.decodeBytes(bytes, HaskellState.limits)

  private val id = hex("09" * 32)
  private val raw = hex("61" + "05" * 28)
  private val address = Address.decode(raw).fold(e => fail(e.toString), identity)
  private val in = TxIn(id, 3)
  private val out = TxOut(address, Value(BigInt("318200635000000"), Map.empty))

  test("a stash is written as {[transaction id, index]: [address, coin]} and read back") {
    val bytes = hex(s"a1 82 5820${id.hex} 03 82 581d${raw.hex} 1b000121 66dbd104c0")
    assertEquals(codec.encodeUnchecked(UtxoTables(Utxo.fromMap(Map(in -> out)))), bytes)
    val back = read(codec, bytes).fold(e => fail(e.render), _.utxo.toMap)
    assertEquals(back.keySet, Set(in))
    assertEquals(back.get(in).map(o => (o.address.raw, o.value)), Some((raw, out.value)))
  }

  test("an empty stash is the empty map, and a later era's a null, as before") {
    assertEquals(
      codec.encodeUnchecked(Empty.stashedAvvm[Shelley](using ShelleyBasedEra.SShelley)).hex,
      "a0"
    )
    assertEquals(
      HaskellStateCodecs
        .stashedAvvm[Allegra](using ShelleyBasedEra.SAllegra)
        .encodeUnchecked(())
        .hex,
      "f6"
    )
  }

  test("the ledger state's own UTxO is still written as the empty map, its entries in the tables") {
    assertEquals(
      HaskellStateCodecs.utxoTables.encodeUnchecked(UtxoTables(Utxo.fromMap(Map(in -> out)))).hex,
      "a0"
    )
  }

  test("a stashed output is a coin-only pair: a value with assets or a datum hash is refused") {
    val withAssets =
      hex(s"a1 82 5820${id.hex} 03 82 581d${raw.hex} 82 01 a1 581c${"07" * 28} a1 40 01")
    assert(read(codec, withAssets) match {
      case Left(
            DecodeError(DecodeError.Kind.Domain(_: LedgerDecodeError.MultiAssetNotAllowed), _, _, _)
          ) =>
        true
      case _ => false
    })
    val withHash = hex(s"a1 82 5820${id.hex} 03 83 581d${raw.hex} 01 5820${"08" * 32}")
    assert(read(codec, withHash) match {
      case Left(DecodeError(DecodeError.Kind.Domain(_: LedgerDecodeError.OutputForm), _, _, _)) =>
        true
      case _ => false
    })
  }
