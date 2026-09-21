"""Download pinned JUnit dependencies to ignored build storage (once, explicitly)."""
import hashlib
from pathlib import Path
from urllib.request import urlopen

destination = Path(__file__).resolve().parents[2] / "build/synthetic-benchmark/toolchain"
destination.mkdir(parents=True, exist_ok=True)
assets = [
    ("junit/junit/4.13.2/junit-4.13.2.jar", "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"),
    ("org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar", "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"),
]
for artifact, digest in assets:
    path = destination / artifact.rsplit("/", 1)[1]
    data = path.read_bytes() if path.exists() else urlopen("https://repo.maven.apache.org/maven2/" + artifact, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise SystemExit("Checksum mismatch: " + path.name)
    path.write_bytes(data)
    print("Verified " + path.name)
