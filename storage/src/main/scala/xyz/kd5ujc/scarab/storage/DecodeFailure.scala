// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.models.runtime.DecodeError

/** Native readIncremental returns decoder failures, but unexpected exceptions escape snapshot
  * fallback (consensus 82ecba32, Util/CBOR.hs181–230 and Storage/LedgerDB/API.hs606–635).
  */
private[storage] object DecodeFailure:
  def native(what: String, error: DecodeError): StorageError =
    error.terminal.fold[StorageError](StorageError.Corrupt(what, error)) { terminal =>
      terminal.cause match
        case DecodeError.TerminalCause.Internal(cause) => StorageError.DecoderDefect(what, cause)
        case DecodeError.TerminalCause.Reconstruction(cause) =>
          StorageError.Reconstruction(what, cause)
    }

  def binary(what: String, error: BinaryError): StorageError = error match
    case BinaryError.Internal(cause)       => StorageError.DecoderDefect(what, cause)
    case BinaryError.Reconstruction(cause) => StorageError.Reconstruction(what, cause)
    case other                             => StorageError.Corrupt(what, other)

  def terminal(error: StorageError): Boolean = error match
    case _: StorageError.DecoderDefect | _: StorageError.Reconstruction[?] => true
    case _                                                                 => false
