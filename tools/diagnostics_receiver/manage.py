"""Run on the VPS to retrieve/delete reports without exposing the read key in shell arguments."""
import argparse
import json
from pathlib import Path
from receiver import Inbox, ID_PATTERN


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["list", "get", "delete"])
    parser.add_argument("report_id", nargs="?")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    inbox = Inbox(root / "data", (root / "secrets/upload_key").read_text().strip(), (root / "secrets/read_key").read_text().strip())
    if args.action == "list":
        result = inbox.list()
    else:
        if not args.report_id or not ID_PATTERN.fullmatch(args.report_id):
            parser.error("A valid report ID is required")
        result = inbox.get(args.report_id) if args.action == "get" else {"deleted": inbox.delete(args.report_id)}
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
