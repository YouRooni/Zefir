import base64
import os
from pathlib import Path


def property_value(value):
    escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ ")
    result = []
    for char in escaped:
        if ord(char) < 128:
            result.append(char)
        else:
            encoded = char.encode("utf-16-be")
            result.extend(f"\\u{int.from_bytes(encoded[i:i + 2], 'big'):04x}" for i in range(0, len(encoded), 2))
    return "".join(result)


root = Path(__file__).resolve().parent.parent
required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
missing = [key for key in required if not os.environ.get(key)]
if missing:
    raise SystemExit("Missing signing secrets: " + ", ".join(missing))
(root / "release.jks").write_bytes(base64.b64decode(os.environ[required[0]], validate=True))
properties = {
    "KEYSTORE_FILE": "release.jks",
    "KEYSTORE_PASSWORD": os.environ[required[1]],
    "KEY_ALIAS": os.environ[required[2]],
    "KEY_PASSWORD": os.environ[required[3]],
}
(root / "keystore.properties").write_text(
    "".join(f"{key}={property_value(value)}\n" for key, value in properties.items()),
    encoding="iso-8859-1", newline="\n",
)
