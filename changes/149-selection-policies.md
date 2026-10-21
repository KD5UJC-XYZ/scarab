### Fixed (peer selection, #149)

- Warm-peer activation now follows the pinned reference policy: peers just demoted from hot are less likely to be immediately promoted again. Cold connections and peer-sharing choices retain uniform selection. Hot-demotion diagnostics retain every eligible peer's score and distinguish an absent score from a measured zero.
