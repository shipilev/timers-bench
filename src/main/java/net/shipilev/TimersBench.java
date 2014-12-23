package net.shipilev;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Fork(5)
@State(Scope.Thread)
public class TimersBench {

    private long lastValue;

    @Benchmark
    public long latency_nanotime() {
        return System.nanoTime();
    }

    @Benchmark
    public long latency_currentTime() {
        return System.currentTimeMillis();
    }

    @Benchmark
    public long granularity_nanotime() {
        long cur;
        do {
            cur = System.nanoTime();
        } while (cur == lastValue);
        lastValue = cur;
        return cur;
    }

    @Benchmark
    public long granularity_currentTime() {
        long cur;
        do {
            cur = System.currentTimeMillis();
        } while (cur == lastValue);
        lastValue = cur;
        return cur;
    }

    public static void main(String[] args) throws RunnerException, InterruptedException {
        PrintWriter pw = new PrintWriter(System.out, true);

        pw.println("---- 8< (cut here) -----------------------------------------");

        pw.println(System.getProperty("java.runtime.name") + ", " + System.getProperty("java.runtime.version"));
        pw.println(System.getProperty("java.vm.name") + ", " + System.getProperty("java.vm.version"));
        pw.println(System.getProperty("os.name") + ", " + System.getProperty("os.version") + ", " + System.getProperty("os.arch"));

        int cpus = figureOutHotCPUs(pw);

        runWith(pw, 1,          "-client");
        runWith(pw, cpus / 2,   "-client");
        runWith(pw, cpus,       "-client");

        runWith(pw, 1,          "-server");
        runWith(pw, cpus / 2,   "-server");
        runWith(pw, cpus,       "-server");

        pw.println();
        pw.println("---- 8< (cut here) -----------------------------------------");

        pw.flush();
        pw.close();
    }

    private static void runWith(PrintWriter pw, int threads, String... jvmOpts) throws RunnerException {
        pw.println();
        pw.println("Running with " + threads + " threads and " + Arrays.toString(jvmOpts) + ": ");

        Options opts = new OptionsBuilder()
                           .threads(threads)
                           .verbosity(VerboseMode.SILENT)
                           .jvmArgs(jvmOpts)
                           .build();

        Collection<RunResult> results = new Runner(opts).run();
        for (RunResult r : results) {
            String name = simpleName(r.getParams().getBenchmark());
            double score = r.getPrimaryResult().getScore();
            double scoreError = r.getPrimaryResult().getStatistics().getMeanErrorAt(0.99);
            pw.printf("%30s: %11.3f ± %10.3f ns%n", name, score, scoreError);
        }
    }

    private static String simpleName(String qName) {
        int lastDot = requireNonNull(qName).lastIndexOf('.');
        return lastDot < 0 ? qName : qName.substring(lastDot + 1);
    }

    /**
     * Warm up the CPU schedulers, bring all the CPUs online to get the
     * reasonable estimate of the system capacity.
     *
     * @return online CPU count
     */
    private static int figureOutHotCPUs(PrintWriter pw) throws InterruptedException {
        ExecutorService service = Executors.newCachedThreadPool();

        pw.println();
        pw.print("Burning up to figure out the exact CPU count...");
        pw.flush();

        int warmupTime = 1000;
        long lastChange = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        futures.add(service.submit(new BurningTask()));

        pw.print(".");

        int max = 0;
        while (System.currentTimeMillis() - lastChange < warmupTime) {
            int cur = Runtime.getRuntime().availableProcessors();
            if (cur > max) {
                pw.print(".");
                max = cur;
                lastChange = System.currentTimeMillis();
                futures.add(service.submit(new BurningTask()));
            }
        }

        for (Future<?> f : futures) {
            pw.print(".");
            f.cancel(true);
        }

        service.shutdown();

        service.awaitTermination(1, TimeUnit.DAYS);

        pw.println(" done!");

        return max;
    }

    public static class BurningTask implements Runnable {
        @Override
        public void run() {
            while (!Thread.interrupted()); // burn;
        }
    }

}
