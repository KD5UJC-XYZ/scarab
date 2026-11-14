### Fixed (network, #949)

- An ASCII node-to-client socket path of exactly 108 characters is refused with the reference's `pokeSockAddr` error
  (network 3.2.8.0 refuses 108 characters or more), not with the JDK's.
- A node-to-client socket path beyond ASCII stops the start, after `configuringLocalSocket`, with the `diffusionErrored`
  line cardano-node 11.1.3 gave on a first start with `/w/café...` under `LANG=C.UTF-8` (`<path>: getFileStatus: does
  not exist (No such file or directory)`). Scarab used to serve it. Unlike the reference, it leaves no socket file under
  the path's characters truncated to a byte each. A path refused for its length or a trailing `/` is refused for that
  first, as in the reference. Under `LANG=C`, and with a lone byte 0xE9, the reference served such a path; Scarab refuses
  it there too (an approved deviation).
- A socket path that holds NUL (it can come only from the configuration's `SocketPath`) refuses the start before
  anything is removed, with the reference's `removeLink` check. The stale-socket removal used to stop at the NUL
  and remove the file the bytes before it name.
