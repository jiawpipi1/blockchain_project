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
	private int ballot;
	private int readballot = 0;
	private int imposeballot;
	private int msgballot = 0;
	private String proposal = null;
	private String estimate = null;
	private Members processes;
	private Map<ActorRef, int[]> states = new HashMap<>();
	private Map<ActorRef, Integer> ackResponses = new HashMap<>();
	private Map<ActorRef, String> estimateMap = new HashMap<>();

	private boolean hold = false;
	private boolean decided = false;
	private boolean isFaultProneMode = false;
	private boolean isSilentMode = false;
	private boolean isDebugMode = false; // For debugging purposes

	private static final double CRASH_PROBABILITY = 0;

	public Process(int ID, int nb) {
		this.id = ID;
		this.N = nb;
		this.ballot = ID - nb;
		this.imposeballot = ID - nb;
	}
	
	public static Props createActor(int ID, int nb) {
		return Props.create(Process.class, () -> new Process(ID, nb));
	}

	private void handleLaunch() {
		String value = (Math.random() >= 0.5) ? "1" : "0";
		propose(value);
	}

	private void startLeadership() {
		if (decided) {
			return; 
		}

		log.info("Process {} is elected as leader. Sending HOLD message...", id);
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new HoldMsg(), self());
			}
		}
	}

	private void handleHold() {
		//log.info("Process {} received HOLD message. Stopping propose operations.", id);
		hold = true;
	}


	private void propose(String v) { // TODO: change to int?
		if (decided)
			return;

		proposal = v;
		ballot += N;
		states.clear();

		 log.info("Process {} proposes message: {}", id, v);


		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new ReadMsg(ballot), self());
			}
		}
	}

	private void handleAbort(int b) {
		if (b == ballot) {

			states.clear();
			ackResponses.clear();
			if(!hold)
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
		if (imposeballot == ballot) {
			return;
		}

		states.put(sender, new int[] { estBallot, est != null ? 1 : 0 });

		if (states.size() >= N / 2) {
			int maxBallot = -1;
			String selectedValue = null;

			for (Map.Entry<ActorRef, int[]> entry : states.entrySet()) {
				int[] state = entry.getValue();
				if (state[0] > maxBallot) { 
					maxBallot = state[0];
					if (estimateMap.get(entry.getKey()) != null) {
						selectedValue = estimateMap.get(entry.getKey());
					} else
						selectedValue = proposal;
				}
			}

			if (maxBallot > 0) {
				proposal = selectedValue;
			}

			ackResponses.clear();

			//log.info("Process {} moves to write phase with proposal: {}", id, proposal);

			for (ActorRef actor : processes.references) {
				if (!actor.equals(self())) {
					actor.tell(new ImposeMsg(ballot, proposal), self());
				}
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
		if (decided)
			return;

		ackResponses.put(sender, b);
		//log.info("Process {} recieves ack message, with ballot:{} total ack received: {}", id, b, ackResponses.size());
		if (ackResponses.size() >= N / 2) {
			Main.reportDelay(id);
			decided = true;
			//log.info("Process {} decides on message: {}  with ballot: {}", id, proposal ,b);
			for (ActorRef actor : processes.references) {
				if (!actor.equals(self())) {
					actor.tell(new DecideMsg(proposal, id, b), self());
				}
			}
			int count = Main.decideCount.incrementAndGet();
		}
	}

	
	private void handleDecide(String v, int from, int b) {
		if(b>msgballot) {
			estimate = v;
			msgballot = b;
		}
		else{
			//log.info("Process {} msgballot: {} from process {} with ballot {}", id,msgballot, from, b );
			return;
		}
		decided = true;
		//log.info("Process {} final decision: {} from process {} with ballot {}", id, v, from, b );
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new DecideMsg(v, from, msgballot), self());
			}
		}

		int count = Main.decideCount.incrementAndGet();
	}


	private boolean determineIfWillCrash() {
		if (Math.random() >= CRASH_PROBABILITY) {
			return false;
		}
		// Process will crash
		isSilentMode = true;
		log.info("Process {} has crashed prob = {}.", id, CRASH_PROBABILITY);
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
		estimateMap.clear();
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
