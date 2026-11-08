#!/bin/bash
# Regenerates the README pictures (docs/screenshots) from ReadmeScreenshotTest:
# Robolectric renders the screens on the JVM with demo data, then the phone screens are scaled
# down and every PNG is re-encoded losslessly with Pillow (python3-pil).
#
# Needs: an Android SDK (local.properties) and your EDIABAS Ecu folder for the fault and Tool32
# pictures, e.g.  BIMMERDATA_ECU_DIR=/path/to/EDIABAS/Ecu tools/readme_screenshots.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${BIMMERDATA_ECU_DIR:?set BIMMERDATA_ECU_DIR to your EDIABAS Ecu folder}"
export BIMMERDATA_ECU_DIR
RAW="$PWD/app/build/screenshots/readme"
rm -rf "$RAW"
./gradlew --console=plain -q :app:testDebugUnitTest -Pscreenshots --tests '*ReadmeScreenshotTest*' --rerun

python3 - "$RAW" docs/screenshots <<'PY'
import os, sys
from PIL import Image

raw, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
PHONE_WIDTH = 540  # phone screens are rendered at 1233 px (411 dp, xxhdpi)
for name in sorted(os.listdir(raw)):
    if not name.endswith(".png"):
        continue
    im = Image.open(os.path.join(raw, name)).convert("RGB")
    if im.width > 1000 and im.height > im.width:  # phone screen
        im = im.resize((PHONE_WIDTH, round(im.height * PHONE_WIDTH / im.width)), Image.LANCZOS)
    path = os.path.join(out, name)
    im.save(path, optimize=True)
    print(f"{path}: {im.width}x{im.height}, {os.path.getsize(path) // 1024} KB")
PY
