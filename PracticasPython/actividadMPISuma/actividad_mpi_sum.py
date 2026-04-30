from mpi4py import MPI

comm = MPI.COMM_WORLD
rank = comm.Get_rank()
size = comm.Get_size()

# N grande (puedes cambiarlo)
N = 10**11  

# División del trabajo
chunk = N // size
inicio = rank * chunk
fin = inicio + chunk

# Último proceso toma el sobrante
if rank == size - 1:
    fin = N

# 🔥 Suma eficiente (evitar loops enormes)
# suma de rango: inicio ... fin-1
suma_local = (fin - 1 + inicio) * (fin - inicio) // 2

print(f"[Proceso {rank}] rango: {inicio} - {fin-1}, suma_local: {suma_local}")

# Reducción con MPI.SUM
suma_total = comm.reduce(suma_local, op=MPI.SUM, root=0)

# Resultado final solo en root
if rank == 0:
    print("\nResultado final:")
    print(f"Suma total de 0 a {N-1} = {suma_total}")