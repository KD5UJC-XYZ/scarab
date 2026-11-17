// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.protocol.{EbHash, Era}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  MirrorExtras,
  PBftSigner,
  PBftState,
  PraosState,
  PrtclState,
  ProtocolExtras,
  TPraosLedgerState,
  TPraosState,
  TicknState
}
import xyz.kd5ujc.scarab.ledger.state.{KeyHash, KeyRole, SlotNo, Sorted, WithOrigin, Word64}
import cats.Order
import xyz.kd5ujc.scarab.codec.Bytes
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.byron.ByronVersion

/** The codec of everything a snapshot holds, one codec per type, composed. Case classes and enums
  * are derived from their `Mirror` ([[Codec.auto]]); the instances below exist so each shared type
  * is derived once (it keeps compilation fast) and so the composition reads as the state's shape. A
  * field added to any derived type changes the schema fingerprint, so snapshots written before it
  * are rejected (`VersionMismatch`) rather than misread. A few are written field by field instead,
  * to keep the layout they were written in ([[byronVersion]], [[script]]). Instances are lazy, so
  * order is free.
  */
object NodeCodecs:
  // Leaf values shared by many types.
  given credential: Codec[Credential] = Codec.auto
  given address: Codec[Address] = Codec.auto
  given ptr: Codec[Ptr] = Codec.auto
  given rewardAccount: Codec[RewardAccount] = Codec.auto
  given value: Codec[Value] = Codec.auto
  given datum: Codec[Datum] = Codec.auto

  /** Written field by field, not derived (#998): the four fields of `Script` in their declared
    * order, through the codecs and with the shape its derivation used, so the stores' scripts keep
    * the layout they were written in and every fingerprint that holds an output keeps its value. A
    * field `Script` gains is not persisted unless this codec writes it, which is a format change
    * (`CodecSuite` pins the shape and the bytes).
    */
  given script: Codec[Script] = scriptOf(Codec.auto)

  /** The script's codec with the kind's codec `kind`: the live one, or a frozen one for a previous
    * version's schema ([[FrozenScriptKind]]).
    */
  def scriptOf(kind: Codec[ScriptKind]): Codec[Script] =
    val hash = summon[Codec[Bytes]]
    val native = summon[Codec[Option[NativeScript]]]
    val plutus = summon[Codec[Option[Bytes]]]
    Codec.make[Script](
      s"Script(kind:${kind.shape},hash:${hash.shape},native:${native.shape},plutus:${plutus.shape})"
    ) { (out, s) =>
      kind.write(out, s.kind)
      hash.write(out, s.hash)
      native.write(out, s.native)
      plutus.write(out, s.plutus)
    }(in =>
      for
        k <- kind.read(in)
        h <- hash.read(in)
        n <- native.read(in)
        p <- plutus.read(in)
      yield Script(k, h, n, p)
    )
  given txIn: Codec[TxIn] = Codec.auto
  given txOut: Codec[TxOut] = Codec.auto
  given utxoChange: Codec[Utxo.Change] = Codec.auto
  given utxo: Codec[Utxo] = Codec.auto
  given paramUpdate: Codec[ParamUpdate] = Codec.auto
  given poolParams: Codec[PoolParams] = Codec.auto
  given protocolParams: Codec[ProtocolParams] = Codec.auto
  given nonce: Codec[Nonce] = Codec.auto
  given era: Codec[Era] = Codec.auto
  given genDelegPair: Codec[GenDelegPair] = Codec.auto

  // The chain-dependent state of the Haskell shape: unsigned words, key hashes, sorted maps.
  given word64: Codec[Word64] =
    Codec.make[Word64]("Word64")((o, w) => o.fixed64(w.bits))(i =>
      i.fixed64().map(Word64.fromUnsigned)
    )

  given slotNo: Codec[SlotNo] =
    Codec.make[SlotNo]("SlotNo")((o, s) => o.fixed64(s.word.bits))(i =>
      i.fixed64().map(SlotNo.fromUnsigned)
    )

  given keyHash[R <: KeyRole]: Codec[KeyHash[R]] =
    Codec.make[KeyHash[R]]("KeyHash")((o, h) => summon[Codec[Bytes]].write(o, h.bytes))(i =>
      summon[Codec[Bytes]]
        .read(i)
        .flatMap(bytes =>
          KeyHash.fromBytes[R](bytes).leftMap(_ => BinaryError.InvalidKeyHashLength(bytes.size, 28))
        )
    )

  /** In ascending key order (that of `Order[K]`), as the map holds it. */
  given sortedMap[K, V](using k: Codec[K], v: Codec[V], order: Order[K]): Codec[SortedMap[K, V]]
  with
    def write(out: Out, a: SortedMap[K, V]): Unit =
      out.varint(a.size.toLong)
      a.foreach { (key, value) =>
        k.write(out, key)
        v.write(out, value)
      }
    def read(in: In): Either[BinaryError, SortedMap[K, V]] =
      Codec.readCounted(in, Sorted.map[K, V](Vector.empty)) {
        for
          key <- k.read(in)
          value <- v.read(in)
        yield key -> value
      }(_ + _)
    def shape: String = s"SortedMap[${k.shape},${v.shape}]"

  given withOrigin[A](using Codec[A]): Codec[WithOrigin[A]] =
    Codec.auto
  given pbftSigner: Codec[PBftSigner] = Codec.auto
  given pbftState: Codec[PBftState] = Codec.auto
  given prtclState: Codec[PrtclState] = Codec.auto
  given ticknState: Codec[TicknState] = Codec.auto
  given tpraosLedgerState: Codec[TPraosLedgerState] = Codec.auto
  given tpraosState: Codec[TPraosState] = Codec.auto
  given praosState: Codec[PraosState] = Codec.auto
  given ebHash: Codec[EbHash] =
    Codec.make[EbHash]("EbHash")((o, h) => summon[Codec[Bytes]].write(o, h.bytes))(i =>
      summon[Codec[Bytes]].read(i).map(EbHash.fromBytes)
    )
  given ebAnnouncement: Codec[EbAnnouncement] = Codec.auto
  given announcedBy: Codec[Praos2.AnnouncedBy] = Codec.auto
  given praos2State: Codec[Praos2.State] = Codec.auto
  given chainDepState: Codec[ChainDepState] = Codec.auto
  given protocolExtras: Codec[ProtocolExtras] = Codec.auto

  // Consensus.
  given follow: Codec[FollowState] = Codec.auto
  // Keep the historical case-class layout and fingerprint as the domain becomes bounded.
  given byronVersion: Codec[ByronVersion] =
    val number = summon[Codec[BigInt]]
    Codec.make[ByronVersion]("ByronVersion(major:BigInt,minor:BigInt,alt:BigInt)")((out, version) =>
      number.write(out, BigInt(version.major))
      number.write(out, BigInt(version.minor))
      number.write(out, BigInt(version.alt))
    )(in =>
      for
        major <- number.read(in)
        minor <- number.read(in)
        alt <- number.read(in)
        version <- ByronVersion.from(major, minor, alt).leftMap {
          case ByronVersion.Error.OutOfRange(field, value) =>
            BinaryError.InvalidByronVersion(field.toString, value)
        }
      yield version
    )
  given byron: Codec[ByronState] = Codec.auto
  given consensusExtras: Codec[ConsensusExtras] = Codec.auto
  given mirrorInShelley: Codec[MirrorExtras.InShelley] = Codec.auto

  // Roots.
  given blockRef: Codec[BlockRef] = Codec.auto
