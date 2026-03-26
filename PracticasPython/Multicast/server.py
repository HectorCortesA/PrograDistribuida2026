import socket
import struct
import threading
import time
import json
import argparse
import logging
from collections import Counter

logging.basicConfig(level=logging.INFO, format="%(asctime)s [SERVER] %(message)s")

MULTICAST_GROUP = "224.1.1.1"
MULTICAST_PORT  = 5007
CLIENT_PORT     = 6000
NUM_REPLICAS    = 3
LOCAL_IP        = "192.168.100.18"

def gcd_euclid(a: int, b: int) -> int:
    while b:
        a, b = b, a % b
    return a

def gcd_vector(numbers: list[int]) -> int:
    result = numbers[0]
    for n in numbers[1:]:
        result = gcd_euclid(result, n)
    return result

class MulticastServer:
    def __init__(self, delivery_mode: int):
        assert delivery_mode in (1, 2, 3)
        self.delivery_mode = delivery_mode
        self._lock = threading.Lock()

    def _send_multicast(self, request_id: str, numbers: list[int]):
        payload = json.dumps({"id": request_id, "numbers": numbers}).encode()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                        socket.inet_aton(LOCAL_IP))
        sock.sendto(payload, (MULTICAST_GROUP, MULTICAST_PORT))
        sock.close()
        logging.info(f"Multicast enviado → id={request_id}, numbers={numbers}")

    def _collect_responses(self, request_id: str, timeout: float = 5.0) -> list[int]:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", CLIENT_PORT + 1))
        sock.settimeout(timeout)
        responses = []
        deadline = time.time() + timeout
        while time.time() < deadline and len(responses) < NUM_REPLICAS:
            try:
                data, _ = sock.recvfrom(1024)
                msg = json.loads(data.decode())
                if msg.get("id") == request_id:
                    responses.append(msg["result"])
                    logging.info(f"Respuesta réplica #{len(responses)}: {msg['result']}")
            except socket.timeout:
                break
        sock.close()
        return responses

    def _decide(self, responses: list[int]) -> int | None:
        if not responses:
            return None
        if self.delivery_mode == 1:
            return responses[0]
        elif self.delivery_mode == 2:
            if len(responses) == NUM_REPLICAS and len(set(responses)) == 1:
                return responses[0]
            return None
        else:
            threshold = NUM_REPLICAS // 2 + 1
            counts = Counter(responses)
            majority, freq = counts.most_common(1)[0]
            return majority if freq >= threshold else None

    def _collect_first(self, request_id: str, timeout: float = 5.0) -> int | None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", CLIENT_PORT + 1))
        sock.settimeout(timeout)
        result = None
        try:
            while True:
                data, _ = sock.recvfrom(1024)
                msg = json.loads(data.decode())
                if msg.get("id") == request_id:
                    result = msg["result"]
                    logging.info(f"Primera respuesta recibida: {result}")
                    break
        except socket.timeout:
            pass
        sock.close()
        return result

    def _handle_client(self, data: bytes, client_addr: tuple, server_sock: socket.socket):
        try:
            msg = json.loads(data.decode())
            request_id = msg["id"]
            numbers    = msg["numbers"]
            logging.info(f"Cliente {client_addr} → id={request_id}, numbers={numbers}")
            self._send_multicast(request_id, numbers)
            if self.delivery_mode == 1:
                result = self._collect_first(request_id)
            else:
                responses = self._collect_responses(request_id)
                result    = self._decide(responses)
            reply = json.dumps({"id": request_id, "result": result}).encode()
            server_sock.sendto(reply, client_addr)
            logging.info(f"Respuesta al cliente {client_addr}: {result}")
        except Exception as e:
            logging.error(f"Error procesando petición: {e}")

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", CLIENT_PORT))
        logging.info(f"Servidor en puerto {CLIENT_PORT} | Modo: {self.delivery_mode}")
        while True:
            data, addr = sock.recvfrom(4096)
            threading.Thread(target=self._handle_client, args=(data, addr, sock), daemon=True).start()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", type=int, choices=[1, 2, 3], default=1)
    args = parser.parse_args()
    MulticastServer(delivery_mode=args.mode).run()