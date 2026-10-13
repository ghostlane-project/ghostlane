"""A tunnel's config as the app writes one for a room, made from the app's own reference.

  room-config.py <work> <way out> <reference> <engine> <its copy> <user> <password> \
                 <name> <utun> <the line's port> <the tunnel's own listener> <login: yes|no>
"""
import json
import sys

work, way_out, reference, engine, copy, user, password, name, utun, port, verify, login = sys.argv[1:13]
config = json.load(open(reference))
config["log"]["level"] = "info"
# A room's names take the reliable way: over TCP, through the line.
config["dns"]["servers"][0]["type"] = "tcp"
tun, listener = config["inbounds"]
tun.pop("route_exclude_address", None)
tun["interface_name"] = utun
listener["listen_port"] = int(verify)
out, direct = config["outbounds"]
out["server_port"] = int(port)
if login == "yes":
    out["username"], out["password"] = user, password
direct["bind_interface"] = way_out
lines = [engine, copy]
config["dns"]["rules"][2]["process_path"] = lines
config["route"]["rules"][2]["process_path"] = lines
json.dump(config, open("%s/%s.json" % (work, name), "w"))
