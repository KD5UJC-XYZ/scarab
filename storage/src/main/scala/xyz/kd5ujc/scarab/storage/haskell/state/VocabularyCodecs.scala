// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.ledger.{Anchor, Nonce, Ptr}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}

import Wire.*

/** The codecs of the vocabulary: hashes, amounts, positions, credentials, DReps, pointers, protocol
  * versions, anchors and nonces. Each cites the `EncCBOR` /`DecCBOR` it follows (`CL` =
  * `cardano-ledger` rev f649f97, `libs/cardano-ledger-core/src/Cardano/Ledger`).
  */
trait VocabularyCodecs:
  // ---- hashes: byte strings of the size of the hash (`Hashes.hs:166-232`, newtypes over `Hash`)

  given keyHash[R <: KeyRole]: Codec[KeyHash[R]] =
    hash(28).validated(KeyHash.fromBytes[R])(h => KeyHash.bytes(h))

  given scriptHash: Codec[ScriptHash] =
    hash(28).validated(ScriptHash.fromBytes)(h => ScriptHash.bytes(h))

  given vrfVerKeyHash: Codec[VRFVerKeyHash] =
    hash(32).validated(VRFVerKeyHash.fromBytes)(h => VRFVerKeyHash.bytes(h))

  given hash32: Codec[Hash32] = hash(32).validated(Hash32.fromBytes)(h => Hash32.bytes(h))

  // ---- amounts

  /** `Coin` (`Coin.hs:93,106,111`): written as an `Integer`, read as a `Word64`. */
  given coin: Codec[Coin] = new Codec[Coin]:
    private val under = word(64)
    def decode(n: Node): Decoded[Coin] =
      under
        .decode(n)
        .flatMap(v => Coin.fromBigInt(v).left.map(e => DecodeError.at(n, violation(e))))
    def encode(a: Coin, out: Writer): Unit = out.integer(Coin.value(a))

  /** `DeltaCoin` (`Coin.hs:115-119`): an `Integer` both ways. */
  given deltaCoin: Codec[DeltaCoin] = integer.imap(DeltaCoin(_))(d => DeltaCoin.value(d))

  /** `CompactForm Coin` (`Coin.hs:190-195`): a `Word64`. */
  given compactCoin: Codec[CompactCoin] =
    word64.imap(w => CompactCoin.fromUnsigned(w.bits))(c => c.word)

  given epochNo: Codec[EpochNo] = word64.imap(w => EpochNo.fromUnsigned(w.bits))(e => e.word)
  given slotNo: Codec[SlotNo] = word64.imap(w => SlotNo.fromUnsigned(w.bits))(s => s.word)
  given blockNo: Codec[BlockNo] = word64.imap(w => BlockNo.fromUnsigned(w.bits))(b => b.word)

  given word64Codec: Codec[Word64] = word64

  /** `NonZero a` (`BaseTypes/NonZero.hs:76-78`): the value, refused when it is zero. */
  given nonZero[A](using c: Codec[A], z: HasZero[A]): Codec[NonZero[A]] =
    c.validated(NonZero.from(_)(using z))(n => NonZero.value(n))

  // ---- credentials

  /** `Credential` (`Credential.hs:322-325`): `[0, key hash]` or `[1, script hash]`. */
  given credential[R <: KeyRole]: Codec[Credential[R]] = new Codec[Credential[R]]:
    def decode(n: Node): Decoded[Credential[R]] =
      variant(n).flatMap {
        case (t, Vector(h)) if t == 0 =>
          keyHash[R].decode(h).map(Credential.KeyHashObj[R](_)).at("hash")
        case (t, Vector(h)) if t == 1 =>
          scriptHash.decode(h).map(Credential.ScriptHashObj[R](_)).at("hash")
        case (t, xs) => unknownVariant(n, "Credential", t, xs.size)
      }
    def encode(a: Credential[R], out: Writer): Unit =
      out.arrayHead(2)
      a match
        case Credential.KeyHashObj(h) =>
          out.uint(0L)
          keyHash[R].encode(h, out)
        case Credential.ScriptHashObj(h) =>
          out.uint(1L)
          scriptHash.encode(h, out)

  /** `DRep` (`DRep.hs:74-90`): `[0, key hash]`, `[1, script hash]`, `[2]`, `[3]`. */
  given drep: Codec[DRep] = new Codec[DRep]:
    def decode(n: Node): Decoded[DRep] =
      variant(n).flatMap {
        case (t, Vector(h)) if t == 0 =>
          keyHash[DRepRole].decode(h).map(DRep.DRepKeyHash(_)).at("hash")
        case (t, Vector(h)) if t == 1 => scriptHash.decode(h).map(DRep.DRepScriptHash(_)).at("hash")
        case (t, Vector()) if t == 2  => Right(DRep.DRepAlwaysAbstain)
        case (t, Vector()) if t == 3  => Right(DRep.DRepAlwaysNoConfidence)
        case (t, xs)                  => unknownVariant(n, "DRep", t, xs.size)
      }
    def encode(a: DRep, out: Writer): Unit = a match
      case DRep.DRepKeyHash(h) =>
        out.arrayHead(2); out.uint(0L); keyHash[DRepRole].encode(h, out)
      case DRep.DRepScriptHash(h) =>
        out.arrayHead(2); out.uint(1L); scriptHash.encode(h, out)
      case DRep.DRepAlwaysAbstain =>
        out.arrayHead(1); out.uint(2L)
      case DRep.DRepAlwaysNoConfidence =>
        out.arrayHead(1); out.uint(3L)

  /** `Ptr` (`Credential.hs:258-260,353-364`, a `CBORGroup`): `[slot, tx index, cert index]` as a
    * `Word32` and two `Word16` s. From version 9 the decoder is strict; before it a value out of
    * its range makes the pointer `Ptr 0 0 0` (`mkPtrNormalized`, `Credential.hs:279-285`), which is
    * what an unreachable pointer was turned into.
    */
  given ptr(using v: ProtVer): Codec[Ptr] = new Codec[Ptr]:
    private val loose = word(64)
    def decode(n: Node): Decoded[Ptr] =
      Wire.items(n, 3).flatMap { xs =>
        if v.atLeast(9) then
          for
            s <- word32.decode(xs(0)).at("slot")
            t <- word16.decode(xs(1)).at("txIx")
            c <- word16.decode(xs(2)).at("certIx")
          yield Ptr(s, t, c)
        else
          for
            s <- loose.decode(xs(0)).at("slot")
            t <- loose.decode(xs(1)).at("txIx")
            c <- loose.decode(xs(2)).at("certIx")
          yield Ptr(s, t, c).normalised
      }
    def encode(a: Ptr, out: Writer): Unit =
      out.arrayHead(3)
      out.uint(a.slot)
      out.uint(a.txIx)
      out.uint(a.certIx)
    override def check(a: Ptr): Option[ConstraintViolation] =
      Codec.refuse(
        !a.fits,
        "Ptr",
        "a pointer of a Word32 and two Word16s",
        s"(${a.slot}, ${a.txIx}, ${a.certIx})"
      )

  /** `ProtVer` (`BaseTypes.hs:211-244`, a `CBORGroup`): `[major, minor]`, the major in the
    * supported versions 0..13 (`Version.hs:55-110`), the minor a `Word32`.
    */
  given protVer: Codec[ProtVer] = new Codec[ProtVer]:
    private val maxMajor = BigInt(13)
    def decode(n: Node): Decoded[ProtVer] =
      Wire.items(n, 2).flatMap { xs =>
        for
          major <- Codec.uintBig(maxMajor).decode(xs(0)).at("major")
          minor <- word32.decode(xs(1)).at("minor")
        yield ProtVer(Word64.fromUnsigned(major.toLong), Word64.fromUnsigned(minor.toLong))
      }
    def encode(a: ProtVer, out: Writer): Unit =
      out.arrayHead(2)
      out.uint(a.major.toBigInt)
      out.uint(a.minor.toBigInt)
    override def check(a: ProtVer): Option[ConstraintViolation] =
      Codec.refuse(
        !(a.major.toBigInt <= maxMajor && a.minor.toBigInt.bitLength <= 32),
        "ProtVer",
        "a major version in 0..13 and a Word32 minor",
        s"(${a.major.toBigInt}, ${a.minor.toBigInt})"
      )

  /** `Anchor` (`BaseTypes.hs:996-1010`): `[url, data hash]`; the url is at most 128 bytes from
    * version 9 and 64 before it (`DecCBOR Url`, `BaseTypes.hs:678-684`).
    */
  given anchor(using v: ProtVer): Codec[Anchor] = new Codec[Anchor]:
    private val url = Codec.textSized(0, if v.atLeast(9) then 128 else 64)
    private val dataHash = hash(32)
    def decode(n: Node): Decoded[Anchor] =
      Wire.items(n, 2).flatMap { xs =>
        for
          u <- url.decode(xs(0)).at("url")
          h <- dataHash.decode(xs(1)).at("dataHash")
        yield Anchor(u, h)
      }
    def encode(a: Anchor, out: Writer): Unit =
      out.arrayHead(2)
      out.text(a.url)
      out.bytes(a.hash)
    override def check(a: Anchor): Option[ConstraintViolation] =
      url.check(a.url).map(_.at("url")).orElse(dataHash.check(a.hash).map(_.at("dataHash")))

  /** `Nonce` (`BaseTypes.hs:574-577`): `[0]` for the neutral nonce, `[1, hash]`. */
  given nonce: Codec[Nonce] = Wire.nonce
