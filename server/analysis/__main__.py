"""CLI: python -m analysis [--latest | --session ID | --list] [--hrmax N]"""

import argparse
import sys

from . import db, report
from .pipeline import analyze


def main(argv=None):
    p = argparse.ArgumentParser(prog="analysis", description="Workout RR/HR analysis")
    p.add_argument("--session", help="session_id to analyze")
    p.add_argument("--latest", action="store_true", help="analyze the most recent session")
    p.add_argument("--list", action="store_true", help="list recent sessions and exit")
    p.add_argument("--environment", default="production", choices=["production", "test"])
    p.add_argument("--hrmax", type=int, default=None, help="max HR for zone breakdown")
    p.add_argument("--out", default=".", help="output directory for report files")
    p.add_argument("--no-files", action="store_true", help="terminal summary only")
    args = p.parse_args(argv)

    if args.list:
        sessions = db.list_sessions(args.environment)
        if not sessions:
            print("No sessions found.")
            return 0
        print(f"{'session_id':38s} {'start':20s} {'beats':>6s}")
        for sid, _dev, start_ms, _end_ms, beats in sessions:
            from datetime import datetime, timezone
            start = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
            print(f"{sid:38s} {start:20s} {beats:6d}")
        return 0

    session_id = args.session
    if args.latest:
        session_id = db.latest_session(args.environment)
    if not session_id:
        p.error("provide --session ID, --latest, or --list")

    beats = db.load_beats(session_id=session_id, environment=args.environment)
    if not beats:
        print(f"No data for session {session_id}", file=sys.stderr)
        return 1

    result = analyze(beats, hrmax=args.hrmax)
    print(report.terminal_summary(session_id, result))

    if not args.no_files:
        json_path = report.write_json(session_id, result, args.out)
        png_path = report.write_png(session_id, result, args.out, beats=beats)
        print(f"\n  wrote {json_path}")
        print(f"  wrote {png_path}" if png_path else "  (PNG skipped — matplotlib not installed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
