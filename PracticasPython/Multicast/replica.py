import socket
import struct
import json
import time
import argparse
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [REPLICA-%(name)s] %(message)s")

MULTICAST_GROUP   = "224.1.1.1"
MULTICAST_PORT    = 5007
SERVER_HOST       = "127.0.0.1"
SERVER_REPLY_PORT = 6001
LOCAL_IP          = "192.168.100.18"

def gcd_euclid(a: int, b: int) -> int:
    a, b = abs(a), abs(b)
    while b:
        a, b = b, a % b
    return a

def gcd_vector(numbers: list) -> int:
    result = numbers[0]
    for n in numbers[1:]:
        result = gcd_euclid(result, n)
    return result

class Replica:
    def __init__(self, replica_id: int, delay_ms: int = 0):
        self.replica_id = replica_id
        self.delay_ms   = delay_ms
        self.logger     = logging.getLogger(str(replica_id))

    def _join_multicast(self) -> socket.socket:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", MULTICAST_PORT))
        mreq = struct.pack("4s4s",
                           socket.inet_aton(MULTICAST_GROUP),
                           socket.inet_aton(LOCAL_IP))
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        return sock

    def _send_result(self, request_id: str, result: int):
        reply = json.dumps({"id": request_id, "result": result, "replica": self.replica_id}).encode()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.sendto(reply, (SERVER_HOST, SERVER_REPLY_PORT))
        sock.close()

    def run(self):
        sock = self._join_multicast()
        self.logger.info(f"Réplica {self.replica_id} escuchando en {MULTICAST_GROUP}:{MULTICAST_PORT}")
        while True:
            try:
                data, _ = sock.recvfrom(4096)
                msg = json.loads(data.decode())
                request_id = msg["id"]
                numbers    = msg["numbers"]
                if self.delay_ms > 0:
                    time.sleep(self.delay_ms / 1000.0)
                result = gcd_vector(numbers)
                self.logger.info(f"MCD{numbers} = {result}  (id={request_id})")
                self._send_result(request_id, result)
            except Exception as e:
                self.logger.error(f"Error: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--id",    type=int, default=1)
    parser.add_argument("--delay", type=int, default=0)
    args = parser.parse_args()
    Replica(replica_id=args.id, delay_ms=args.delay).run()