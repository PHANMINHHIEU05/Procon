import glob
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1] if len(sys.argv) > 1 else "AGGREGATE"
tests = failures = errors = skipped = 0
files = sorted(glob.glob("*/target/surefire-reports/TEST-*.xml"))
for path in files:
    root = ET.parse(path).getroot()
    tests += int(root.get("tests", 0))
    failures += int(root.get("failures", 0))
    errors += int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))
print(
    f"{label} reportFiles={len(files)} tests={tests} failures={failures} "
    f"errors={errors} skipped={skipped}"
)
