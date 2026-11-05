"""Assemble immutable release repositories behind a stable composite p2 site."""

import argparse
from pathlib import Path
import re
import shutil
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile


DEPENDENCY_SITES = (
    "https://download.eclipse.org/releases/2026-03/",
)


def assemble(incoming, site, release):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", release):
        raise ValueError("Invalid release directory name")
    for kind in ("content", "artifacts"):
        with ZipFile(incoming / f"{kind}.jar") as archive:
            metadata = ET.fromstring(archive.read(f"{kind}.xml"))
        if kind == "artifacts":
            actual = {(a.get("classifier"), a.get("id")) for a in metadata.findall("artifacts/artifact")}
            expected = {
                ("osgi.bundle", "gama.shared.models"),
                ("osgi.bundle", "gama.ui.shared.models"),
                ("org.eclipse.update.feature", "gama.feature.shared.models"),
            }
            if actual != expected:
                raise ValueError(f"Unexpected thin-site artifacts: {actual}")
    if not (incoming / "plugins").is_dir() or not (incoming / "features").is_dir():
        raise ValueError("The incoming repository has no plugins or features")
    releases = site / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    destination = releases / release
    if destination.exists():
        raise ValueError(f"Refusing to overwrite immutable release {release}")
    shutil.copytree(incoming, destination)
    children = sorted(p for p in releases.iterdir() if p.is_dir())
    for child in children:
        if not (child / "content.jar").is_file() or not (child / "artifacts.jar").is_file():
            raise ValueError(f"Incomplete retained repository: {child}")
    for kind in ("Metadata", "Artifact"):
        root = ET.Element("repository", {
            "name": "GAMA Shared Models",
            "type": f"org.eclipse.equinox.internal.p2.{kind.lower()}.repository.Composite{kind}Repository",
            "version": "1.0.0",
        })
        properties = ET.SubElement(root, "properties", size="1")
        ET.SubElement(properties, "property", name="p2.timestamp", value=str(int(time.time() * 1000)))
        entries = ET.SubElement(root, "children", size=str(len(children)))
        for child in children:
            ET.SubElement(entries, "child", location=f"releases/{child.name}")
        references = ET.SubElement(root, "references", size=str(len(DEPENDENCY_SITES) * 2))
        for location in DEPENDENCY_SITES:
            for repository_type in ("0", "1"):
                ET.SubElement(references, "repository", uri=location, url=location,
                              type=repository_type, options="1")
        ET.indent(root)
        filename = "compositeContent.xml" if kind == "Metadata" else "compositeArtifacts.xml"
        instruction = "compositeMetadataRepository" if kind == "Metadata" else "compositeArtifactRepository"
        (site / filename).write_bytes(
            f'<?xml version="1.0" encoding="UTF-8"?>\n<?{instruction} version="1.0.0"?>\n'.encode()
            + ET.tostring(root, encoding="utf-8") + b"\n")
    (site / "p2.index").write_text(
        "version=1\nmetadata.repository.factory.order=compositeContent.xml,!\n"
        "artifact.repository.factory.order=compositeArtifacts.xml,!\n", encoding="utf-8")
    (site / ".nojekyll").touch()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("incoming", type=Path)
    parser.add_argument("site", type=Path)
    parser.add_argument("release")
    args = parser.parse_args()
    assemble(args.incoming, args.site, args.release)
