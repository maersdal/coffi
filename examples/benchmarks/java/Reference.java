/**
 * Machine calibration reference for the coffi benchmarks.
 *
 * Zero-dependency, deterministic, single-threaded workload printing two
 * scores: cpu_ms (recursive integer work, mirrors the ackermann benchmarks)
 * and mem_ms (boxed allocation churn, mirrors the mem benchmarks). Published
 * benchmark numbers come with the scores of the machine that produced them;
 * to estimate a number on another machine, scale it by the ratio of that
 * machine's scores. Run on the same JVM the benchmarks run on (the JIT
 * matters as much as the hardware).
 */
public final class Reference {
  static long ack(long m, long n) {
    if (m == 0) return n + 1;
    if (n == 0) return ack(m - 1, 1);
    return ack(m - 1, ack(m, n - 1));
  }

  /** One round of the mem score: 5 boxed 4M-Float arrays with a sliding
   * window of 3 retained; Float has no autobox cache, so every element is a
   * real allocation. */
  static double memRound() {
    final int n = 4_000_000;
    Float[][] retained = new Float[3][];
    double checksum = 0;
    for (int iter = 0; iter < 5; iter++) {
      Float[] v = new Float[n];
      for (int i = 0; i < n; i++) v[i] = (i & 1023) * 0.5f;
      retained[iter % 3] = v;
      checksum += v[0] + v[n - 1];
    }
    return checksum + retained[0][1];
  }

  static void run() {
    long cpuChecksum = 0;
    double memChecksum = 0;
    long cpuBest = Long.MAX_VALUE, memBest = Long.MAX_VALUE;
    for (int rep = 0; rep < 5; rep++) {
      long t0 = System.nanoTime();
      cpuChecksum += ack(3, 11);
      cpuBest = Math.min(cpuBest, System.nanoTime() - t0);
    }
    for (int rep = 0; rep < 10; rep++) {
      // start each round from a clean heap so where GC pauses land doesn't
      // swing the min
      System.gc();
      long t0 = System.nanoTime();
      memChecksum += memRound();
      memBest = Math.min(memBest, System.nanoTime() - t0);
    }
    System.out.println("checksum: " + cpuChecksum + " " + memChecksum);
    // score is the geometric mean of the two: the single number benchmark
    // results are divided by to make them comparable across machines
    System.out.printf("REFERENCE cpu_ms=%d mem_ms=%d score=%d%n",
        cpuBest / 1_000_000, memBest / 1_000_000,
        Math.round(Math.sqrt((cpuBest / 1e6) * (memBest / 1e6))));
  }

  public static void main(String[] args) throws Exception {
    // ack(3,11) recurses ~16k deep; a dedicated thread with a roomy stack
    // makes the score independent of -Xss
    Thread t = new Thread(null, Reference::run, "reference", 512L << 20);
    t.start();
    t.join();
  }
}
