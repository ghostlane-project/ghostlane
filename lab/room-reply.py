"""Reads one reply of the tunnel helper from its input.

  room-reply.py summary          what it answered, in a line
  room-reply.py field <name>     one field of it (0 when it has none)
  room-reply.py tail <how many>  what the tunnel's sing-box said: the lines about
                                 a connection it was asked for, or about something
                                 going wrong, with nothing long enough to be a secret
"""
import json
import re
import sys

what = sys.argv[1]
try:
    reply = json.loads(sys.stdin.readline())
except Exception:
    reply = None

if what == "summary":
    if reply is None:
        print("no reply")
    else:
        print("ok=%s state=%s hold=%s line=%s error=%s" % (
            reply.get("ok"), reply.get("state"), reply.get("hold"),
            "yes" if reply.get("line") else "no", reply.get("error")))
elif what == "field":
    print((reply or {}).get(sys.argv[2], 0))
elif what == "tail":
    tail = (reply or {}).get("logTail", "")
    said = [line for line in tail.split("\n") if line]
    keep = re.compile(r"ERROR|WARN|FATAL|verify-in|outbound/socks|started|dns:")
    colours = re.compile(r"\x1b\[[0-9;]*m")
    kept = [re.sub(r"[0-9A-Za-z+/=_-]{24,}", "<id>", colours.sub("", line))[:170] for line in said if keep.search(line)]
    for line in kept[-int(sys.argv[2]):]:
        print("        " + line)
    print("        (%d lines of %d kept)" % (len(kept), len(said)))
