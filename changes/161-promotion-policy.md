### Added (protected promotion tooling, #161)
- Validate conventional PR titles and provide a trusted metadata check for the planned same-repository `dev` to `main` policy.
- Add a reviewed cutover plan, distinct tag-creation and immutability rules, and an approval-gated main promotion controller that checks live PR metadata and approved commits before merging. Repository protection, App credentials and the promotion cutover remain owner-managed and inactive until configured.
- Report required CI checks for all planned protected PR targets. CDDL selection now reports a required gate even when no comparison is needed, without rebuilding the tool for unrelated changes.
