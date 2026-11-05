# gama.shared.models

Empty GAMA/Eclipse plug-in scaffold for shared model repositories. Browsing,
importing and other runtime behavior are not implemented yet.

## Projects

| Project | Role |
|---------|------|
| `gama.shared.models` | Logic bundle, with no runtime contributions yet. |
| `gama.ui.shared.models` | UI bundle, depending on `gama.shared.models`. |
| `gama.feature.shared.models` | Installable feature grouping both bundles. |
| `gama.p2site.shared.models` | Thin Eclipse p2 update site. |

The bundles contain no Java classes, activators, extension registrations or GAML
extensions. They do not depend on GAMA's GAML annotation processor.

## Build

Use Maven 3.9.x and a JDK supported by Tycho 5.0.2; CI uses Maven 3.9.16 and
Temurin 25. The bundles target JavaSE-21 and Eclipse 2026-03 (4.39).

```sh
mvn -B -DskipTests package
```

The update site is generated in
`gama.p2site.shared.models/target/repository/`. It contains exactly the two
bundles and one feature, not their external dependencies. The target platform
covers Linux GTK x86_64, Windows x86_64, and macOS x86_64 / Apple Silicon.

To work in Eclipse/GAMA, import the four existing projects into the workspace.
Both bundle projects include PDE/JDT metadata and Java 21/UTF-8 project settings.

## Update site and CI

The intended stable update-site URL is
`https://gama-platform.org/gama.shared.models/`. Add this URL in
**Help > Install New Software...** and select **GAMA Shared Models**. The
installable feature ID is `gama.feature.shared.models.feature.group`. Installation
currently adds only the empty bundles; it does not add commands or GAML syntax.

The publishing workflow builds relevant `main` pushes and pull requests, and can
also be dispatched manually. Only successful non-PR builds on `main` publish.
GitHub Pages must use **GitHub Actions** (`build_type=workflow`), and the
`github-pages` environment and repository rules must permit deployment and
workflow pushes to `shared-models-site`.

Each published build is retained as an immutable `releases/<run-id>-<attempt>/`
repository on `shared-models-site`. Root composite p2 metadata keeps all retained
releases accessible and references Eclipse 2026-03 for external dependencies.
Each release includes source-commit and workflow-run provenance. The publisher
requires Python 3.11 or newer:

```sh
python3 gama.p2site.shared.models/publish.py \
  gama.p2site.shared.models/target/repository /path/to/site unique-release-id
```

## License and scaffold provenance

Copyright (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners. This project is licensed
under GNU GPL version 3; see [LICENSE](LICENSE) and
[THIRD_PARTY.md](THIRD_PARTY.md).

The PDE, Tycho, feature and versioned p2 publishing scaffold is adapted from
[gama-platform/gama.copilot](https://github.com/gama-platform/gama.copilot/tree/8408c27).
Copilot-specific code, service dependencies and extension contributions are not
included.
