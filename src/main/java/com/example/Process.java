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

	private static final double CRASH_PROBABILITY = 1;
	
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
			return; // Already decided, no need to start leadership
		}

		log.info("Process {} is elected as leader. Sending HOLD message...", id);
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new HoldMsg(), self());
			}
		}
	}

	// After receive HOLD message, process stops proposing. Only leader can propose.
	private void handleHold() {
		log.info("Process {} received HOLD message. Stopping propose operations.", id);
		hold = true;
	}

	/**
	 * 發起提案 Propose()
	 */
	private void propose(String v) { // TODO: change to int?
		if (decided)
			return;

		proposal = v;
		ballot += N;
		states.clear();

		// log.info("Process {} proposes message: {}", id, v);

		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new ReadMsg(ballot), self());
			}
		}
	}

	private void handleAbort(int b) {
		if (b == ballot) {
			// log.info("Process {} received AbortMsg for ballot {}. Retrying proposal...",
			// id, b);

			// 清空現有的 state 以便重新發起提案
			states.clear();
			ackResponses.clear();

			// 重新發起提案
			propose(proposal);
		}
	}

	/**
	 * 接收 READballot 請求 Receive READballot request
	 */
	private void handleReadRequest(int b, ActorRef sender) {

		// log.info("Process {} received ReadMsg: b={}, readballot={}, imposeballot={}",
		// id, b, readballot, imposeballot);

		if (readballot > b || imposeballot > b) {
			sender.tell(new AbortMsg(b), self());
			// log.info("Process {} aborts ReadMsg for b={}", id, b);
		} else {
			readballot = b;
			sender.tell(new GatherMsg(b, imposeballot, estimate), self());
//			log.info("Process {} accepts ReadMsg and sends GatherMsg: new readballot={}, imposeballot={}, estimate={}",
//			 id, readballot, imposeballot, estimate);
		}
	}

	/**
	 * 處理 READballot 回應 Process READballot response
	 */
	private void handleReadResponse(int b, int estBallot, String est, ActorRef sender) {
		if (imposeballot == ballot) {
			// log.info("Process {} is already in impose phase, ignoring new GatherMsg from
			// {}", id, sender);
			return;
		}

		states.put(sender, new int[] { estBallot, est != null ? 1 : 0 });

		// log.info("Process {} received GatherMsg: b={}, estBallot={}, statesSize={}",
		// id, b, estBallot, states.size());
		// log.info("and with est={}", est);
		if (states.size() >= N / 2) {
			int maxBallot = -1;
			String selectedValue = null;

			for (Map.Entry<ActorRef, int[]> entry : states.entrySet()) {
				int[] state = entry.getValue();
				if (state[0] > maxBallot) { // 選擇最大的 ballot
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

			imposeballot = ballot;
			ackResponses.clear();

			// log.info("Process {} moves to write phase with proposal: {}", id, proposal);

			for (ActorRef actor : processes.references) {
				if (!actor.equals(self())) {
					actor.tell(new ImposeMsg(ballot, proposal), self());
				}
			}
		}
	}

	/**
	 * 接收 IMPOSballot 請求 Receive IMPOSballot request
	 */
	private void handleImposeRequest(int b, String v, ActorRef sender) {
//		 log.info("Process {} received ImposMsg: b={}, v={}", id, b, v);
		// log.info("Process {}, readballot={}, imposeballot={}, estimate={}", id,
		// readballot, imposeballot, estimate);

		if (readballot > b || imposeballot > b) {
			sender.tell(new AbortMsg(b), self());
			// log.info("Process {} rejects ImposMsg: b={}, current readballot={},
			// imposeballot={}", id, b, readballot,
			// imposeballot);
			// log.info("and estimate={}", estimate);
		} else {
			estimate = v;
			imposeballot = b;
			sender.tell(new AckMsg(b), self());
//			log.info("Process {} accepts ImposMsg and sends AckMsg: new readballot={}, imposeballot={}, estimate={}",
//			 id, readballot, imposeballot, estimate);
		}
	}

	/**
	 * 處理 ACKballot 回應 Process ACKballot response
	 */
	private void handleAckResponse(int b, ActorRef sender) {
		if (decided)
			return;

		ackResponses.put(sender, b);
		// log.info("Process {} recieves ack message, total ack received: {}", id,
		// ackResponses.size());
		if (ackResponses.size() >= N / 2) {
			Main.reportDelay(id);
			decided = true;
			log.info("Process {} decides on message: {}", id, proposal);
			for (ActorRef actor : processes.references) {
				if (!actor.equals(self())) {
					actor.tell(new DecideMsg(proposal, id), self());
				}
			}
			int count = Main.decideCount.incrementAndGet();
			// log.info("Current decideCount = {}", count);
		}
	}

	/**
	 * 接收 DECIDE 訊息 Receive DECIDE message
	 */
	private void handleDecide(String v, int from) {
		if (proposal != null && proposal.equals(v)) { // @LEO can delete?
			if (decided) {
				return;
			}
		} else {
			log.info("Process {} received a new decision proposal: {} from {}", id, v, from);
			proposal = v;
		}
		// if (decided)
		// return;

		decided = true;
		// proposal = v;

		log.info("Process {} final decision: {} from process {}", id, v, from);
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new DecideMsg(v, from), self());
			}
		}

		int count = Main.decideCount.incrementAndGet();
		log.info("Current decideCount = {} and N = {}", count, N);
	}

	// Determines if process crash. If crash, enters silent mode forever, else
	// continues.
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
			startLeadership(); // 成為 leader，發送 HOLD 訊息
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
			handleDecide(((DecideMsg) message).proposal, ((DecideMsg) message).id);
		}
	}
}
