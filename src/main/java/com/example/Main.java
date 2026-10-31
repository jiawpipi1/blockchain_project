package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;

import java.time.Duration;
import java.util.*;
import java.util.Scanner; 
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

//Import XChart classes
import org.knowm.xchart.*;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.style.markers.SeriesMarkers;
import java.io.IOException;

public class Main {
	private static final int[] LEADER_ELECTION_TIMEOUT = {500, 1000, 1500, 2000}; // 500, 1000, 1500, 2000
	private static final int DELAY_BETWEEN_EXPERIMENTS = 20; // let the previous run's stray messages drain
	// Runs per configuration, override with -Druns=<n>
	private static final int NUMBER_OF_EXPERIMENTS = Integer.getInteger("runs", 100);
	private static final int WARMUP_RUNS = Integer.getInteger("warmup", 30); // discarded, lets the JIT warm up
	private static final int RUN_TIMEOUT_MS = 60000; // give up on a run that never reaches a majority
	private static final String ONE_LINE = "--------------------------------------------------";
	
	public static final int[] N = {3, 10, 100}; // 3,10,100
	private static final int[] f = {1, 4, 49}; // Number of processes that may crash // 1,4,49
	private static final double[] ALPHA = {0, 0.1, 1};
	public static volatile long startTime; // System.nanoTime() at the start of a run
	private static ArrayList<ActorRef> references; // List of process references
	
	public static AtomicInteger decideCount = new AtomicInteger(0);
	private static volatile int currentRunId = 0; // decisions from older runs are ignored
	private static boolean recording = true;
	private static boolean hasCalculatedDelay = false;
	private static Set<String> decidedValues = new HashSet<>();
	private static double majorityDelay = -1;
	
	private static double[] consensusDelays;
	private static int experimentsDone = 0;
	private static int agreementViolations = 0;
	private static int timedOutRuns = 0;
	private static StringBuilder runsCsv = new StringBuilder("N,f,tle_ms,alpha,run,first_decide_ms,majority_decide_ms,leader_elections,decided_values\n");

    private static List<ResultData> resultDataList = new ArrayList<>();
	
	public static void main(String[] args) throws InterruptedException {
		
		//Scanner scanner = new Scanner(System.in);
		
		final ActorSystem system = ActorSystem.create("system");
		long sweepStart = System.currentTimeMillis();

		// Warm-up runs (not recorded) so the first configuration is not measured on a cold JVM
		recording = false;
		consensusDelays = new double[1];
		for (int w = 0; w < WARMUP_RUNS; w++) {
			int nIdx = w % N.length;
			runOnce(system, N[nIdx], f[nIdx], LEADER_ELECTION_TIMEOUT[0], ALPHA[w % ALPHA.length], 0);
		}
		recording = true;

		for (int tle : LEADER_ELECTION_TIMEOUT) { // Iterate through timeout values
            for (int nIdx = 0; nIdx < N.length; nIdx++) { // N and f are related by index
                int currentN = N[nIdx];
                int currentF = f[nIdx];
                for (double alpha : ALPHA) { // Iterate through alpha values
                    System.out.println(ONE_LINE);
                    System.out.printf("Running combination: N=%d, f=%d, TLE=%d, ALPHA=%.1f%n",
                            currentN, currentF, tle, alpha);
                    
                    consensusDelays = new double[NUMBER_OF_EXPERIMENTS]; // Reset for each combination
                    experimentsDone = 0;

                    for (int exp = 0; exp < NUMBER_OF_EXPERIMENTS; exp++) {
                        runOnce(system, currentN, currentF, tle, alpha, exp);
                        experimentsDone++;
                    }

                    double averageConsensusDelay = calculateAverageConsensusDelay(); // Now shows results per combination
                    resultDataList.add(new ResultData(currentN, currentF, tle, alpha, averageConsensusDelay));
                    
                    /*
                    System.out.println("\nPress 'q' + Enter to continue...");
                    while (true) {
                        String input = scanner.nextLine().trim();
                        if (input.equalsIgnoreCase("q")) break;
                        System.out.println("Invalid input. Press 'q' + Enter:");
                    }
                    */
                }
            }
        }
		system.terminate();
		system.getWhenTerminated().toCompletableFuture().join();
		generateCharts();
		writeCsv();
		System.out.printf("Sweep took %.1f s (%d runs per configuration)%n",
				(System.currentTimeMillis() - sweepStart) / 1000.0, NUMBER_OF_EXPERIMENTS);
		System.out.println(ONE_LINE);
		System.out.println("Agreement violations: " + agreementViolations + ", timed-out runs: " + timedOutRuns);
    }

    private static void writeCsv() {
        new java.io.File("results").mkdirs();
        StringBuilder summary = new StringBuilder("N,f,tle_ms,alpha,avg_first_decide_ms\n");
        for (ResultData d : resultDataList) {
            summary.append(String.format(Locale.US, "%d,%d,%d,%.1f,%.3f%n", d.n, d.f, d.tle, d.alpha, d.averageDelay));
        }
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("results", "runs.csv"), runsCsv.toString().getBytes());
            java.nio.file.Files.write(java.nio.file.Paths.get("results", "summary.csv"), summary.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
	
	private static class ResultData {
        public final int n;
        public final int f;
        public final int tle;
        public final double alpha;
        public final double averageDelay;

        public ResultData(int n, int f, int tle, double alpha, double averageDelay) {
            this.n = n;
            this.f = f;
            this.tle = tle;
            this.alpha = alpha;
            this.averageDelay = averageDelay;
        }
    }
	
    private static void generateCharts() {
        Map<Integer, List<Double>> nData = new HashMap<>();
        Map<Double, List<Double>> alphaData = new HashMap<>();
        Map<Integer, List<Double>> tleData = new HashMap<>();

        for (ResultData data : resultDataList) {
            nData.computeIfAbsent(data.n, k -> new ArrayList<>()).add(data.averageDelay);
            alphaData.computeIfAbsent(data.alpha, k -> new ArrayList<>()).add(data.averageDelay);
            tleData.computeIfAbsent(data.tle, k -> new ArrayList<>()).add(data.averageDelay);
        }

        Map<Integer, Double> nAverages = computeAverages(nData);
        Map<Double, Double> alphaAverages = computeAverages(alphaData);
        Map<Integer, Double> tleAverages = computeAverages(tleData);

        createAndSaveChart(nAverages, "N", "Average Consensus Delay vs N", "N", "Average Delay (ms)");
        createAndSaveChart(alphaAverages, "ALPHA", "Average Consensus Delay vs ALPHA", "ALPHA", "Average Delay (ms)");
        createAndSaveChart(tleAverages, "TLE", "Average Consensus Delay vs LEADER_ELECTION_TIMEOUT", "LEADER_ELECTION_TIMEOUT (ms)", "Average Delay (ms)");
    }

    private static <T> Map<T, Double> computeAverages(Map<T, List<Double>> data) {
        Map<T, Double> averages = new HashMap<>();
        for (Map.Entry<T, List<Double>> entry : data.entrySet()) {
            T key = entry.getKey();
            List<Double> values = entry.getValue();
            double sum = values.stream().mapToDouble(Double::doubleValue).sum();
            averages.put(key, sum / values.size());
        }
        return averages;
    }

    private static <T> void createAndSaveChart(Map<T, Double> data, String parameter, String title, String xAxisTitle, String yAxisTitle) {
        List<T> sortedKeys = new ArrayList<>(data.keySet());
        sortedKeys.sort((a, b) -> {
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            return 0;
        });

        List<Number> xValues = new ArrayList<>();
        List<Double> yValues = new ArrayList<>();
        for (T key : sortedKeys) {
            if (key instanceof Number) {
                xValues.add((Number) key);
            }
            yValues.add(data.get(key));
        }

        XYChart chart = new XYChartBuilder().width(800).height(600).title(title).xAxisTitle(xAxisTitle).yAxisTitle(yAxisTitle).build();
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setMarkerSize(8);

        double[] xData = xValues.stream().mapToDouble(Number::doubleValue).toArray();
        double[] yData = yValues.stream().mapToDouble(Double::doubleValue).toArray();

        chart.addSeries(parameter, xData, yData);

        try {
            new java.io.File("results").mkdirs();
            BitmapEncoder.saveBitmapWithDPI(chart, "./results/" + parameter + "_chart", BitmapFormat.PNG, 300);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
	
	
	// Send special crash messages to f processes at random
	private static void sendCrashMessages(ArrayList<Integer> randomIndexes, ArrayList<ActorRef> references, int currentF) {
		// Send crash messages
		for (int k = 0; k < currentF; k++) {
			int randomProcessIndex = randomIndexes.get(k);
			references.get(randomProcessIndex).tell(new CrashMsg(true), ActorRef.noSender());
		}
	}

	// After every timeout, select a new leader
	private static int findNewLeaderIndex(ArrayList<Integer> faultyIndexes, ArrayList<ActorRef> references, int currentN) {
		// Find a new leader
		int leaderIndex = new Random().nextInt(currentN);
		// Find a non fault-prone leader
		while (faultyIndexes.contains(leaderIndex)) {
			leaderIndex = new Random().nextInt(currentN);
		}
		return leaderIndex;
	}

	
	
	private static ArrayList<Integer> getFaultyIndexes(int currentN, int currentF) {
		ArrayList<Integer> fullListIndexes = new ArrayList<Integer>();
		for (int j = 0; j < currentN; j++) {
			fullListIndexes.add(j);
		}
		Collections.shuffle(fullListIndexes);

		ArrayList<Integer> faultyIndexes = new ArrayList<Integer>();
		for (int k = 0; k < currentF; k++) {
			faultyIndexes.add(fullListIndexes.get(k));
		}
		return faultyIndexes;

	}

	
	
	private static synchronized void resetSystem() {
		hasCalculatedDelay = false; 
		decidedValues.clear();
		majorityDelay = -1;
		decideCount.set(0);
	}

	private static double calculateAverageConsensusDelay() {
		double totalConsensusDelay = 0;
		for (int i = 0; i < NUMBER_OF_EXPERIMENTS; i++) {
			totalConsensusDelay += consensusDelays[i];
		}

		double averageConsensusDelay = totalConsensusDelay / NUMBER_OF_EXPERIMENTS;
		System.out.println(ONE_LINE);
		System.out.println("Average consensus delay: " + averageConsensusDelay + " ms");
		System.out.println(ONE_LINE);
		return averageConsensusDelay;
	}

	private static void runOnce(ActorSystem system, int currentN, int currentF, int currentTLE,
			double alpha, int exp) throws InterruptedException {
		// Fresh actors for every run ("restart all actors"): stray messages from the previous run cannot reach them
		int runId = currentRunId + 1;
		references = new ArrayList<>();
		for (int i = 0; i < currentN; i++) {
			references.add(system.actorOf(Process.createActor(i + 1, currentN, currentF, currentTLE, alpha, runId),
					"r" + runId + "_" + i));
		}
		Members m = new Members(references);
		for (ActorRef actor : references) {
			actor.tell(m, ActorRef.noSender());
		}
		currentRunId = runId;
		Thread.sleep(5); // let the actors start before the clock starts

		ArrayList<Integer> faultyIndexes = getFaultyIndexes(currentN, currentF);
		startTime = System.nanoTime();
		sendCrashMessages(faultyIndexes, references, currentF);

		for (ActorRef actor : references) {
			actor.tell(new LaunchMsg(), ActorRef.noSender());
		}

		// Wait until a majority decides. Every TLE without a majority, elect a non fault-prone leader.
		// (A run that already reached a majority before the first TLE is finished; no need to sleep the full TLE.)
		int majority = currentN / 2 + 1;
		int elections = 0;
		long tleNanos = currentTLE * 1_000_000L;
		long nextElection = startTime + tleNanos;
		long deadline = startTime + RUN_TIMEOUT_MS * 1_000_000L;
		while (decideCount.get() < majority && System.nanoTime() < deadline) {
			if (System.nanoTime() >= nextElection) {
				int newLeaderIndex = findNewLeaderIndex(faultyIndexes, references, currentN);
				references.get(newLeaderIndex).tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
				elections++;
				nextElection += tleNanos;
			}
			LockSupport.parkNanos(100_000); // poll every 0.1 ms
		}

		synchronized (Main.class) {
			if (decideCount.get() < majority) {
				timedOutRuns++;
				System.out.println("Run timed out before a majority decided");
			}
			if (decidedValues.size() > 1) {
				agreementViolations++;
				System.out.println("AGREEMENT VIOLATION: decided values " + decidedValues);
			}
			if (recording) {
				runsCsv.append(String.format(Locale.US, "%d,%d,%d,%.1f,%d,%.3f,%.3f,%d,%s%n", currentN, currentF, currentTLE,
						alpha, exp + 1, consensusDelays[experimentsDone], majorityDelay, elections,
						String.join("|", decidedValues)));
			}
		}

		for (ActorRef actor : references) {
			system.stop(actor);
		}
		Thread.sleep(DELAY_BETWEEN_EXPERIMENTS);
		resetSystem();
	}

	/*
	public static void main(String[] args) throws InterruptedException {
		// Create array to store consensus delays
		consensusDelays = new long[NUMBER_OF_EXPERIMENTS];
		experimentsDone = 0;

		// Instantiate an actor system
		final ActorSystem system = ActorSystem.create("system");
		system.log().info("System started with N=" + N);

		references = new ArrayList<>();

		// if I change N, need change system (change f too)

		// change tle (timeout leader election)
		// change a (crash probability)
		for (int i = 0; i < N; i++) {
			// Instantiate processes
			final ActorRef a = system.actorOf(Process.createActor(i + 1, N), "" + i);
			references.add(a);
		}

		// give each process a view of all the other processes
		Members m = new Members(references);
		for (ActorRef actor : references) {
			actor.tell(m, ActorRef.noSender());
		}

		// Run experiments repeatedly
		for (int i = 0; i < NUMBER_OF_EXPERIMENTS; i++) {
			system.log().info("Starting experiment " + (i + 1));
			startTime = System.currentTimeMillis();
			runOnce(system, references); // TODO: system output got elect leader, but no effect to process. Can find a
											// way to eliminate it?
			experimentsDone++;
		}
		// Calculate consensus delay average
		calculateAverageConsensusDelay();

		system.terminate(); // Terminate the actor system
		System.out.println("System has terminated.");
		return;
	} */
	
	
	
	
	

	// Called by a process when it decides (by majority of acks or by a DECIDE message)
	public static synchronized void reportDecision(int runId, int ID, String value) {
		if (runId != currentRunId) { // late message from an earlier run
			return;
		}
		double delay = (System.nanoTime() - startTime) / 1e6;
		decidedValues.add(value);
		int count = decideCount.incrementAndGet();
		if (count == references.size() / 2 + 1) {
			majorityDelay = delay;
		}
		if (hasCalculatedDelay) { // only the first DECIDE defines the consensus delay
			return;
		}
		hasCalculatedDelay = true;
		consensusDelays[experimentsDone] = delay;
	}
}
