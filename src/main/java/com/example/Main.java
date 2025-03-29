package com.example;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import scala.concurrent.duration.Duration;

import java.util.*;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
	
	public static int N = 3;
	public static AtomicInteger decideCount = new AtomicInteger(0);
	public static long startTime;
	private static int f = 1; // Number of processes that may crash
	
	// Send special crash messages to f processes at random
	private static void sendCrashMessages(ArrayList<ActorRef> references) {
		// Create random list of indexes
		ArrayList<Integer> randomList = new ArrayList<Integer>();
		for (int j = 0; j < N; j++) {
			randomList.add(j); 
		}
		Collections.shuffle(randomList);
		
		// Send crash messages
		for (int k = 0; k < f; k++) {
			int randomProcess = randomList.get(k);
			references.get(randomProcess).tell(new CrashMsg(true), ActorRef.noSender());
		}
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
		// Send special crash messages to f processes at random
		sendCrashMessages(references);
		
		int leaderIndex = new Random().nextInt(N);
		
		// Initiate leader election
		system.scheduler().scheduleOnce(Duration.create(500, TimeUnit.MILLISECONDS), references.get(leaderIndex),
				new LeaderSelectionMsg(leaderIndex + 1), system.dispatcher(), null);

		startTime = System.currentTimeMillis();
		
		for (ActorRef actor : references) {
            actor.tell(new LaunchMsg(), ActorRef.noSender());
        }
	}

	public static synchronized void reportDelay() {
		long delay = System.currentTimeMillis() - startTime;
		akka.event.Logging.getLogger(akka.actor.ActorSystem.create(), "Main")
				.info("Consensus delay = " + delay + " ms");
	}

}
