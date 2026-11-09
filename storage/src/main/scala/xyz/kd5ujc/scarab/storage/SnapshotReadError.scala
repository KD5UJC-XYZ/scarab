// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError

/** Structural failures of snapshot framing and its immutable anchor. Nested decoders retain their
  * own closed error types in StorageError.Corrupt.
  */
enum SnapshotReadError extends DomainError:
  case FileTooShort(length: Int)
  case ChecksumMismatch
  case BadMagic
  case VersionHeaderLength(found: Int, expected: Int)
  case TrailingPayload
  case SidecarLength(length: Int, fileLength: Int)
  case BindingLength(length: Int, fileLength: Int)

  /** The recorded reference build of a V10 file is empty or longer than the file. */
  case BuildLength(length: Int, fileLength: Int)

  /** The recorded reference build of a V10 file is not printable ASCII without spaces. */
  case BuildName
  case ByronWithLedgerState
  case ByronLedgerInShelley
  case AnchorMismatch
  case IndexLength(length: Long)
  case IndexMagic
  case PointOutsideData
  case TruncatedBlock
  case BlockChecksum
  case BlockAnchorMismatch
