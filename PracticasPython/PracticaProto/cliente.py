def run_client():
    with grpc.insecure_channel('localhost:8000') as channel :
        