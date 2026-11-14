// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

/** Shared socket failures live below the transport so [[NetworkError]] preserves closed causes. */
type IoErrorType = xyz.kd5ujc.scarab.protocol.IoErrorType
val IoErrorType = xyz.kd5ujc.scarab.protocol.IoErrorType
type Errno = xyz.kd5ujc.scarab.protocol.Errno
val Errno = xyz.kd5ujc.scarab.protocol.Errno
type ErrnoCause = xyz.kd5ujc.scarab.protocol.ErrnoCause
val ErrnoCause = xyz.kd5ujc.scarab.protocol.ErrnoCause
type SystemCall = xyz.kd5ujc.scarab.protocol.SystemCall
val SystemCall = xyz.kd5ujc.scarab.protocol.SystemCall
type SystemCallError = xyz.kd5ujc.scarab.protocol.SystemCallError
val SystemCallError = xyz.kd5ujc.scarab.protocol.SystemCallError
