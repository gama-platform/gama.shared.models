# GAMA Shared Models

GAMA Shared Models adds a **Shared Models** folder above **User** in GAMA's
navigator. It discovers community projects from
[gama-platform/gama.models](https://github.com/gama-platform/gama.models) and
copies only the projects the user explicitly chooses into their workspace.

## Browsing and importing

Expand **Shared Models** and then either source:

- **Published models** reads the public JSON registry, including version,
  description, author, license and GAMA compatibility information.
- **Repository projects** discovers `.project` folders in the repository and
  retains their categories, such as Generic and Thematic.

Expand a project to browse its file hierarchy. Right-click a project for
**Show details...**, **Open source page**, or **Import into workspace...**.
Double-clicking a file opens its source page externally; for registry archives,
this opens the model's homepage or download page, not an individual file preview.

Browsing never creates workspace projects. Registry ZIP archives are downloaded
to the plug-in cache for file listing; repository file listings come from
GitHub's tree API. Import asks for a local project name, copies the complete
selected project and opens it under User. Existing projects and folders are
never overwritten. Imported projects are independent local copies, not linked
to GitHub, and record their source and version/commit as project properties.

**Refresh/F5** discards the displayed snapshots and reloads on expansion.
Network work runs in cancellable background jobs. Cached catalogue metadata can
be used after a network failure, with an explicit stale/offline message.
GitHub authentication is not required; anonymous API rate limits still apply.

This first version imports **ZIP projects only**, using the latest registered
version or a commit-pinned repository snapshot. It verifies registry archive
sizes and SHA-256 checksums, limits downloads to 200 MiB and extraction to
1 GiB/10,000 entries, and rejects unsafe paths. Temporary extracted files are
removed after browsing/importing; downloaded archives and JSON metadata remain
in the plug-in state cache for reuse.

Projects with linked resources, ambiguous multi-project archives or missing
project natures fail explicitly rather than being partially imported. Legacy
GAMA nature identifiers are normalized and built-in/library classification is
removed so the imported copy remains a user project. Required extensions and
GAMA version compatibility remain the user's responsibility; importing does
not compile or execute remote models.

Version selection, `.gamp`/`.gamx` import, in-app source previews and automatic
updates are not implemented yet.

## Installing through GAMA

The intended user flow is to open **Install GAMA extensions...** in GAMA and
install the tool alongside other GAMA extensions. For it to appear there, the
GAMA extension catalog must include this update site among the repositories it
checks:

<https://gama-platform.org/gama.shared.models/>

The installable feature bundles the `gama.shared.models` and
`gama.ui.shared.models` plug-ins and is assigned to the `gama.category.tool`
category. The catalog entry and category allow GAMA to discover and present the
tool in its extension installer.

Until the update site is included in GAMA's extension catalog, it can be
installed manually through **Help > Install New Software...** using the URL
above. Installation requires a GAMA build containing the new
`gama.ui.navigator.topLevelFolders` API. Older GAMA builds are not supported.

## Project structure

| Project | Role |
|---------|------|
| `gama.shared.models` | HTTP catalogue discovery, metadata, download and safe ZIP staging. |
| `gama.ui.shared.models` | Virtual navigator hierarchy, details/menu commands and workspace import. |
| `gama.feature.shared.models` | Installable feature grouping both bundles. |
| `gama.p2site.shared.models` | Eclipse p2 update site. |

The UI contributes through the navigator's existing folder and Eclipse command
extension points. No GAML extensions are registered; annotation processing is
explicitly disabled. JSON parsing uses Jackson packages available in the target
platform.

## Build

Use Maven 3.9.x and a JDK supported by Tycho 5.0.2; CI uses Maven 3.9.16 and
Temurin 25. The bundles target JavaSE-25, Eclipse 2026-03 (4.39), and the current GAMA
navigator contribution API.

```sh
mvn -B -DskipTests package
```

The default GAMA dependency site is
`https://updates.gama-platform.org/0.0.0/`. At the time this implementation was
added, that published site did **not** yet contain the navigator contribution
API. Until an updated GAMA build is published there, supply a compatible p2
repository containing the newly built navigator and its dependencies:

```sh
mvn -B -DskipTests \
  -Dgama.target.repository=file:/absolute/path/to/gama/p2/repository package
```

For CI, set the repository variable `GAMA_TARGET_REPOSITORY` to a publicly
accessible compatible p2 site, or provide the target URL on a manual workflow
dispatch. The build intentionally fails against an older navigator rather than
silently building a nonfunctional plug-in.

The update site is generated in
`gama.p2site.shared.models/target/repository/`. It contains the two bundles and
one feature, not their external dependencies. The target platform covers Linux
GTK x86_64, Windows x86_64, and macOS x86_64 / Apple Silicon.

To work in Eclipse/GAMA, import the four existing projects into the workspace.
Both bundle projects include PDE/JDT metadata and Java 25/UTF-8 project settings.
The Eclipse target/workspace must include the updated `gama.ui.navigator`.

## Update site and publishing

The publishing workflow builds relevant `main` pushes and pull requests, and
can also be dispatched manually. Only successful non-PR builds on `main`
publish. GitHub Pages must use **GitHub Actions** (`build_type=workflow`), and
the `github-pages` environment and repository rules must permit deployment and
workflow pushes to `shared-models-site`.

Each published build is retained as an immutable `releases/<run-id>-<attempt>/`
repository on `shared-models-site`. Root composite p2 metadata keeps all
retained releases accessible and references Eclipse 2026-03 and GAMA for external
dependencies. Each release includes source-commit and workflow-run provenance.
The publisher requires Python 3.11 or newer:

```sh
python3 gama.p2site.shared.models/publish.py \
  gama.p2site.shared.models/target/repository /path/to/site unique-release-id
```

## License and scaffold provenance

Copyright (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners. This project is
licensed under GNU GPL version 3; see [LICENSE](LICENSE) and
[THIRD_PARTY.md](THIRD_PARTY.md).

The PDE, Tycho, feature, and versioned p2 publishing scaffold is adapted from
[gama-platform/gama.copilot](https://github.com/gama-platform/gama.copilot/tree/8408c27).
Copilot-specific code, service dependencies, and extension contributions are
not included.
