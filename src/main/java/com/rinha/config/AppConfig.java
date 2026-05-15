package com.rinha.config;

/**
 * Configuração lida do ambiente uma única vez no startup.
 * Todos os campos primitivos para evitar boxing na consulta.
 */
public final class AppConfig {

    private final int serverPort;
    private final String distanceMetric;
    private final String vectorIndex;
    private final int topK;
    private final double threshold;
    private final int warmupWorkers;
    private final int warmupIterations;
    private final boolean perfLog;
    private final String datasetPath;
    private final boolean devFallback;
    private final int devDatasetSize;
    private final int ivfCentroids;
    private final int ivfNProbe;
    private final long indexSeed;
    private final boolean boundaryFallback;
    private final boolean mmap;
    private final String ivfIndexPath;
    private final int boundaryFallbackNProbe;
    private final String httpServer;
    private final String udsPath;
    private final boolean mmapPrefetch;
    private final boolean mmapHugepage;
    private final boolean ivfEarlyStop;
    private final String qbinPath;

    private AppConfig(Builder b) {
        this.serverPort = b.serverPort;
        this.distanceMetric = b.distanceMetric;
        this.vectorIndex = b.vectorIndex;
        this.topK = b.topK;
        this.threshold = b.threshold;
        this.warmupWorkers = b.warmupWorkers;
        this.warmupIterations = b.warmupIterations;
        this.perfLog = b.perfLog;
        this.datasetPath = b.datasetPath;
        this.devFallback = b.devFallback;
        this.devDatasetSize = b.devDatasetSize;
        this.ivfCentroids = b.ivfCentroids;
        this.ivfNProbe = b.ivfNProbe;
        this.indexSeed = b.indexSeed;
        this.boundaryFallback = b.boundaryFallback;
        this.mmap = b.mmap;
        this.ivfIndexPath = b.ivfIndexPath;
        this.boundaryFallbackNProbe = b.boundaryFallbackNProbe;
        this.httpServer = b.httpServer;
        this.udsPath = b.udsPath;
        this.mmapPrefetch = b.mmapPrefetch;
        this.mmapHugepage = b.mmapHugepage;
        this.ivfEarlyStop = b.ivfEarlyStop;
        this.qbinPath = b.qbinPath;
    }

    public static AppConfig fromEnv() {
        Builder b = new Builder();
        b.serverPort = intEnv("SERVER_PORT", 8080);
        b.distanceMetric = strEnv("DISTANCE_METRIC", "EUCLIDEAN").toUpperCase();
        b.vectorIndex = strEnv("VECTOR_INDEX", "BRUTE_FORCE").toUpperCase();
        b.topK = intEnv("TOP_K", 5);
        b.threshold = doubleEnv("THRESHOLD", 0.6);
        b.warmupWorkers = clamp(intEnv("WARMUP_WORKERS", 2), 1, 16);
        b.warmupIterations = Math.max(0, intEnv("WARMUP_ITERATIONS", 1000));
        b.perfLog = boolEnv("PERF_LOG", false);
        b.datasetPath = strEnv("DATASET_PATH", "");
        b.devFallback = boolEnv("DEV_FALLBACK", true);
        b.devDatasetSize = Math.max(1, intEnv("DEV_DATASET_SIZE", 5000));
        b.ivfCentroids = Math.max(1, intEnv("IVF_CENTROIDS", 1024));
        b.ivfNProbe = Math.max(1, intEnv("IVF_NPROBE", 8));
        b.indexSeed = longEnv("INDEX_SEED", 42L);
        b.boundaryFallback = boolEnv("BOUNDARY_FALLBACK", false);
        b.mmap = boolEnv("MMAP", true);
        b.ivfIndexPath = strEnv("IVF_INDEX_PATH", "");
        b.boundaryFallbackNProbe = Math.max(1, intEnv("BOUNDARY_FALLBACK_NPROBE", 32));
        b.httpServer = strEnv("HTTP_SERVER", "NIO").toUpperCase();
        b.udsPath = strEnv("UDS_PATH", "");
        // Defaults ligados. Pre-fetch e hugepage são lossless (só perf);
        // early-stop é probabilístico e pode mudar detecção em borderline
        // — A/B isolando se desconfiar do failure_rate.
        b.mmapPrefetch = boolEnv("MMAP_PREFETCH", true);
        b.mmapHugepage = boolEnv("MMAP_HUGEPAGE", true);
        b.ivfEarlyStop = boolEnv("IVF_EARLY_STOP", true);
        b.qbinPath = strEnv("QBIN_PATH", "");
        return new AppConfig(b);
    }

    public int serverPort() { return serverPort; }
    public String distanceMetric() { return distanceMetric; }
    public String vectorIndex() { return vectorIndex; }
    public int topK() { return topK; }
    public double threshold() { return threshold; }
    public int warmupWorkers() { return warmupWorkers; }
    public int warmupIterations() { return warmupIterations; }
    public boolean perfLog() { return perfLog; }
    public String datasetPath() { return datasetPath; }
    public boolean devFallback() { return devFallback; }
    public int devDatasetSize() { return devDatasetSize; }
    public int ivfCentroids() { return ivfCentroids; }
    public int ivfNProbe() { return ivfNProbe; }
    public long indexSeed() { return indexSeed; }
    public boolean boundaryFallback() { return boundaryFallback; }
    public boolean mmap() { return mmap; }
    public String ivfIndexPath() { return ivfIndexPath; }
    public int boundaryFallbackNProbe() { return boundaryFallbackNProbe; }
    public String httpServer() { return httpServer; }
    public String udsPath() { return udsPath; }
    public boolean mmapPrefetch() { return mmapPrefetch; }
    public boolean mmapHugepage() { return mmapHugepage; }
    public boolean ivfEarlyStop() { return ivfEarlyStop; }
    public String qbinPath() { return qbinPath; }

    private static String strEnv(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int intEnv(String k, int def) {
        String v = System.getenv(k);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ex) { return def; }
    }

    private static long longEnv(String k, long def) {
        String v = System.getenv(k);
        if (v == null || v.isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException ex) { return def; }
    }

    private static double doubleEnv(String k, double def) {
        String v = System.getenv(k);
        if (v == null || v.isEmpty()) return def;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException ex) { return def; }
    }

    private static boolean boolEnv(String k, boolean def) {
        String v = System.getenv(k);
        if (v == null || v.isEmpty()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private static int clamp(int x, int lo, int hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    @Override
    public String toString() {
        return "AppConfig{port=" + serverPort
                + ", metric=" + distanceMetric
                + ", index=" + vectorIndex
                + ", topK=" + topK
                + ", threshold=" + threshold
                + ", warmup=" + warmupWorkers + "x" + warmupIterations
                + ", perfLog=" + perfLog
                + ", boundaryFallback=" + boundaryFallback
                + ", mmap=" + mmap
                + ", httpServer=" + httpServer
                + ", udsPath=" + (udsPath.isEmpty() ? "<tcp>" : udsPath)
                + ", mmapPrefetch=" + mmapPrefetch
                + ", mmapHugepage=" + mmapHugepage
                + ", ivfEarlyStop=" + ivfEarlyStop
                + "}";
    }

    private static final class Builder {
        int serverPort;
        String distanceMetric;
        String vectorIndex;
        int topK;
        double threshold;
        int warmupWorkers;
        int warmupIterations;
        boolean perfLog;
        String datasetPath;
        boolean devFallback;
        int devDatasetSize;
        int ivfCentroids;
        int ivfNProbe;
        long indexSeed;
        boolean boundaryFallback;
        boolean mmap;
        String ivfIndexPath;
        int boundaryFallbackNProbe;
        String httpServer;
        String udsPath;
        boolean mmapPrefetch;
        boolean mmapHugepage;
        boolean ivfEarlyStop;
        String qbinPath;
    }
}
