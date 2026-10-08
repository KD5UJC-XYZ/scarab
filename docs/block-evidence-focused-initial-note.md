# Initial focused test-harness correction

The first v0.21 focused attempt compiled the new core and passed 40 tests, then a test deliberately throwing LinkageError failed through MUnit's `intercept` helper, which does not intercept that fatal error. The run reported total 45, passed 40, failed 1, skipped 4; app tests were not reached. The full stdout was not separately retained as a file.

The test was corrected to catch that exact LinkageError manually and assert object identity and propagation. Production code still excludes fatal errors from NonFatal conversion; no expected crypto outcome, protocol limit or deadline changed. The later retained focused log includes the passing corrected test plus additional constructor/transplant/resource cases.
