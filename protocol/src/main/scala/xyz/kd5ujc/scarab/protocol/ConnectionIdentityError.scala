// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** Failures while retaining the concrete addresses of an established TCP socket. */
enum ConnectionIdentityError extends DomainError:
  case UnresolvedAddress(value: String)
  case NonNumericScope(value: String)
  case ScopeOutOfRange(value: BigInt)
  case InvalidPort(value: Int)
  case UnsupportedAddressLength(value: Int)

  def message: String = this match
    case UnresolvedAddress(value) => s"established TCP endpoint is not a numeric IP address: $value"
    case NonNumericScope(value)   => s"TCP IPv6 scope is not the kernel's numeric scope: $value"
    case ScopeOutOfRange(value)   => s"TCP IPv6 scope exceeds Word32: $value"
    case InvalidPort(value)       => s"TCP port exceeds Word16: $value"
    case UnsupportedAddressLength(value) => s"TCP address has $value bytes instead of 4 or 16"
