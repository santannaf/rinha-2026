# ----- builder ----- (gera binário nativo via GraalVM + converte dataset)
#
# Usamos a imagem oficial native-image-community (JDK 25 com SVM acoplado).
# Tag fixa pra build reproduzível; bump quando subir GraalVM upstream.
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /workspace

# Camada 1: wrapper + metadados — muda raramente, fica em cache.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Camada 2: fontes + recursos + dataset compactado.
COPY src ./src
COPY resources/normalization.json resources/mcc_risk.json ./resources/
COPY resources/references.json.gz ./resources/references.json.gz

# 1) Converte dataset JSON.gz -> .bin (float32 + header RNHA) usando JVM.
RUN DATASET_PATH=resources/references.json.gz \
    ./gradlew --no-daemon convertDataset --args="resources/references.bin"

# 2) Constrói o IVF index (.idx) + dataset CLUSTERED (.bin com linhas
#    reordenadas por cluster) usando JVM full CPU. O layout clustered torna a
#    varredura de cluster sequencial em memória — essencial pro repair.
#    Ambos são shippados pro runtime e carregados via mmap em segundos.
RUN DATASET_PATH=resources/references.bin IVF_CENTROIDS=4096 IVF_NPROBE=4 \
    ./gradlew --no-daemon buildIvfIndex --args="resources/references.idx resources/references-clustered.bin"

# 3) Quantiza o dataset pra int16 (.qbin) — opcional em runtime via
#    VECTOR_INDEX=IVF_Q16. Custo: ~5s de build, +84 MiB na imagem.
RUN DATASET_PATH=resources/references.bin \
    ./gradlew --no-daemon buildQuantizedDataset --args="resources/references.qbin"

# 4) Compila o binário nativo.
RUN ./gradlew --no-daemon nativeCompile

# ----- runtime ----- (debian-slim só com libc + zlib que o binário linka)
FROM debian:12-slim
WORKDIR /app

# native-image-community produz binário glibc dinamicamente linkado.
# Precisamos só de libc6 + zlib1g (libz.so.1 usada pelo java.util.zip).
RUN apt-get update \
    && apt-get install -y --no-install-recommends zlib1g \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/build/native/nativeCompile/rinha-backend-26-java /app/rinha-backend-26-java
COPY --from=builder /workspace/resources/references-clustered.bin /resources/references-clustered.bin
COPY --from=builder /workspace/resources/references.idx /resources/references.idx
COPY --from=builder /workspace/resources/references.qbin /resources/references.qbin
COPY --from=builder /workspace/resources/normalization.json /resources/normalization.json
COPY --from=builder /workspace/resources/mcc_risk.json /resources/mcc_risk.json

ENV DATASET_PATH=/resources/references-clustered.bin
ENV IVF_INDEX_PATH=/resources/references.idx
ENV QBIN_PATH=/resources/references.qbin
ENV VECTOR_INDEX=IVF
ENV IVF_CENTROIDS=4096
ENV IVF_NPROBE=4

# Sem JAVA_OPTS — o binário nativo não usa JVM. Para tunar GC do nativo,
# passe -XX:MaximumHeapSizePercent ou -Xmx via env JAVA_TOOL_OPTIONS, ou
# adicione args no entrypoint quando necessário.

EXPOSE 8080
ENTRYPOINT ["/app/rinha-backend-26-java"]
