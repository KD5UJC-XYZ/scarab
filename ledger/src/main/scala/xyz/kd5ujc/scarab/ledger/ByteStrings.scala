// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** The encodings of a byte string a ledger decoder reads. The reference reads a byte string with
  * `decodeBytes` or `decodeByteArray`, which take a definite-length string below decoder version 12
  * and also an indefinite (chunked) one, its chunks joined, from 12 (`ifDecoderVersionAtLeast
  * (natVersion @12)`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:350-373` and
  * `1426-1448`, cardano-ledger f649f975). Below 12 cborg fails a chunked string with "expected
  * bytes" or "expected string". Plutus `Data` and transaction metadata read chunked strings in
  * every version and do not take this policy.
  *
  * A reader of a byte string takes a `ByteStrings` as a context parameter, and none is given by
  * default: a decoder of an era's blocks derives it from the era's [[DecoderVersion]], and a reader
  * of what the node stored itself names [[ByteStrings.Chunked]].
  */
enum ByteStrings:
  /** A definite-length string only: every decoder version below 12. */
  case Definite

  /** A definite string, or an indefinite one whose chunks are joined: decoder version 12 on, and
    * the outputs this node stored before it refused chunked strings in blocks, which must keep
    * reading (ADR 0006).
    */
  case Chunked

object ByteStrings:
  /** `ifDecoderVersionAtLeast (natVersion @12)`. */
  def of(version: DecoderVersion): ByteStrings =
    if version.atLeast(12) then Chunked else Definite
