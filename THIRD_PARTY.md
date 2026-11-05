# Third-party dependencies

This repository distributes only the `gama.shared.models` and
`gama.ui.shared.models` bundles and the `gama.feature.shared.models` feature.
They currently contain no runtime code or third-party binaries.

Eclipse components used to resolve the build target are supplied by the
[Eclipse 2026-03 update site](https://download.eclipse.org/releases/2026-03/).
They are not redistributed by this thin p2 site and retain their publishers'
licenses and notices. Maven and Tycho are build tools, not bundled runtime
dependencies.

The build and publishing scaffold is adapted from
[gama-platform/gama.copilot](https://github.com/gama-platform/gama.copilot/tree/8408c27)
under GNU GPL version 3, with the original GAMA copyright attribution retained.
No Copilot components, agents, services or Microsoft repositories are included.
