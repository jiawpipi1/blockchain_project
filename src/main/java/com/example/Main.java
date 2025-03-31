package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;

import java.time.Duration;
import java.util.*;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
	private static final int LEADER_ELECTION_TIMEOUT = 500; // 500, 1000, 1500, 2000
	private static final int DELAY_BETWEEN_EXPERIMENTS = 1500;
	private static final int NUMBER_OF_EXPERIMENTS = 5;
	private static final String ONE_LINE = "--------------------------------------------------";
	
	public static final int N = 10; // 3,10,100
	private static final int f = 4; // Number of processes that may crash // 1,4,49
	public static long startTime; 
	private static ArrayList<ActorRef> references; // List of process references
	
	public static AtomicInteger decideCount = new AtomicInteger(0);
	private static boolean hasCalculatedDelay = false;
	
	private static long[] consensusDelays;
	private static int experimentsDone = 0;

	// Send special crash messages to f processes at random
	private static void sendCrashMessages(ArrayList<Integer> randomIndexes, ArrayList<ActorRef> references) {
		// Send crash messages
		for (int k = 0; k < f; k++) {
			int randomProcessIndex = randomIndexes.get(k);
			references.get(randomProcessIndex).tell(new CrashMsg(true), ActorRef.noSender());
		}
	}

	// After every timeout, select a new leader
	private static int findNewLeaderIndex(ArrayList<Integer> faultyIndexes, ArrayList<ActorRef> references) {
		// Find a new leader
		int leaderIndex = new Random().nextInt(N);
		// Find a non fault-prone leader
		while (faultyIndexes.contains(leaderIndex)) {
			leaderIndex = new Random().nextInt(N);
		}
		return leaderIndex;
	}

	// Return array of f random indexes
	private static ArrayList<Integer> getFaultyIndexes() {
		// Create random list of indexes
		ArrayList<Integer> fullListIndexes = new ArrayList<Integer>();
		for (int j = 0; j < N; j++) {
			fullListIndexes.add(j);
		}
		Collections.shuffle(fullListIndexes);

		// Add first f elements to randomfList
		ArrayList<Integer> faultyIndexes = new ArrayList<Integer>();
		for (int k = 0; k < f; k++) {
			faultyIndexes.add(fullListIndexes.get(k));
		}
		return faultyIndexes;

	}

	private static void resetSystem() {
		hasCalculatedDelay = false; // Reset delay calculation flag
		decideCount.set(0); // Reset decide count
	}

	private static void calculateAverageConsensusDelay() {
		long totalConsensusDelay = 0;
		for (int i = 0; i < NUMBER_OF_EXPERIMENTS; i++) {
			System.out.println("Experiment " + (i + 1) + " consensus delay: " + consensusDelays[i] + " ms");
			totalConsensusDelay += consensusDelays[i];
		}

		double averageConsensusDelay = (double) totalConsensusDelay / NUMBER_OF_EXPERIMENTS;
		System.out.println(ONE_LINE);
		System.out.println("Average consensus delay: " + averageConsensusDelay + " ms");
		System.out.println(ONE_LINE);
	}

	private static void runOnce(ActorSystem system, ArrayList<ActorRef> references) throws InterruptedException {
		ArrayList<Integer> faultyIndexes = getFaultyIndexes();
		// Send special crash messages to f processes at random
		sendCrashMessages(faultyIndexes, references);

		// Start timer
		startTime = System.currentTimeMillis();

		// Start proposing
		for (ActorRef actor : references) {
			actor.tell(new LaunchMsg(), ActorRef.noSender());
		}

		// First Delay before leader election
		Thread.sleep(LEADER_ELECTION_TIMEOUT);
		do {
			// Choose new leader
			int newLeaderIndex = findNewLeaderIndex(faultyIndexes, references);
			ActorRef newLeader = references.get(newLeaderIndex);
			system.log().info("New leader index: " + newLeaderIndex);

			// Elect new leader
			newLeader.tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
			// Subsequent Delays
			Thread.sleep(LEADER_ELECTION_TIMEOUT);
		} while (!hasCalculatedDelay);

		while (decideCount.get() < N / 2) {
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
			runOnce(system, references); // TODO: system output got elect leader, but no effect to process. Can find a
											// way to eliminate it?
			experimentsDone++;
		}
		// Calculate consensus delay average
		calculateAverageConsensusDelay();

		system.terminate(); // Terminate the actor system
		System.out.println("System has terminated.");
		return;
	}

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
