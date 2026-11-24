#!/usr/bin/env bash
# Build validation (completion spec v1.1 §10.3): an app bundle or APK of one environment must not contain the other
# environment's endpoints, and a prod build must not contain the local debug server.
#   scripts/check-environment.sh prod app/build/outputs/bundle/prodRelease/app-prod-release.aab
set -euo pipefail
env="${1:?prod or dev}"; artifact="${2:?aab or apk}"
"${PYTHON:-python3}" - "$env" "$artifact" <<'PY'
import sys, zipfile
env, artifact = sys.argv[1], sys.argv[2]
if env == "prod":
    forbidden = ["https://api.dev.alondra1.com", "https://config.dev.alondra1.com", "https://config2.dev.alondra1.com", "http://10.0.2.2"]
    required = "https://api.alondra1.com"
else:
    forbidden = ["https://api.alondra1.com", "https://config.alondra1.com", "https://config2.alondra1.com"]
    required = "https://api.dev.alondra1.com"
with zipfile.ZipFile(artifact) as z:
    dex = [z.read(n) for n in z.namelist() if n.endswith(".dex")]
if not dex:
    sys.exit(f"no dex files in {artifact}")
problems = [f"{artifact} contains {bad}" for bad in forbidden if any(bad.encode() in d for d in dex)]
if not any(required.encode() in d for d in dex):
    problems.append(f"{artifact} does not contain {required} (wrong flavor?)")
for p in problems:
    print("FAIL:", p, file=sys.stderr)
if problems:
    sys.exit(1)
print(f"ok: {artifact} belongs to {env}")
PY
