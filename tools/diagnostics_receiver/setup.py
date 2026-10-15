"""Run once on Ubuntu; keys remain in ignored local files, never printed."""
import os
from pathlib import Path
import re
import secrets


def setup(domain):
    if not re.fullmatch(r"(?=.{3,253}\Z)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", domain):
        raise SystemExit("Enter a domain only, such as logs.example.com")
    if os.name != "posix":
        raise SystemExit("Run setup on your Ubuntu VPS.")
    os.umask(0o077)
    root = Path(__file__).resolve().parent
    for name in ("secrets", "data"):
        (root / name).mkdir(mode=0o700, exist_ok=True)
        (root / name).chmod(0o700)
    for name in ("upload_key", "read_key"):
        path = root / "secrets" / name
        if not path.exists():
            with path.open("x") as output:
                output.write(secrets.token_urlsafe(48) + "\n")
        path.chmod(0o600)
    environment = root / ".env"
    with environment.open("w") as output:
        output.write(f"LOG_DOMAIN={domain.lower()}\nLOCAL_UID={os.getuid()}\nLOCAL_GID={os.getgid()}\n")
    environment.chmod(0o600)
    print("Configuration ready. Keys are in secrets/; do not commit or post them.")


if __name__ == "__main__":
    setup(input("Report domain (for example logs.example.com): ").strip())
