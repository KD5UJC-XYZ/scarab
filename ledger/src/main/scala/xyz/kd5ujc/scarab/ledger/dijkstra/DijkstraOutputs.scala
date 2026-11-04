// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Address, CborRead, DecodeField, DecodeSite, LedgerDecodeError}
import xyz.kd5ujc.scarab.plutus.PlutusData

import V12.Result

/** A transaction input: the id of the transaction and the index of its output. */
final case class DijkstraTxIn(id: Bytes, index: Int)

object DijkstraTxIn:
  /** `decodeRecordNamed "TxIn" (const 2)` of a `TxId`, 32 bytes, and a `TxIx`, a `Word16`
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/TxIn.hs:130-135`, cardano-ledger 9df9f98c).
    */
  def decode(n: Node): Result[DijkstraTxIn] =
    V12
      .record(n, 2, DecodeField.Input)
      .flatMap(xs =>
        (V12.bytesN(xs(0), 32, DecodeField.InputId), V12.word16(xs(1), DecodeField.InputIndex))
          .mapN(DijkstraTxIn(_, _))
      )

  /** `Set TxIn` (`decodeSet` from version 9): the tag 258 allowed, no input twice. */
  def set(n: Node, what: DecodeSite): Result[Vector[DijkstraTxIn]] =
    V12.set(n, what)(decode)(identity)

  def nonEmptySet(n: Node, what: DecodeSite): Result[Vector[DijkstraTxIn]] =
    V12.nonEmptySet(n, what)(decode)(identity)

/** A multi-asset map: per policy, per asset name, the quantity; a policy and a name are bytes. */
type MultiAsset = Map[Bytes, Map[Bytes, BigInt]]

object MultiAsset:
  /** `decodeMultiAsset` from version 12 (`eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs:325-350`,
    * 9df9f98c): a non-empty map of policies, each a script hash, to non-empty maps of asset names
    * (at most 32 bytes, `Value.hs:129-138`) to non-zero quantities read by `quantity`, no key
    * twice; then `isMultiAssetSmallEnough` (`Value.hs:743-745`).
    */
  def decode(n: Node, what: DecodeSite)(quantity: Node => Result[BigInt]): Result[MultiAsset] =
    for
      policies <- V12.nonEmptyMap(n, what)(V12.bytesN(_, 28, what / DecodeSite.Qualifier.Policy))(
        (_, assets) =>
          V12.nonEmptyMap(assets, what / DecodeSite.Qualifier.Assets)(assetName(_, what))((_, q) =>
            quantity(q).flatMap(v => Either.cond(v != 0, v, LedgerDecodeError.ZeroQuantity(what)))
          )
      )
      count = policies.map(_._2.size).sum
      _ <- Either.cond(
        44 * count + 28 * policies.size <= 65535,
        (),
        LedgerDecodeError.MultiAssetTooBig(what, count, policies.size)
      )
    yield policies.map((p, as) => p -> as.toMap).toMap

  private def assetName(n: Node, what: DecodeSite): Result[Bytes] =
    V12
      .bytes(n, what / DecodeSite.Qualifier.AssetName)
      .flatMap(b => Either.cond(b.size <= 32, b, LedgerDecodeError.AssetNameLength(what, b.size)))

  /** A quantity of a mint (`decodeIntegerBounded64`, `Value.hs:368-392`): an integer item, not a
    * bignum, in the range of `Int64`.
    */
  def minted(n: Node): Result[BigInt] = V12.int64(n, DecodeField.Mint).map(BigInt(_))

  /** A quantity of an output (`decodeWord64`). */
  def held(n: Node): Result[BigInt] = V12.word64(n, DecodeField.OutputValue)

/** The value of an output: lovelace and assets. */
final case class DijkstraValue(coin: BigInt, assets: MultiAsset)

object DijkstraValue:
  /** `decodeMaryValue` (`Value.hs:298-322`): a coin alone, a `Word64`; or, for a list of any length
    * form, `decodeRecordNamed "MaryValue" (const 2)` of the coin and a multi-asset map from version
    * 12, which may not be empty.
    */
  def decode(n: Node): Result[DijkstraValue] = n.value match
    case Value.UInt(_) => V12.word64(n, DecodeField.OutputValue).map(DijkstraValue(_, Map.empty))
    case Value.Arr(_) =>
      V12
        .record(n, 2, DecodeField.OutputValue)
        .flatMap(xs =>
          (
            V12.word64(xs(0), DecodeField.OutputValue),
            MultiAsset.decode(xs(1), DecodeField.OutputValue)(MultiAsset.held)
          ).mapN(DijkstraValue(_, _))
        )
    case other =>
      Left(
        LedgerDecodeError.Expected(DecodeField.OutputValue, LedgerDecodeError.Shape.Array, other)
      )

/** The datum of an output. */
enum DijkstraDatum:
  case NoDatum
  case Hash(hash: Bytes)

  /** An inline datum: the bytes of the Plutus `Data`, which its hash is over. */
  case Inline(raw: Bytes)

object DijkstraDatum:
  /** `Datum` from version 12
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:301-316`): `decodeRecordSum` of a
    * hash `[0, hash]` or an inline datum `[1, #6.24(bytes)]`, whose bytes must be Plutus `Data`
    * with nothing after them (`makeBinaryData`, `:218-237`).
    */
  def decode(n: Node): Result[DijkstraDatum] =
    V12.sum(n, DecodeField.DatumOption) {
      case t if t == 0 => (2, xs => V12.bytesN(xs(1), 32, DecodeField.DatumHash).map(Hash(_)))
      case t if t == 1 => (2, xs => inlineDatum(xs(1)).map(Inline(_)))
    }

  private def inlineDatum(n: Node): Result[Bytes] =
    V12
      .nested(n, DecodeField.InlineDatum)
      .flatMap((raw, item) => data(item, DecodeField.InlineDatum).as(raw))

  /** Plutus `Data` as the plutus-core `Serialise` instance reads it, through the node's decoder. */
  def data(n: Node, what: DecodeSite): Result[Unit] =
    PlutusData.fromNode(n).bimap(LedgerDecodeError.PlutusData(what, _), _ => ())

  /** `hashData`: Blake2b-256 of the datum's original bytes. */
  def hash(raw: Bytes): Bytes = Blake2b.hash256.hash(raw)

/** An output, its original bytes kept. */
final case class DijkstraTxOut(
    raw: Bytes,
    address: Address,
    value: DijkstraValue,
    datum: DijkstraDatum,
    referenceScript: Option[DijkstraScript]
)

object DijkstraTxOut:
  /** `decodeBabbageTxOut` (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:555-676`,
    * 9df9f98c): a map (`TypeMapLen` or `TypeMapLenIndef`; a map whose length is written in eight
    * bytes is `TypeMapLen64` and goes to the list path, which refuses it) read by
    * `decodeSparseKeyed` with the address and value required, a datum and a reference script
    * optional; or the legacy list `[address, value]` or `[address, value, datum hash]`, definite or
    * indefinite. The address is read as Conway and later read it, the whole byte string, a pointer
    * that does not fit refused (`fromCborRigorousBothAddr False`, `Address.hs:459-476`).
    */
  def decode(n: Node): Result[DijkstraTxOut] = n.value match
    case Value.Map(_) if !CborRead.mapLength64(n) =>
      for
        fields <- Sparse.read(n, DecodeField.Output, required = Set(0, 1))(Set(0, 1, 2, 3))
        address <- fields.get(0).toRight(missing(DecodeField.OutputAddress)).flatMap(address)
        value <- fields
          .get(1)
          .toRight(missing(DecodeField.OutputValue))
          .flatMap(DijkstraValue.decode)
        datum <- fields.get(2).traverse(DijkstraDatum.decode)
        script <- fields.get(3).traverse(referenceScript)
      yield DijkstraTxOut(
        n.original,
        address,
        value,
        datum.getOrElse(DijkstraDatum.NoDatum),
        script
      )
    case _ =>
      V12.list(n, DecodeField.Output).flatMap {
        case Vector(a, v) =>
          (address(a), DijkstraValue.decode(v))
            .mapN(DijkstraTxOut(n.original, _, _, DijkstraDatum.NoDatum, None))
        case Vector(a, v, d) =>
          (address(a), DijkstraValue.decode(v), V12.bytesN(d, 32, DecodeField.DatumHash))
            .mapN((x, y, h) => DijkstraTxOut(n.original, x, y, DijkstraDatum.Hash(h), None))
        case xs => Left(LedgerDecodeError.ArrayLength(DecodeField.Output, 3, xs.size))
      }

  private def missing(field: DecodeSite) = LedgerDecodeError.MissingField(DecodeField.Output, field)

  private def address(n: Node): Result[Address] =
    V12.bytes(n, DecodeField.OutputAddress).flatMap(Address.decode(_, Address.Mode.Strict))

  /** `decodeCIC "Script"`: the tag 24 over the bytes of a script read in full. */
  private def referenceScript(n: Node): Result[DijkstraScript] =
    V12.nested(n, DecodeField.ReferenceScript).flatMap((_, item) => DijkstraScript.script(item))

  /** `Seq (Sized TxOut)` (`decodeStrictSeq`): a list, no tag. */
  def list(n: Node): Result[Vector[DijkstraTxOut]] =
    V12.list(n, DecodeField.Outputs).flatMap(_.traverse(decode))
end DijkstraTxOut

/** `decodeSparseKeyed` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:
  * 1228-1287`, 9df9f98c): a definite or indefinite map whose keys, read with `decodeWord`, are the
  * known ones, none twice, and the required ones all present. The value of each key is read by the
  * caller.
  */
private[dijkstra] object Sparse:
  def read(n: Node, what: DecodeSite, required: Set[Int])(known: Set[Int]): Result[Map[Int, Node]] =
    for
      kvs <- V12.entries(n, what)
      keyed <- kvs.traverse((k, v) =>
        V12
          .word64(k, what / DecodeSite.Qualifier.Key)
          .flatMap(key =>
            Option
              .when(key.isValidInt && known.contains(key.toInt))(key.toInt -> v)
              .toRight(LedgerDecodeError.UnknownKey(what, key))
          )
      )
      _ <- V12.unique(keyed, what / DecodeSite.Qualifier.Key)(_._1)
      fields = keyed.toMap
      absent = required -- fields.keySet
      _ <- Either.cond(absent.isEmpty, (), LedgerDecodeError.MissingKeys(what, absent))
    yield fields
