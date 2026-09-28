#!/usr/bin/env python3
"""Decode the repository signing secret into runner-private temporary files; never log it."""
import base64
import json
import os
from pathlib import Path

secret = os.environ.get("ANDROID_SIGNING_BUNDLE", "")
if not secret:
    raise SystemExit("Missing ANDROID_SIGNING_BUNDLE repository secret. See docs/UPDATES.md; refusing a randomly signed build.")
bundle = json.loads(base64.b64decode(secret, validate=True))
root = Path(os.environ["RUNNER_TEMP"]) / "boox-signing"
root.mkdir(mode=0o700, exist_ok=True)
key = root / "release.jks"
key.write_bytes(base64.b64decode(bundle["keystore"], validate=True))
password = bundle["password"]
alias = bundle["alias"]
if not all(c.isalnum() or c in "-_" for c in password + alias):
    raise SystemExit("Signing password and alias must be alphanumeric (hyphen/underscore allowed).")
properties = root / "keystore.properties"
properties.write_text(f"storeFile={key}\nstorePassword={password}\nkeyPassword={password}\nkeyAlias={alias}\n")
key.chmod(0o600)
properties.chmod(0o600)
with open(os.environ["GITHUB_ENV"], "a") as output:
    output.write(f"BOOX_SIGNING_PROPERTIES={properties}\n")
