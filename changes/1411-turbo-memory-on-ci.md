### Added (conformance)

- The **Conformance reports** workflow can run `uplc-turbo` for its memory counts alone (`uplc-turbo-memory`): the programs within the site's memory limit and the bytes allocated per memory unit, at the runner's own settings, which a GitHub-hosted runner reproduces. `publish.py` refuses a `uplc-turbo` report that was not written on a local machine.
