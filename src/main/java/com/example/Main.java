package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.actor.PoisonPill;

import java.time.Duration;
import java.util.*;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
	private static final int LEADER_ELECTION_TIMEOUT = 500; // 500, 1000, 1500, 2000
	private static final int DELAY_BETWEEN_EXPERIMENTS = 500;
	private static final int NUMBER_OF_EXPERIMENTS = 15;
	private static final String ONE_LINE = "--------------------------------------------------";

	public static final int N = 100; // 3,10,100
	private static final int f = 49; // Number of processes that may crash // 1,4,49
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

	private static ArrayList<Integer> getFaultyIndexes() {
		ArrayList<Integer> fullListIndexes = new ArrayList<Integer>();
		for (int j = 0; j < N; j++) {
			fullListIndexes.add(j);
		}
		Collections.shuffle(fullListIndexes);

		ArrayList<Integer> faultyIndexes = new ArrayList<Integer>();
		for (int k = 0; k < f; k++) {
			faultyIndexes.add(fullListIndexes.get(k));
		}
		return faultyIndexes;

	}

	private static void resetSystem() {
		hasCalculatedDelay = false;
		decideCount.set(0);
		for (ActorRef actor : references) {
			actor.tell(PoisonPill.getInstance(), ActorRef.noSender()); // 通知 Actor 結束
		}
		references.clear();
	}

	private static void calculateAverageConsensusDelay(int start) {
		long totalConsensusDelay = 0;
		for (int i = start - 4; i < start + 1; i++) {
			System.out.println("Experiment " + (i + 1) + " consensus delay: " + consensusDelays[i] + " ms");
			totalConsensusDelay += consensusDelays[i];
		}

		double averageConsensusDelay = (double) totalConsensusDelay / NUMBER_OF_EXPERIMENTS;
		System.out.println(ONE_LINE);
		System.out.println("Average consensus delay: " + averageConsensusDelay + " ms");
		System.out.println(ONE_LINE);
	}

	private static void runOnce(ActorSystem system, ArrayList<ActorRef> references, double crash)
			throws InterruptedException {
		for (int i = 0; i < N; i++) {
			// Instantiate processes
			String actorName = "actor_" + i + "_" + System.nanoTime();
			final ActorRef a = system.actorOf(Process.createActor(i + 1, N, crash), actorName);
			references.add(a);
		}

		// give each process a view of all the other processes
		Members m = new Members(references);
		for (ActorRef actor : references) {
			actor.tell(m, ActorRef.noSender());
		}
		ArrayList<Integer> faultyIndexes = getFaultyIndexes();
		sendCrashMessages(faultyIndexes, references);

		for (ActorRef actor : references) {
			actor.tell(new LaunchMsg(), ActorRef.noSender());
		}

		// First Delay before leader election
		Thread.sleep(LEADER_ELECTION_TIMEOUT);
		int newLeaderIndex = findNewLeaderIndex(faultyIndexes, references);
		ActorRef newLeader = references.get(newLeaderIndex);
		//system.log().info("New leader index: " + newLeaderIndex);

		// Elect new leader
		newLeader.tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
		// Subsequent Delays
		Thread.sleep(LEADER_ELECTION_TIMEOUT);

		while (decideCount.get() < N / 2) {
			// System.out.println(decideCount.get());
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

		// Run experiments repeatedly
		for (int i = 0; i < NUMBER_OF_EXPERIMENTS; i++) {
			system.log().info("Starting experiment " + (i + 1));
			startTime = System.currentTimeMillis();
			if (i < 5) {
				runOnce(system, references, 0);
			} else if (i < 10) {
				runOnce(system, references, 0.1);
			} else {
				runOnce(system, references, 1);
			}
			// TODO: system output got elect leader, but no effect to process. Can find a
			if (i == 4) {
				calculateAverageConsensusDelay(i);
			} else if (i == 9) {
				calculateAverageConsensusDelay(i);
			} else if (i == 14) {
				calculateAverageConsensusDelay(i);

			}

			experimentsDone++;

		}
		// Calculate consensus delay average

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
