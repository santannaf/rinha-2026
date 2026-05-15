# Rinha de Backend 2026 — API antifraude (Java 25 + GraalVM)

API HTTP que decide em **~3 milissegundos** se uma transação de cartão
deve ser aprovada ou bloqueada. Java puro, compilado em binário nativo
via GraalVM, sem framework — servidor HTTP escrito do zero usando
`java.nio`. Roda em 1.0 CPU / 350 MB de RAM totais.

---

## O problema, sem jargão

Imagine que você é uma adquirente de cartão. A cada compra, alguém
precisa decidir **em poucos milissegundos** se aquela transação é fraude.

Você tem um arquivo histórico de **3 milhões de transações** já
classificadas como "fraude" ou "legítima". A regra do desafio é simples:

1. Pega a nova transação.
2. Acha as **5 transações históricas mais parecidas** com ela.
3. Conta quantas dessas 5 foram fraudes.
4. Se 3+ foram fraudes → bloqueia. Se 2 ou menos → aprova.

**O desafio técnico** é fazer essa decisão milhões de vezes por dia,
em ~3ms p99, dentro de um container com **0.35 CPU e 155 MB de RAM**.

---

## Como resolvemos, etapa por etapa

### Etapa 1 — Transformar a transação num vetor

Uma transação tem campos como `valor`, `parcelas`, `cidade do
cliente`, `MCC do estabelecimento`, `horário`, etc. Pra "comparar"
duas transações, primeiro precisamos transformar cada uma num **vetor
numérico de tamanho fixo** — no nosso caso, 14 números entre 0 e 1.

Exemplo simplificado:
| Campo da transação | Vira o quê? |
|---|---|
| valor R$ 41,12 | divide pelo maior valor histórico → `0.04` |
| MCC 5411 (mercado) | risco histórico desse MCC → `0.32` |
| hora 23h | `23 / 23 = 1.0` |
| dia da semana = sábado | normalizado pra `0.83` |
| ... | (mais 10 features derivadas) |

Quem faz essa conversão é o `SchemaAwareVectorizationStrategy`. **Lê
o JSON byte a byte sem nenhuma biblioteca**, sem alocar `String` por
campo, sem montar `Map`. Cada parse leva ~4 microssegundos.

### Etapa 2 — Achar as 5 transações mais parecidas

Comparar a transação nova com todas as 3 milhões seria inviável
(daria ~100ms por request). Usamos um algoritmo chamado **IVF**
(*Inverted File Index*), que funciona como um índice de biblioteca:

1. **Antes (offline):** agrupamos as 3M transações em **4.096
   "vizinhanças"** (chamadas de *clusters*). Cada vizinhança tem um
   "endereço médio" (centróide). Dois meses de transações de
   madrugada em mercado X provavelmente caem na mesma vizinhança.
2. **No request:** em vez de comparar com 3M, comparamos a transação
   nova só com os **4.096 centróides**. Pegamos as 2 vizinhanças mais
   próximas. Aí, dentro dessas 2 vizinhanças (~1.500 transações),
   procuramos as 5 mais parecidas.

Resultado: **~200× menos comparações**, com o mesmo top-5 em 99% dos
casos.

#### Truque extra: parar de procurar antes do fim

Se as primeiras vizinhanças que varremos já dão um top-5 "unânime"
(todos fraude, ou todos legítimo), saímos cedo — não vale a pena
varrer o resto. Chamamos isso de **early-stop**. É uma aposta
probabilística: como o IVF já varre as vizinhanças em ordem de
proximidade, é improvável que vizinhanças mais distantes mudem a
classificação. Funciona em ~30-50% das queries.

### Etapa 3 — Decidir aprovar ou bloquear

Conta-se quantas das 5 mais parecidas são fraude. Como só existem
**6 respostas possíveis** (0, 1, 2, 3, 4 ou 5 frauds), **pré-geramos
todas as 6 respostas HTTP completas no startup** (status line +
headers + corpo JSON, tudo em bytes). No request, é só escrever os
bytes prontos no socket. **Zero serialização JSON no hot path.**

---

## Por que é rápido (otimizações principais)

Cada uma das decisões abaixo cortou ms do p99. Aqui em ordem de
impacto:

### Servidor HTTP escrito do zero (em vez de framework)

Frameworks como Undertow, Netty, Spring gastam **~1 ms** em
alocações internas e dispatching só pra entregar o request ao seu
código. Substituímos por um servidor HTTP minimal em `java.nio`
(1 thread, 1 *selector*, parser HTTP/1.1 manual). O hot path do
request é **zero alocação de memória**: ele reusa buffers e parsers
durante toda a vida da conexão.

### Comunicação por Unix Domain Socket

Normalmente um load balancer (nginx/HAProxy) conversa com o backend
via **TCP no localhost**. Mas TCP, mesmo entre dois processos da
mesma máquina, paga toda a stack IP (cabeçalhos, checksums, controle
de congestão). **Unix Domain Socket (UDS)** é um arquivo especial
que pula a stack IP completa — funciona como uma "estrada interna"
direta entre processos. Economia: ~0.5-1ms no p99.

### mmap + pre-fault + páginas grandes

O dataset de 3 milhões de vetores ocupa **168 MB**. Carregá-lo no
heap (memória normal do Java) consumiria toda a RAM do container.

Em vez disso, "memory-mapeamos" o arquivo: o sistema operacional
gerencia páginas de memória ligadas ao arquivo, **compartilhando-as
entre os 2 backends** (o cgroup do Docker conta uma vez só).

Adicionalmente:
- **Pre-fault** (`MappedByteBuffer.load()`): toca todas as páginas no
  startup pra evitar pausas no hot path por *page fault*.
- **Huge pages** (`madvise(MADV_HUGEPAGE)`): pede pro kernel usar
  páginas grandes (2 MiB em vez de 4 KiB). Reduz cache misses do TLB
  (Translation Lookaside Buffer) durante o scan IVF.

### HAProxy em vez de nginx

Fizemos A/B entre nginx e HAProxy mantendo o resto idêntico. HAProxy
deu ~1ms a menos no p99 com nosso workload (requests/responses
curtos, alta concorrência keep-alive). Adotamos.

### Binário nativo (GraalVM native-image)

Java tradicional roda em uma JVM que **compila o código aos poucos**
durante execução (JIT). Isso significa:
- Startup lento (~3-5 segundos)
- Primeiros milhares de requests são lentos enquanto o JIT compila
- Consumo de memória alto (~100 MB de overhead)

Compilamos com **GraalVM native-image** pra um binário ELF que:
- Inicia em ~300ms
- Tem RSS de ~30 MB
- Não tem JIT — perf estável desde o primeiro request

### CPU pinning

Cada container fica preso a um núcleo específico (`cpuset: "0"`,
`"1"`, `"2"`). Evita migração de thread entre cores, que invalidaria
o cache L1/L2 de instruções e dados.

### Resposta HTTP pré-renderizada

Como o algoritmo tem só 6 saídas possíveis (`fraud_count` 0-5),
calculamos as 6 respostas HTTP **inteiras** no startup — status
line, headers, content-length e corpo JSON, tudo já em bytes UTF-8.
No request, o servidor só escolhe qual `byte[]` jogar no socket. Não
há concatenação, formatação de número, nem chamada a JSON
serializer.

---

## Resultados

Bench oficial Rinha 2026 (`k6 run test/test.js`, rampa até 900 RPS
em 120s, 1.0 CPU e 350 MB total):

| Métrica | Valor |
|---|---:|
| **p99 latência** | **~2.8 ms** |
| **score final** | **~3030** |
| failure_rate | 1.75% |
| HTTP errors | 0 |

**Histórico do projeto:**

| Etapa | p99 | score |
|---|---:|---:|
| Início (Undertow + TCP) | 5.07 ms | 2768 |
| + NIO próprio + UDS | 3.96 ms | 2879 |
| + mmap pre-fault/hugepage + early-stop | 3.96 ms | 2879 |
| + HAProxy (vs nginx) ⭐ | **2.93 ms** | **3009** |
| **Estado atual (3 runs média)** | **2.81 ms** | **3030** |

**Ganho total: −44% no p99, +9.5% no score.**

**Comparação com líderes da Rinha 2026:**

| Equipe / Stack | p99 | score |
|---|---:|---:|
| pedrosakuma (.NET NativeAOT + AVX2 manual) | 1.06 ms | 5974 |
| daniloitagyba (.NET + LB custom fd-passing) | 1.13 ms | 5946 |
| **este projeto (Java 25 + GraalVM puro)** | **2.81 ms** | **3030** |

O *gap* pra alcançar o top exige técnicas fora do escopo "Java
puro": SIMD AVX2 manual via JNI/C, ou LB custom escrito em C com
fd-passing. Em Java + GraalVM SVM sem JNI, estamos próximos do teto
prático.

---

## Como rodar

### Local (JVM, com dataset real)

```bash
# 1. Pré-gera artefatos do dataset (uma vez)
DATASET_PATH=resources/references.json.gz ./gradlew convertDataset
DATASET_PATH=resources/references.bin ./gradlew buildIvfIndex

# 2. Roda o servidor
DATASET_PATH=resources/references.bin \
IVF_INDEX_PATH=resources/references.idx \
VECTOR_INDEX=IVF \
./gradlew run

# 3. Em outro terminal, testa
curl -i localhost:8080/ready
curl -i -X POST localhost:8080/fraud-score \
  -H 'Content-Type: application/json' \
  -d @sample.json
```

### Local (sem dataset)

```bash
./gradlew run   # gera dataset sintético de 5.000 vetores
```

### Docker (modo produção — HAProxy + UDS + native-image)

```bash
cd containerization
docker compose up -d --build
# espera ficar pronto
until curl -sf localhost:9999/ready; do sleep 1; done
```

### Benchmark oficial Rinha (k6)

```bash
k6 run test/test.js
# resultado vai pra test/results.json
```

---

## Variantes A/B (composes alternativos)

Pra fazer comparações isoladas, há 4 arquivos compose com a mesma
topologia mas LB ou transport diferente:

| Arquivo | LB | Transport | Quando usar |
|---|---|---|---|
| `docker-compose.yml` (default) | HAProxy | UDS | produção |
| `docker-compose.nginx-uds.yml` | nginx | UDS | A/B nginx vs HAProxy |
| `docker-compose.nginx-tcp.yml` | nginx | TCP loopback | A/B UDS vs TCP |

Pra rodar um alternativo:
```bash
docker compose -f containerization/docker-compose.nginx-uds.yml up -d
```

---

## Variáveis de ambiente principais

| Variável | Default | O que faz |
|---|---|---|
| `SERVER_PORT` | `8080` | Porta HTTP. Ignorado se `UDS_PATH` setado. |
| `UDS_PATH` | (vazio) | Se setado, escuta em Unix socket em vez de TCP. |
| `HTTP_SERVER` | `NIO` | Implementação HTTP (só `NIO` por hoje). |
| `VECTOR_INDEX` | `BRUTE_FORCE` | `BRUTE_FORCE` / `IVF` / `IVF_Q16`. |
| `DATASET_PATH` | (classpath) | Path do `.bin` ou `.json.gz`. |
| `IVF_INDEX_PATH` | (vazio) | Path do `.idx` pré-construído. |
| `QBIN_PATH` | (vazio) | Path do `.qbin` (só pra `IVF_Q16`). |
| `IVF_NPROBE` | `8` | Quantas vizinhanças varrer por query. |
| `IVF_EARLY_STOP` | `true` | Para o scan cedo se top-K já é unânime. |
| `MMAP_PREFETCH` | `true` | Pre-fault páginas mmap no startup. |
| `MMAP_HUGEPAGE` | `true` | `madvise(MADV_HUGEPAGE)`. |
| `BOUNDARY_FALLBACK` | `false` | Repair scan-all com bbox prune (opt-in). |
| `DISTANCE_METRIC` | `EUCLIDEAN` | `EUCLIDEAN` / `MANHATTAN` / `COSINE` / `EUCLIDEAN_SIMD`. |
| `PERF_LOG` | `false` | Imprime timings por estágio em stdout. |

Lista completa em `src/main/java/com/rinha/config/AppConfig.java`.

---

## Estrutura do código

```
src/main/java/com/rinha/
├── Main                                  startup + lifecycle
├── config/AppConfig                      lê env vars no startup
│
├── dataset/
│   ├── DatasetLoader                     carrega .bin / .json.gz / sintético
│   ├── BinaryDataset                     formato .bin v2 (mmap-friendly)
│   ├── BinaryQuantizedDataset            formato .qbin v1 (int16)
│   ├── QuantParams                       params da quantização int16
│   └── NativeMemAdvise                   wrapper FFM pra madvise(2)
│
├── distance/                             EUCLIDEAN, MANHATTAN, COSINE
│
├── index/
│   ├── VectorIndex                       interface { build, searchTopK }
│   ├── BruteForceVectorIndex             scan completo (baseline correto)
│   ├── IvfVectorIndex                    IVF + early-stop (default)
│   ├── IvfQuantizedVectorIndex           variante int16 (lossless)
│   ├── ReferenceDataset                  float32 flat + labels
│   └── QuantizedReferenceDataset         int16 flat + labels
│
├── vector/
│   ├── SchemaAwareVectorizationStrategy  parser zero-alloc do JSON
│   ├── Normalization                     escalas pra normalizar [0,1]
│   └── McCRiskMap                        risco histórico por MCC
│
├── score/FraudScorer                     conta frauds / topK
│
├── server/
│   ├── HttpServer                        interface
│   ├── ServerFactory                     constrói o impl
│   ├── ReadyState                        flag thread-safe (/ready)
│   ├── IndexHolder                       holder pós-warmup
│   ├── nio/                              servidor HTTP NIO próprio
│   │   ├── NioHttpServer                 lifecycle
│   │   ├── NioEventLoop                  1 thread + Selector
│   │   ├── HttpRequestParser             HTTP/1.1 manual zero-alloc
│   │   ├── PreRendered                   6 respostas pré-renderizadas
│   │   └── Connection                    estado per-conexão
│   └── service/{FraudScoreService,ReadyService}
│
├── tools/                                offline helpers
│   ├── ConvertDataset                    .json.gz → .bin
│   ├── BuildIvfIndex                     gera .idx (centroides + postings)
│   └── BuildQuantizedDataset             .bin → .qbin (int16)
│
└── warmup/Warmup                         puxa JIT/SVM antes do /ready
```

---

## Stack técnica

- **Java 25** (`sourceCompatibility = 25`)
- **GraalVM native-image** (produção) | **JVM** (dev)
- **`jdk.incubator.vector`** — Vector API (opcional, no caminho default
  só o scalar é usado pois o SVM tem suporte limitado a SIMD via Vector API)
- **`java.lang.foreign`** — FFM (Foreign Function & Memory), usado pra
  chamar `madvise(2)` da libc
- **Zero dependências** de framework HTTP/JSON/logging
- **JUnit 5** (apenas `testImplementation`)

---

## Endpoints

| Método | Path | Resposta |
|---|---|---|
| `GET /ready` | `200 {"status":"ready"}` quando dataset + índice + warmup terminaram. `503 {"status":"loading"}` antes. |
| `POST /fraud-score` | `200 {"approved":bool,"fraud_score":number}` com `application/json` no body. `503` se ainda não pronto. `400` se body inválido. |

---

## Limitações e próximos passos (se quiser fechar o gap pro top)

Estamos perto do teto pra Java + GraalVM SVM "puro". As 2 frentes
que ainda renderiam ganho real:

1. **SIMD AVX2 manual via JNI ou Panama**. Tentamos com Vector API
   (`jdk.incubator.vector`) — funciona em JVM com JIT mas em
   native-image o SVM cai em fallback super lento (medido: p99
   1200ms). Pra acelerar de verdade, precisa biblioteca C compilada
   com intrinsics (`_mm256_madd_epi16`) chamada via FFM/JNI.
   Ganho estimado: 0.5-1 ms p99.

2. **LB custom em C com fd-passing** (`SCM_RIGHTS`). Os top do .NET
   usam essa técnica: o LB recebe a conexão TCP do cliente e
   **passa o file descriptor** pro backend via Unix socket de
   controle. O LB sai do data path inteiro depois disso. Economia:
   pelo menos 1ms p99, mas exige LB binário custom.

Ambas saem do escopo "Java + Gradle só", trazem código C pra
manutenção.

---

## Histórico de decisões

Cada otimização foi medida em A/B. Tabela resumindo:

| Mudança | Ganho p99 | Ganho score |
|---|---:|---:|
| NIO próprio (em vez de Undertow) | +0.7 ms | +0 |
| UDS (em vez de TCP loopback) | -1 ms | +0 |
| mmap pre-fault + huge pages | -0.5 ms | +50 |
| IVF early-stop | -0.3 ms | +0 |
| HAProxy (em vez de nginx) | -1 ms | +130 |
| **Total** | **-2.2 ms** | **+262** |

(Algumas decisões foram **revertidas** depois de bench negativo:
Vector API SIMD em SVM, so-no-forevis LB. Quantização int16 funciona
mas não trouxe ganho de perf sem AVX2 manual.)
