#!/usr/bin/env python3
"""Read the CPU diode from ArtemLab Cooling and serve it over LAN HTTPS."""

import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
from ipaddress import ip_address, ip_network
import json
import ssl
import subprocess
import time


def reading():
    result = subprocess.run(
        ["/usr/local/libexec/artemlab-cooling", "--status"],
        capture_output=True,
        check=True,
        text=True,
        timeout=2,
    )
    sample = json.loads(result.stdout)
    temperature = sample["cpu_TC0D"]
    age = time.time() - sample["time"]
    if not 0 <= age <= 30 or not -20 <= temperature <= 125:
        raise ValueError("unavailable CPU reading")
    return {"cpuTempC": temperature}


class TemperatureRequest(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/temperature":
            self.send_error(404)
            return
        try:
            value = reading()
        except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
            status, value = 503, {"cpuTempC": None}
        else:
            status = 200
        response = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, *_args):
        pass


class LocalHttpsServer(HTTPServer):
    def __init__(self, address, subnet, tls):
        self.subnet = subnet
        self.tls = tls
        super().__init__(address, TemperatureRequest)

    def get_request(self):
        connection, peer = super().get_request()
        try:
            if ip_address(peer[0]) not in self.subnet:
                raise OSError("outside the configured subnet")
            connection.settimeout(3)
            return self.tls.wrap_socket(connection, server_side=True), peer
        except OSError:
            connection.close()
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, type=ip_address, help="Mac LAN IPv4 address")
    parser.add_argument("--allow", required=True, type=ip_network, help="allowed IPv4 subnet")
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--cert", required=True, help="TLS certificate in PEM format")
    parser.add_argument("--key", required=True, help="TLS private key in PEM format")
    args = parser.parse_args()
    if args.bind.version != 4 or args.bind not in args.allow or args.bind.is_unspecified:
        parser.error("bind must be a specific IPv4 address on the allowed subnet")
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(args.cert, args.key)
    with LocalHttpsServer((str(args.bind), args.port), args.allow, tls) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
