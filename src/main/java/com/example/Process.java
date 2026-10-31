package com.example;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedAbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class Process extends UntypedAbstractActor {
	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
	private final int N;
	private final int id;
	private final int f;
	private final int tle;
	private final double alpha;
	private final int runId; // experiment run this actor belongs to
	private int ballot;
	private int readballot = 0;
	private int imposeballot;
	private int msgballot = 0;
	private String proposal = null;
	private String estimate = null;
	private Members processes;
	private Map<ActorRef, int[]> states = new HashMap<>();
	private Map<ActorRef, Integer> ackResponses = new HashMap<>();
	// Value carried by the highest imposeballot seen in the current read phase
	private int highestEstBallot = Integer.MIN_VALUE;
	private String highestEst = null;
	private boolean imposeSent = false;

	private boolean hold = false;
	private boolean decided = false;
	private boolean isFaultProneMode = false;
	private boolean isSilentMode = false;
	private boolean isDebugMode = false; // For debugging purposes


	public Process(int ID, int nb, int f, int tle, double alpha) {
		this(ID, nb, f, tle, alpha, 0);
	}

	public Process(int ID, int nb, int f, int tle, double alpha, int runId) {
		this.runId = runId;
		this.id = ID;
		this.N = nb;
		this.ballot = ID - nb;
		this.imposeballot = ID - nb;
		this.f = f;
		this.tle = tle;
		this.alpha = alpha;
		
	}
	
	
	public static Props createActor(int id, int n, int f, int tle, double alpha) {
	    return Props.create(Process.class, () -> new Process(id, n, f, tle, alpha));
	}

	public static Props createActor(int id, int n, int f, int tle, double alpha, int runId) {
	    return Props.create(Process.class, () -> new Process(id, n, f, tle, alpha, runId));
	}
	
	/*
	public static Props createActor(int ID, int nb) {
		return Props.create(Process.class, () -> new Process(ID, nb));
	}*/

	private void handleLaunch() {
		String value = (Math.random() >= 0.5) ? "1" : "0";
		propose(value);
	}

	private void startLeadership() {
		if (decided) {
			return; 
		}

		log.info("Process {} is elected as leader. Sending HOLD message...", id);
		hold = false;
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new HoldMsg(), self());
			}
		}
		// Make sure the leader is driving a ballot that the others no longer interrupt
		if (proposal == null) {
			proposal = (Math.random() >= 0.5) ? "1" : "0";
		}
		propose(proposal);
	}

	private void handleHold() {
		hold = true;
	}


	private void propose(String v) { // TODO: change to int?
		if (decided)
			return;

		proposal = v;
		ballot += N;
		states.clear();
		ackResponses.clear();
		highestEstBallot = Integer.MIN_VALUE;
		highestEst = null;
		imposeSent = false;

		if (isDebugMode) {
			log.info("Process {} proposes message: {}", id, v);
		}

		// The proposer is also an acceptor, so it sends to itself as well
		for (ActorRef actor : processes.references) {
			actor.tell(new ReadMsg(ballot), self());
		}
	}

	private int majority() {
		return N / 2 + 1;
	}

	private void handleAbort(int b) {
		if (b == ballot && !decided) {
			states.clear();
			ackResponses.clear();
			if (!hold)
				propose(proposal);
		}
	}


	private void handleReadRequest(int b, ActorRef sender) {

		if (readballot > b || imposeballot > b) {
			sender.tell(new AbortMsg(b), self());
		} else {
			readballot = b;
			sender.tell(new GatherMsg(b, imposeballot, estimate), self());

			
			if (isDebugMode) { // Output more info
				log.info("Process {} accepts ReadMsg and sends GatherMsg: new readballot={}, imposeballot={}, estimate={}",
			 id, readballot, imposeballot, estimate);
			}
		}
	}


	private void handleReadResponse(int b, int estBallot, String est, ActorRef sender) {
		// Ignore stale replies and replies arriving after the impose phase started
		if (decided || b != ballot || imposeSent) {
			return;
		}

		states.put(sender, new int[] { estBallot, est != null ? 1 : 0 });
		if (est != null && estBallot > highestEstBallot) {
			highestEstBallot = estBallot;
			highestEst = est;
		}

		if (states.size() >= majority()) {
			// Safety: adopt the value of the highest ballot already accepted by someone
			if (highestEst != null) {
				proposal = highestEst;
			}
			imposeSent = true;
			ackResponses.clear();

			for (ActorRef actor : processes.references) {
				actor.tell(new ImposeMsg(ballot, proposal), self());
			}
		}
	}


	private void handleImposeRequest(int b, String v, ActorRef sender) {
		if (readballot > b || imposeballot > b) {
			//log.info("abort impose readballot = {}, imposeballot = {}, recieved ballot = {} ", readballot, imposeballot, b );
			sender.tell(new AbortMsg(b), self());
		} else {
			//log.info("process {} accept impose readballot = {}, imposeballot = {}, recieved ballot = {} ", id , readballot, imposeballot, b );
			estimate = v;
			imposeballot = b;
			sender.tell(new AckMsg(b), self());

			
			if (isDebugMode) { // Output more info
				log.info("Process {} accepts ImposMsg and sends AckMsg: new readballot={}, imposeballot={}, estimate={}",
						id, readballot, imposeballot, estimate);
			}
			 
		}
	}

	private void handleAckResponse(int b, ActorRef sender) {
		if (decided || b != ballot) // ignore acks of an older ballot
			return;

		ackResponses.put(sender, b);
		if (ackResponses.size() >= majority()) {
			decide(proposal, b);
		}
	}

	private void handleDecide(String v, int from, int b) {
		if (decided) {
			return;
		}
		decide(v, b);
	}

	// Decide once, then relay so every live process learns the value
	private void decide(String v, int b) {
		decided = true;
		estimate = v;
		msgballot = b;
		Main.reportDecision(runId, id, v);
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new DecideMsg(v, id, b), self());
			}
		}
	}


	private boolean determineIfWillCrash() {
		if (Math.random() >= alpha) {
			return false;
		}
		// Process will crash
		isSilentMode = true;
		if (isDebugMode) {
			log.info("Process {} has crashed prob = {}.", id, alpha);
		}
		return true;
	}

	// Restart a process
	private void handleRestart() {

		ballot = id - N;
		readballot = 0;
		imposeballot = id - N;
		msgballot = 0;
		proposal = null;
		estimate = null;
		states.clear();
		ackResponses.clear();
		highestEstBallot = Integer.MIN_VALUE;
		highestEst = null;
		imposeSent = false;
		hold = false;
		decided = false;
		isFaultProneMode = false;
		isSilentMode = false;

//		log.info("Process {} has restarted.", id);
	}

	// Returns true if process is fault-prone
	public boolean checkIfFaultProne() {
		return isFaultProneMode;
	}

	@Override
	public void onReceive(Object message) {
		if (message instanceof RestartMsg) {
			handleRestart();
			return;
		}
		if (isSilentMode) {
			return;
		}
		if (isFaultProneMode && determineIfWillCrash()) {
			return; // Process crashes
		}
		// Process does not crash, continues as per normal

		if (message instanceof Members) {
			processes = (Members) message;
			// log.info("Process {} received process list", id);
		} else if (message instanceof LaunchMsg) {
			handleLaunch();
		} else if (message instanceof CrashMsg) {
			isFaultProneMode = true;
		} else if (message instanceof LeaderSelectionMsg) {
			startLeadership(); 
		} else if (message instanceof HoldMsg) {
			handleHold();
		} else if (message instanceof ReadMsg) {
			handleReadRequest(((ReadMsg) message).ballot, getSender());
		} else if (message instanceof GatherMsg) {
			GatherMsg msg = (GatherMsg) message;
			handleReadResponse(msg.ballot, msg.imposeballot, msg.estimate, getSender());
		} else if (message instanceof ImposeMsg) {
			ImposeMsg msg = (ImposeMsg) message;
			handleImposeRequest(msg.ballot, msg.proposal, getSender());
		} else if (message instanceof AckMsg) {
			handleAckResponse(((AckMsg) message).ballot, getSender());
		} else if (message instanceof AbortMsg) {
			handleAbort(((AbortMsg) message).ballot);
		} else if (message instanceof DecideMsg) {
			handleDecide(((DecideMsg) message).proposal, ((DecideMsg) message).id, ((DecideMsg) message).b);
		}
	}
}
