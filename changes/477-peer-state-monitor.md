### Fixed (peer monitoring, #477)

- Keep outbound connection monitoring alive through hot-session and bearer teardown so a connection loss reaches the governor exactly once at each release stage.
- Retain per-protocol application results and connection status across deactivation and reconnects. Successful hot returns preserve the warm bearer and apply the reference node's repromotion delay.
- Include the typed normal-return reason and repromotion delay in asynchronous peer-demotion logs (#460).
