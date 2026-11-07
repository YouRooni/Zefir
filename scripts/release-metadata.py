import argparse
import os
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    values = dict(
        line.split("=", 1)
        for line in (root / "version.properties").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )
    number = r"(?:0|[1-9][0-9]*)"
    pattern = rf"v({number}\.{number}\.{number}(?:-(?:alpha|beta|rc)(?:\.[1-9][0-9]*)?)?)"
    match = re.fullmatch(pattern, args.tag)
    if not match:
        parser.error("Expected vX.Y.Z or vX.Y.Z-beta.1 (alpha, beta and rc are supported).")
    version = match[1]
    if values.get("versionName") != version:
        parser.error("Tag must match versionName in version.properties.")
    if not 1 <= int(values.get("versionCode", "0")) <= 2100000000:
        parser.error("versionCode must be a positive Android version code.")
    notes = root / "docs" / "releases" / f"{version}.md"
    if not notes.is_file() or not notes.read_text(encoding="utf-8").strip():
        parser.error(f"Write release notes in docs/releases/{version}.md first.")
    result = {"version": version, "prerelease": str("-" in version).lower()}
    text = "".join(f"{key}={value}\n" for key, value in result.items())
    print(text, end="")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(text)


if __name__ == "__main__":
    main()
