#!/usr/bin/env python3
"""Read selected MarketPlus Chrome tabs without changing settings or transmitting products."""
import argparse
import json
from pathlib import Path
import subprocess


def run_browser(javascript, selector, list_only=False):
    if not list_only and not selector:
        raise ValueError("A non-empty page selector is required")
    # JSON quoting here builds an AppleScript string literal, never a shell command.
    script = """on run argv
set selectedTabs to {}
set pageSelector to item 1 of argv
tell application "Google Chrome"
  repeat with w in windows
    repeat with t in tabs of w
      set pageUrl to URL of t
      if (pageUrl starts with "https://mp.cafe24.com/mp/") and (pageSelector is "" or pageUrl contains pageSelector) then
        set end of selectedTabs to contents of t
      end if
    end repeat
  end repeat
  if item 2 of argv is "list" then
    set resultText to ""
    repeat with t in selectedTabs
      set resultText to resultText & (title of t) & " | " & (URL of t) & linefeed
    end repeat
    return resultText
  end if
  if (count selectedTabs) is not 1 then error "UNIQUE_MARKETPLUS_TAB_REQUIRED"
  return execute (item 1 of selectedTabs) javascript SCRIPT_LITERAL
end tell
end run
""".replace("SCRIPT_LITERAL", json.dumps(javascript, ensure_ascii=False))
    result = subprocess.run(
        ["/usr/bin/osascript", "-", selector, "list" if list_only else "read"],
        input=script, text=True, capture_output=True, timeout=25,
    )
    if result.returncode:
        raise ValueError(result.stderr.strip())
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("selector", nargs="?", default="", help="Unique URL substring for a snapshot")
    parser.add_argument("--list", action="store_true", help="List only MarketPlus tab titles and URLs")
    args = parser.parse_args()
    if not args.list and not args.selector:
        parser.error("A non-empty page selector is required for a snapshot")
    javascript = (Path(__file__).with_name("marketplus-snapshot.js")).read_text()
    try:
        result = run_browser(javascript, args.selector, args.list)
    except (ValueError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"{error}\n")
    if args.list:
        print(result or "MARKETPLUS_TAB_NOT_FOUND")
    else:
        print(json.dumps(json.loads(result), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
