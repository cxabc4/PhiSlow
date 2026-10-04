"""Package the public source using an allowlist, without any chart or music files."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
VERSION = ElementTree.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().attrib[
    "{http://schemas.android.com/apk/res/android}versionName"
]
OUTPUT = ROOT / f"PhiSlow-Source-{VERSION}.zip"
files = [ROOT / name for name in (
    "README.md", "LICENSE", "NOTICE", "build.ps1", ".gitignore",
    "app/src/main/AndroidManifest.xml", "tools/normalize_zip_paths.ps1",
    "tools/package_source.py",
)]
for folder in (
    "app/src/main/java", "app/src/main/res", "app/src/main/assets/noteskin",
    "app/src/main/assets/licenses", "tests", "design/Phislow-Clear-v1",
):
    for path in (ROOT / folder).rglob("*"):
        if not path.is_file() or "__pycache__" in path.parts:
            continue
        if path.suffix.lower() not in (".java", ".xml", ".ps1", ".py", ".md", ".txt", ".json", ".png", ".svg", ".yml", ".yaml"):
            continue
        files.append(path)
with ZipFile(OUTPUT, "w", ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(set(files)):
        archive.write(path, path.relative_to(ROOT).as_posix())
print(f"Source: {OUTPUT} ({OUTPUT.stat().st_size:,} bytes, {len(set(files))} files)")
