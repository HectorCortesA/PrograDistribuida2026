import socket
import json
import time
import uuid
import argparse
import random
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s [CLIENT] %(message)s")

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 6000
TIMEOUT     = 10.0

def send_request(numbers: list[int]) -> tuple[int | None, float]:
    request_id = str(uuid.uuid4())
    payload    = json.dumps({"id": request_id, "numbers": numbers}).encode()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)

    t_start = time.perf_counter()
    sock.sendto(payload, (SERVER_HOST, SERVER_PORT))

    result = None
    try:
        data, _ = sock.recvfrom(4096)
        t_end   = time.perf_counter()
        msg     = json.loads(data.decode())
        if msg.get("id") == request_id:
            result = msg.get("result")
    except socket.timeout:
        t_end = time.perf_counter()
        logging.warning("Timeout esperando respuesta del servidor")
    finally:
        sock.close()

    elapsed_ms = (t_end - t_start) * 1000
    return result, elapsed_ms

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--numbers", type=int, nargs=5, default=None)
    args = parser.parse_args()
    numbers = args.numbers or [random.randint(1, 1000) for _ in range(5)]
    logging.info(f"Enviando: {numbers}")
    result, elapsed = send_request(numbers)
    logging.info(f"MCD = {result}  |  Tiempo: {elapsed:.3f} ms")