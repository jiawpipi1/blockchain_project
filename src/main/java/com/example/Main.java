package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import scala.concurrent.duration.Duration;

import java.util.*;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
	public static final int N = 100;
	private static final int f = 10; // Number of processes that may crash
	private static final int LEADER_ELECTION_TIMEOUT = 500; // 500, 1000, 1500, 2000
	
<<<<<<< HEAD
	public static AtomicInteger decideCount = new AtomicInteger(0);
	public static long startTime;
	private static boolean hasCalculatedDelay = false;
	

=======
	public static int N = 3;
	public static AtomicInteger decideCount = new AtomicInteger(0);
	public static long startTime;
	private static int f = 1; // Number of processes that may crash
>>>>>>> 84c36972aea2620c6446ea97c64b13e2ae357605
	
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

	public static void main(String[] args) throws InterruptedException {

		// Instantiate an actor system
		final ActorSystem system = ActorSystem.create("system");
		system.log().info("System started with N=" + N);

		ArrayList<ActorRef> references = new ArrayList<>();

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
<<<<<<< HEAD
=======
		// Send special crash messages to f processes at random
		sendCrashMessages(references);
		
		int leaderIndex = new Random().nextInt(N);
		
		// Initiate leader election
		system.scheduler().scheduleOnce(Duration.create(500, TimeUnit.MILLISECONDS), references.get(leaderIndex),
				new LeaderSelectionMsg(leaderIndex + 1), system.dispatcher(), null);
>>>>>>> 84c36972aea2620c6446ea97c64b13e2ae357605

		ArrayList<Integer> faultyIndexes = getFaultyIndexes();
		// Send special crash messages to f processes at random
//		sendCrashMessages(faultyIndexes, references);

		// Start timer
		startTime = System.currentTimeMillis();
<<<<<<< HEAD

		// Start proposing (all processes)
		OfconsProposerMsg opm = new OfconsProposerMsg("100");
		references.get(0).tell(opm, ActorRef.noSender());

		// Initiate leader election every LEADER_ELECTION_TIMEOUT
		do {
			int newLeaderIndex = findNewLeaderIndex(faultyIndexes, references);
			ActorRef newLeader = references.get(newLeaderIndex);
			System.out.println("New leader index: " + newLeaderIndex);
			
			// Change LeaderSelectionMsg to TestMsg for testing
			// Cannot seem to make this work
//			system.scheduler().scheduleOnce(Duration.create(50, TimeUnit.MILLISECONDS), newLeader,
//					new LeaderSelectionMsg(newLeaderIndex + 1), system.dispatcher(), null);	
			// but this works
			newLeader.tell(new LeaderSelectionMsg(newLeaderIndex + 1), ActorRef.noSender());
			Thread.sleep(LEADER_ELECTION_TIMEOUT); 	// repeat
		} while (!hasCalculatedDelay); 	
//		System.out.println("out of the loop");
=======
		
		for (ActorRef actor : references) {
            actor.tell(new LaunchMsg(), ActorRef.noSender());
        }
>>>>>>> 84c36972aea2620c6446ea97c64b13e2ae357605
	}

	public static synchronized void reportDelay() {
		if (hasCalculatedDelay) { // no need to calculate delay again
			return;
		}
		hasCalculatedDelay = true;

		long delay = System.currentTimeMillis() - startTime;
		akka.event.Logging.getLogger(akka.actor.ActorSystem.create(), "Main")
				.info("Consensus delay = " + delay + " ms");

	}

}
