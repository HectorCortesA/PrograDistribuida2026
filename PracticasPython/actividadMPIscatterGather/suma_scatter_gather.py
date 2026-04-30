from mpi4py import MPI
import numpy as np
import sys
import time

comm = MPI.COMM_WORLD
rank = comm.Get_rank()
size = comm.Get_size()

# Total de números
N = 1_000_000

# El proceso 0 verifica si el número de procesos es válido
if rank == 0:
    procesos_validos = [10, 20, 40, 50, 100]
    if size not in procesos_validos:
        print(f"Error: se requieren {procesos_validos} procesos, pero se tienen {size}")
        print("Usando oversubscribe: mpiexec -n <N> --oversubscribe python3 script.py")
        sys.exit(1)
    
    print(f"EJECUTANDO CON {size} PROCESOS")
    print(f"Generando {N:,} números aleatorios entre 1 y 1000...")
    
    # Medir tiempo total de ejecución
    start_time = time.time()
    
    data = np.random.randint(1, 1001, N)
    
    # Crear array_to_share (datos a compartir)
    array_to_share = data
    
    # Calcular cómo distribuir los datos
    # El proceso 0 también participa en la suma
    chunk_size = N // size
    chunks = []
    for i in range(size):
        start = i * chunk_size
        if i == size - 1:
            end = N  # El último proceso toma el resto
        else:
            end = (i + 1) * chunk_size
        chunks.append(array_to_share[start:end])
else:
    chunks = None
    array_to_share = None
    start_time = None

# Sincronizar todos los procesos antes de comenzar la medición
comm.Barrier()
# Iniciar medición de tiempo para cada proceso
local_start_time = time.time()

# Distribuir datos a TODOS los procesos (incluyendo el 0)
local_data = comm.scatter(chunks, root=0)

# Cada proceso suma su parte local
local_sum = np.sum(local_data)
print(f"Proceso {rank:3d}: suma = {local_sum:>12,} | elementos = {len(local_data):>6,}")

# Recolectar todos los resultados
all_sums = comm.gather(local_sum, root=0)

# Sincronizar antes de detener la medición
comm.Barrier()
local_end_time = time.time()

# Proceso 0 calcula la suma total
if rank == 0:
    total_sum = sum(all_sums)
    
    # Calcular tiempo total
    end_time = time.time()
    
    print(f"\nRESULTADOS FINALES PARA {size} PROCESOS:")
    print(f"Sumas parciales de cada proceso: {all_sums}")
    print(f"Suma total: {total_sum:,}")
    print(f"Promedio: {total_sum/N:.2f}")
    print(f"\nTIEMPOS DE EJECUCIÓN:")
    print(f"Tiempo total (incluyendo generación de datos): {end_time - start_time:.4f} segundos")
    
# Cada proceso muestra su tiempo individual (opcional)
print(f"Proceso {rank:3d}: tiempo de cómputo local = {local_end_time - local_start_time:.6f} segundos")