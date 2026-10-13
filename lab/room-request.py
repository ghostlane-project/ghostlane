"""One request to the tunnel helper, as a line of JSON.

  room-request.py <work> start <config's name> <utun>
  room-request.py <work> line <program> <output> <arguments…>
  room-request.py <work> stop [keep] | line-stop | status | release
"""
import json
import sys

work, verb, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
request = {"verb": verb}
if verb == "start":
    request["config"] = open("%s/%s.json" % (work, rest[0])).read()
    request["names"] = "172.19.0.2"
    request["hold"] = {"interface": rest[1], "pass": []}
elif verb == "line":
    request["program"], request["output"], request["arguments"] = rest[0], rest[1], rest[2:]
elif rest:
    request["hold"] = rest[0]
print(json.dumps(request))
