import random
import time
import uuid
from collections import Counter
from math import gcd as _gcd
from functools import reduce
import csv
import statistics
import os

NUM_EXECUTIONS = 60
NUM_REPLICAS   = 3

REPLICA_LATENCIES = [
    (5,  15),
    (10, 30),
    (20, 60),
]

def gcd_euclid(a: int, b: int) -> int:
    a, b = abs(a), abs(b)
    while b:
        a, b = b, a % b
    return a

def gcd_vector(numbers: list[int]) -> int:
    result = numbers[0]
    for n in numbers[1:]:
        result = gcd_euclid(result, n)
    return result

def simulate_replicas(numbers: list[int]) -> list[tuple[float, int]]:
    responses = []
    true_result = gcd_vector(numbers)
    for i, (lo, hi) in enumerate(REPLICA_LATENCIES):
        latency = random.uniform(lo, hi)
        if random.random() < 0.05:
            result = true_result + random.choice([1, -1, 2])
        else:
            result = true_result
        responses.append((latency, result))
    responses.sort(key=lambda x: x[0])
    return responses

def mode1(responses: list[tuple[float, int]]) -> tuple[float, int | None]:
    lat, res = responses[0]
    return lat, res

def mode2(responses: list[tuple[float, int]]) -> tuple[float, int | None]:
    last_lat = responses[-1][0]
    results  = [r for _, r in responses]
    if len(set(results)) == 1:
        return last_lat, results[0]
    return last_lat, None

def mode3(responses: list[tuple[float, int]]) -> tuple[float, int | None]:
    threshold = NUM_REPLICAS // 2 + 1
    for i in range(threshold, len(responses) + 1):
        partial  = [r for _, r in responses[:i]]
        lat      = responses[i - 1][0]
        counts   = Counter(partial)
        majority, freq = counts.most_common(1)[0]
        if freq >= threshold:
            return lat, majority
    last_lat = responses[-1][0]
    return last_lat, None

def run_benchmark(n: int = NUM_EXECUTIONS) -> list[dict]:
    rows = []
    for i in range(1, n + 1):
        numbers  = [random.randint(1, 500) for _ in range(5)]
        expected = gcd_vector(numbers)
        responses = simulate_replicas(numbers)
        t1, r1 = mode1(responses)
        t2, r2 = mode2(responses)
        t3, r3 = mode3(responses)
        rows.append({
            "exec":     i,
            "numbers":  numbers,
            "expected": expected,
            "t1_ms":    round(t1, 3),
            "r1":       r1,
            "ok1":      r1 == expected,
            "t2_ms":    round(t2, 3),
            "r2":       r2,
            "ok2":      r2 == expected,
            "t3_ms":    round(t3, 3),
            "r3":       r3,
            "ok3":      r3 == expected,
        })
    return rows

def export_csv(rows: list[dict], path: str):
    fields = ["exec", "numbers", "expected",
              "t1_ms", "r1", "ok1",
              "t2_ms", "r2", "ok2",
              "t3_ms", "r3", "ok3"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for row in rows:
            w.writerow({**row, "numbers": str(row["numbers"])})

def summary(rows: list[dict]) -> dict:
    def stats(key):
        vals = [r[key] for r in rows]
        return {
            "min":    round(min(vals), 3),
            "max":    round(max(vals), 3),
            "avg":    round(statistics.mean(vals), 3),
            "median": round(statistics.median(vals), 3),
            "stdev":  round(statistics.stdev(vals), 3),
        }
    return {
        "Modo 1 (Primera)": {**stats("t1_ms"), "accuracy": f"{sum(r['ok1'] for r in rows)}/{len(rows)}"},
        "Modo 2 (Todas)":   {**stats("t2_ms"), "accuracy": f"{sum(r['ok2'] for r in rows)}/{len(rows)}"},
        "Modo 3 (Mayoría)": {**stats("t3_ms"), "accuracy": f"{sum(r['ok3'] for r in rows)}/{len(rows)}"},
    }

if __name__ == "__main__":
    rows = run_benchmark(NUM_EXECUTIONS)
    summ = summary(rows)
    out_dir = "/mnt/user-data/outputs"
    os.makedirs(out_dir, exist_ok=True)
    export_csv(rows, f"{out_dir}/benchmark_results.csv")