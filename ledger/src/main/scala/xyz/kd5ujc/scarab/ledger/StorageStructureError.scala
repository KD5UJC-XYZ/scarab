// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** Basic persisted-file identities, independent of the storage interpreter. */
enum StorageStructureError:
  case VolatileImmutableMismatch
  case WrongFileKind(kind: FileKind)
  case InvalidIdentityFields
  case UnexpectedIdentityLines
  case EmptyIdentity
  case GenesisSnapshot
  case SlotOrder(slot: BigInt, previous: BigInt)
  case HashLength(actual: Int)
  case InvalidBlockNumbers(slot: BigInt, blockNo: BigInt)
  case KeyLength(actual: Int)
  case StampLength(actual: Int)
  case RocksFailure(cause: Throwable)
