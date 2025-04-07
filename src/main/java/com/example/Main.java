package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;

import java.time.Duration;
import java.util.*;
import java.util.Scanner; 
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

//Import XChart classes
import org.knowm.xchart.*;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.style.markers.SeriesMarkers;
import java.io.IOException;

public class Main {
	private static final int[] LEADER_ELECTION_TIMEOUT = {500, 1000, 1500, 2000}; // 500, 1000, 1500, 2000
	private static final int DELAY_BETWEEN_EXPERIMENTS = 1500;
	private static final int NUMBER_OF_EXPERIMENTS = 5;
	private static final String ONE_LINE = "--------------------------------------------------";
	
	public static final int[] N = {3, 10, 100}; // 3,10,100
	private static final int[] f = {1, 4, 49}; // Number of processes that may crash // 1,4,49
	private static final double[] ALPHA = {0, 0.1, 1};
	public static long startTime; 
	private static ArrayList<ActorRef> references; // List of process references
	
	public static AtomicInteger decideCount = new AtomicInteger(0);
	private static boolean hasCalculatedDelay = false;
	
	private static long[] consensusDelays;
	private static int experimentsDone = 0;

    private static List<ResultData> resultDataList = new ArrayList<>();
	
	public static void main(String[] args) throws InterruptedException {
		
		//Scanner scanner = new Scanner(System.in);
		
		for (int tle : LEADER_ELECTION_TIMEOUT) { // Iterate through timeout values
            for (int nIdx = 0; nIdx < N.length; nIdx++) { // N and f are related by index
                int currentN = N[nIdx];
                int currentF = f[nIdx];
                for (double alpha : ALPHA) { // Iterate through alpha values
                    System.out.println(ONE_LINE);
                    System.out.printf("Running combination: N=%d, f=%d, TLE=%d, ALPHA=%.1f%n",
                            currentN, currentF, tle, alpha);
                    
                    consensusDelays = new long[NUMBER_OF_EXPERIMENTS]; // Reset for each combination
                    experimentsDone = 0;

                    final ActorSystem system = ActorSystem.create("system");
                    references = new ArrayList<>();

                    // Create processes with current parameters
                    for (int i = 0; i < currentN; i++) {
                        // Modified createActor() call (change in Process class required)
                        final ActorRef a = system.actorOf(
                            Process.createActor(i+1, currentN, currentF, tle, alpha), 
                            ""+i
                        );
                        references.add(a);
                    }

                    
                    Members m = new Members(references);
                    for (ActorRef actor : references) {
                        actor.tell(m, ActorRef.noSender());
                    }

                    for (int exp = 0; exp < NUMBER_OF_EXPERIMENTS; exp++) {
                        // Modified runOnce() call
                        runOnce(system, references, currentN, currentF, tle);
                        experimentsDone++;
                    }

                    double averageConsensusDelay = calculateAverageConsensusDelay(); // Now shows results per combination
                    resultDataList.add(new ResultData(currentN, currentF, tle, alpha, averageConsensusDelay));
                    system.terminate();
                    
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
		generateCharts();
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
            BitmapEncoder.saveBitmapWithDPI(chart, "./" + parameter + "_chart", BitmapFormat.PNG, 300);
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

	
	
	private static void resetSystem() {
		hasCalculatedDelay = false; 
		decideCount.set(0);
	}

	private static double calculateAverageConsensusDelay() {
		long totalConsensusDelay = 0;
		for (int i = 0; i < NUMBER_OF_EXPERIMENTS; i++) {
			System.out.println("Experiment " + (i + 1) + " consensus delay: " + consensusDelays[i] + " ms");
			totalConsensusDelay += consensusDelays[i];
		}

		double averageConsensusDelay = (double) totalConsensusDelay / NUMBER_OF_EXPERIMENTS;
		System.out.println(ONE_LINE);
		System.out.println("Average consensus delay: " + averageConsensusDelay + " ms");
		System.out.println(ONE_LINE);
		return averageConsensusDelay;
	}

	private static void runOnce(ActorSystem system, ArrayList<ActorRef> references, int currentN, int currentF, int currentTLE) throws InterruptedException {
		startTime = System.currentTimeMillis();
		ArrayList<Integer> faultyIndexes = getFaultyIndexes(currentN, currentF);
		sendCrashMessages(faultyIndexes, references, currentF);

		for (ActorRef actor : references) {
			actor.tell(new LaunchMsg(), ActorRef.noSender());
		}

		// First Delay before leader election
		Thread.sleep(currentTLE);
		/* if you want can uncomment
		 * 
		 * do {
			// Choose new leader
			int newLeaderIndex = findNewLeaderIndex(faultyIndexes, references);
			ActorRef newLeader = references.get(newLeaderIndex);
			system.log().info("New leader index: " + newLeaderIndex);

			// Elect new leader
			newLeader.tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
			// Subsequent Delays
			Thread.sleep(LEADER_ELECTION_TIMEOUT);
		} while (!hasCalculatedDelay);*/
		
			int newLeaderIndex = findNewLeaderIndex(faultyIndexes, references, currentN);
			ActorRef newLeader = references.get(newLeaderIndex);
			system.log().info("New leader index: " + newLeaderIndex);

			// Elect new leader
			newLeader.tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
			// Subsequent Delays
			Thread.sleep(currentTLE);

		while (decideCount.get() < currentN / 2) {
			System.out.println(decideCount.get());
			// Wait for majority to decide
		}
		system.log().info("verified majority has decided");

		Thread.sleep(DELAY_BETWEEN_EXPERIMENTS); // Sleep for a while to let processes finish
		restartProcesses(); // Restart processes
		Thread.sleep(DELAY_BETWEEN_EXPERIMENTS); // Sleep for a while to let restart finish
		resetSystem();
		System.out.println("Reached the end of experiment" + ONE_LINE);
		return;
	}

	// Restart all processes after consensus delay is calculated
	public static void restartProcesses() {
		// Create restart message
		RestartMsg restartMsg = new RestartMsg();
		// Restart processes
		for (ActorRef process : references) {
			process.tell(restartMsg, ActorRef.noSender());
		}
		System.out.println("All processes have restarted.");
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
	
	
	
	
	

	public static synchronized void reportDelay(int ID) {
		if (hasCalculatedDelay) { // no need to calculate delay again
			return;
		}
		hasCalculatedDelay = true;

		long endTime = System.currentTimeMillis();
		long delay = endTime - startTime;
		akka.event.Logging.getLogger(akka.actor.ActorSystem.create(), "Main")
				.info("Process " + ID + " has reached DECIDE with consensus delay = " + delay + " ms");
		consensusDelays[experimentsDone] = delay;
	}
}
