// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node}

/** Text a block body carries is read with `decodeString`, which takes a definite-length string in
  * every decoder version (cborg through `instance DecCBOR Text`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:356-358`,
  * cardano-ledger f649f975): an indefinite (chunked) string is not text there. The readers that
  * keep chunked text are transaction metadata (`decodeMetadatum`, `Metadata.hs:153-222`, pinned by
  * the #373 corpus suite) and Plutus `Data`, and neither goes through [[CborRead.text]].
  *
  * Each place is written twice, with a definite string and with the same characters as one chunk,
  * so a refusal is for the form of the string alone. Evidence for #435.
  */
class DefiniteTextSuite extends FunSuite:
  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def node(s: String): Node =
    CborRead.decode(hex(s)).fold(e => fail(s"not one CBOR item: $e"), identity)

  private val hash = "ab" * 32

  private val conway = DecoderVersion.of(xyz.kd5ujc.scarab.protocol.Era.Conway)

  /** `[url, data_hash]` (`Anchor`, `PoolMetadata`): the same array with a definite and a chunked
    * url.
    */
  private def pair(url: String): String = s"82 $url 5820 $hash"

  private val definiteUrl = "61 61" // "a"
  private val chunkedUrl = "7f 61 61 ff" // "a" as one chunk

  test("CborRead.text takes a definite string and refuses a chunked one") {
    assertEquals(
      CborRead.text(node(definiteUrl), DecodeField.PoolMetadataURL),
      Right("a")
    )
    assertEquals(
      CborRead.text(node(chunkedUrl), DecodeField.PoolMetadataURL),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.PoolMetadataURL))
    )
  }

  test("a pool metadata url is definite: the reference reads PoolMetadata with decodeString") {
    assert(
      PoolParams.metadataHash(hex(pair(definiteUrl))).isRight,
      "a definite url with a 32-byte hash is read"
    )
    assertEquals(
      PoolParams.metadataHash(hex(pair(chunkedUrl))),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.PoolMetadataURL))
    )
  }

  test("an anchor url is definite: `Url` decodes textDecCBOR, and textDecCBOR is decodeString") {
    assertEquals(Anchor.decode(node(pair(definiteUrl)), conway).map(_.url), Right("a"))
    assertEquals(
      Anchor.decode(node(pair(chunkedUrl)), conway),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.AnchorUrl))
    )
  }

  test("a relay dns name is definite: `DnsName` decodes textDecCBOR") {
    // [[1, null, dns_name]]: a SingleHostName relay without a port
    assertEquals(
      PoolRelays.decode(hex(s"81 83 01 f6 $definiteUrl")),
      Right(Vector(PoolRelay.SingleHostName(None, "a")))
    )
    assertEquals(
      PoolRelays.decode(hex(s"81 83 01 f6 $chunkedUrl")),
      Left(PoolRelayError.IndefiniteDnsName(0))
    )
    // [[2, dns_name]]: a MultiHostName relay
    assertEquals(
      PoolRelays.decode(hex(s"81 82 02 $definiteUrl")),
      Right(Vector(PoolRelay.MultiHostName("a")))
    )
    assertEquals(
      PoolRelays.decode(hex(s"81 82 02 $chunkedUrl")),
      Left(PoolRelayError.IndefiniteDnsName(0))
    )
  }

  test("a Byron software name is definite: ApplicationName decodes text") {
    // `ApplicationName` is `newtype ... = ApplicationName Text` with `decCBOR = ... <$> decCBOR`
    // (`Update/ApplicationName.hs:41-62`, cardano-ledger f649f975), so it takes `decodeString`.
    assertEquals(
      CborRead.text(node(definiteUrl), DecodeField.SoftwareName),
      Right("a")
    )
    assertEquals(
      CborRead.text(node(chunkedUrl), DecodeField.SoftwareName),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.SoftwareName))
    )
  }
