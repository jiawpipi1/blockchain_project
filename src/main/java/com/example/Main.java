package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;

import java.time.Duration;
import java.util.*;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

	public static final int N = 10; // 3,10,100
	private static final int f = 4; // Number of processes that may crash // 1,4,49
	private static final int LEADER_ELECTION_TIMEOUT = 500; // 500, 1000, 1500, 2000

	private static Cancellable electNewLeaderRepeatedly;
	public static AtomicInteger decideCount = new AtomicInteger(0);
	public static long startTime;
	private static boolean hasCalculatedDelay = false;
	private static ArrayList<ActorRef> references; // List of process references

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

		// Instantiate an actor system
		final ActorSystem system = ActorSystem.create("system");
		system.log().info("System started with N=" + N);

		references = new ArrayList<>();

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
 			System.out.println("New leader index: " + newLeaderIndex);
 			
 			// Elect new leader
 			newLeader.tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
 			// Subsequent Delays
 			Thread.sleep(LEADER_ELECTION_TIMEOUT);
 		} while (!hasCalculatedDelay); 

//		System.out.println("just before verifying leader election is cancelled");
//		while (electNewLeaderRepeatedly.isCancelled()) {
//			// Wait for leader election is cancelled so wont have new proposals
//		}
//		System.out.println("verified leader election is cancelled");
		while (decideCount.get() < N / 2 ) {
			// Wait for majority to decide
		}
		System.out.println("verified majority has decided");
		
		Thread.sleep(1500); // Sleep for a while to let processes finish
		restartProcesses(); // Restart processes
		Thread.sleep(1500); // Sleep for a while to let restart finish
		System.out.println("Reached the end of Main");
		return;
	}

	public static synchronized void reportDelay(int ID) {
		if (hasCalculatedDelay) { // no need to calculate delay again
			return;
		}
		hasCalculatedDelay = true;
//		if (electNewLeaderRepeatedly != null) {
//			electNewLeaderRepeatedly.cancel(); // Cancel the leader election
//		}

		long delay = System.currentTimeMillis() - startTime;
		akka.event.Logging.getLogger(akka.actor.ActorSystem.create(), "Main")
				.info("Process " + ID + " has reached DECIDE with consensus delay = " + delay + " ms");
	}

}
