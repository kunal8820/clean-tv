#!/usr/bin/env python3
"""Read or set the launcher's Home tile order from a computer, instead of carrying tiles one by one.

  TV_HOST=<tv ip> tools/order.py get > order.txt     # the saved order, then every other TV app, one per line
  (edit order.txt: put the lines in the order you want, delete the ones you don't care about)
  TV_HOST=<tv ip> tools/order.py set order.txt       # Home shows those first, in that order
  TV_HOST=<tv ip> tools/order.py clear               # back to dock_apps and A to Z

A line is a package name (com.netflix.ninja) or a component (ar.tvplayer.tv/.ui.MainActivity); # starts a
comment, and `get` writes each app's name there. Apps you leave out keep following the usual rule after the listed
ones. The order is saved as the reorder button saves it, so it is kept until dock_apps changes (a rebuild or a
theme push with another dock); `get` says when a saved order no longer applies.

Before `set` and `clear` change anything, the order on the TV is copied to ~/.cleanyourtv/order-<tv>-<time>.txt,
and `set` of that file puts it back. `get > file` before a factory reset or a reinstall keeps a copy as well.
"""
import datetime
import os
import re
import sys

PACKAGE = "com.gathin.tvhome"
URI = "content://" + PACKAGE + ".order"
ENTRY = re.compile(r"[A-Za-z0-9_.]+(/[A-Za-z0-9_.$]+)?")
KEYS = ("order", "stale", "labels", "saved", "unknown")


def shell(dev, command):
    # adb_shell does not return the remote exit code: a final marker tells a failed command from an empty answer.
    output = dev.shell(command + "; rc=$?; printf '\\n__CYT_ORDER_RC__%s\\n' \"$rc\"", read_timeout_s=30)
    result = re.search(r"\n__CYT_ORDER_RC__(\d+)\s*$", output)
    if not result or result[1] != "0":
        raise RuntimeError("TV command failed or returned an incomplete response")
    text = output[:result.start()].strip()
    if re.search(r"(?mi)^\s*(Error|Exception|Permission Denial|Security exception)", text):
        raise RuntimeError(text.splitlines()[0] if text else "TV refused the command")
    return text


def call(dev, method, arg=None):
    """content call on OrderProvider. The Bundle comes back as Bundle[{key=value, ...}] text: split it on the
    known keys, so an app name holding ", " cannot cut a value short."""
    command = f"content call --uri {URI} --method {method}"
    if arg is not None:
        command += f" --arg '{arg}'"
    text = shell(dev, command)
    body = re.search(r"Bundle\[\{(.*)\}\]", text, re.S)
    if not body:
        raise RuntimeError("the launcher did not answer; is it installed and up to date? " + text[:200])
    starts = sorted((m.start(), m.end(), m[1]) for m in
                    re.finditer(r"(?:^|, )(" + "|".join(KEYS) + r")=", body[1]))
    fields = {}
    for i, (_, end, key) in enumerate(starts):
        stop = starts[i + 1][0] if i + 1 < len(starts) else len(body[1])
        fields.setdefault(key, body[1][end:stop])
    return fields


def lines(value):
    return [v for v in (value or "").split("\n") if v]


def render(fields):
    """The order as an editable text file: listed tiles first, then the rest, each with its name as a comment."""
    names = dict(l.split("\t", 1) for l in lines(fields.get("labels")) if "\t" in l)
    saved, stale = lines(fields.get("order")), lines(fields.get("stale"))
    out = ["# Home tile order: first line = first tile. Packages or components, one per line; # starts a comment."]
    if stale:
        out.append("# A saved order no longer applies: dock_apps changed after it was saved, so Home ignores it.")
        out.append("# These lines are that order; `set` this file to use it again.")
    listed = saved or stale
    for k in listed:
        out.append(f"{k}  # {names[k]}" if k in names else k)
    packages = {k.split("/", 1)[0] for k in listed}
    rest = [k for k in names if k not in listed and k.split("/", 1)[0] not in packages
            and not k.startswith(PACKAGE + "/")]
    if rest:
        out.append("# Not in the saved order (they follow by the usual rule):")
        out.extend(f"{k}  # {names[k]}" for k in rest)
    return "\n".join(out) + "\n"


def backup(fields):
    """Copy the order on the TV before it changes, or None when there was none to lose."""
    if not lines(fields.get("order")) and not lines(fields.get("stale")):
        return None
    folder = os.path.join(os.path.expanduser("~"), ".cleanyourtv")
    os.makedirs(folder, exist_ok=True)
    host = re.sub(r"[^A-Za-z0-9._-]", "_", os.environ.get("TV_HOST", "tv"))
    path = os.path.join(folder, f"order-{host}-{datetime.datetime.now():%Y%m%d-%H%M%S}.txt")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(render(fields))
    return path


def entries(text):
    """Same rule as the launcher's Order.entries: lines or commas, # comments, blanks and repeats left out."""
    out = []
    for line in text.splitlines():
        for part in line.split("#", 1)[0].split(","):
            e = part.strip()
            if e and e not in out:
                out.append(e)
    return out


def main():
    args = sys.argv[1:]
    if not args or args[0] not in ("get", "set", "clear") or (args[0] == "set") != (len(args) == 2):
        print((__doc__ or "").strip(), file=sys.stderr)
        return 2
    wanted = []
    if args[0] == "set":
        with open(args[1], encoding="utf-8") as f:
            wanted = entries(f.read())
        bad = [e for e in wanted if not ENTRY.fullmatch(e)]
        if bad:
            print("Not a package or component: " + ", ".join(bad), file=sys.stderr)
            return 1
        if not wanted:
            print("The file lists no app.", file=sys.stderr)
            return 1
    # The caller may use Python -I, which leaves the script directory out of sys.path.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    dev = None
    try:
        from tv import connect
        dev = connect()
        before = call(dev, "get")
        if args[0] == "get":
            # `get > order.txt` on Windows would write the console code page (cp1252), so app names with accents
            # come back garbled and `set` reads the file as UTF-8: write UTF-8 whenever the output is a file.
            if not sys.stdout.isatty():
                sys.stdout.reconfigure(encoding="utf-8")
            sys.stdout.write(render(before))
            if lines(before.get("stale")):
                print("Note: the saved order no longer applies (dock_apps changed); `set` it to use it again.",
                      file=sys.stderr)
            return 0
        copy = backup(before)
        if copy:
            print(f"Previous order copied to {copy} (`set` that file to put it back).")
        if args[0] == "set":
            result = call(dev, "set", ",".join(wanted))
            print(f"Saved {result.get('saved', '?')} tiles; Home shows them first.")
            unknown = lines(result.get("unknown"))
            if unknown:
                print("Not installed, left out: " + ", ".join(unknown))
        else:
            call(dev, "clear")
            print("Saved order removed: Home is back to dock_apps and A to Z.")
    except Exception as error:
        print(f"order.py failed: {error}", file=sys.stderr)
        return 1
    finally:
        if dev is not None:
            dev.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
