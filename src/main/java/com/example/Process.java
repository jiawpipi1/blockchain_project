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
	private boolean hold = false;
	private Members processes;
	private String proposal = null;
	private int ballot = 0;
	private int readballot = 0;
	private int imposeballot = 0;
	private String estimate = null;
	private Map<ActorRef, int[]> states = new HashMap<>();
	private Map<ActorRef, Integer> ackResponses = new HashMap<>();

	private boolean decided = false;
	private boolean crashed = false;
	private static final double CRASH_PROBABILITY = 0.1;

	public Process(int ID, int nb) {
		N = nb;
		id = ID;
	}

	public static Props createActor(int ID, int nb) {
		return Props.create(Process.class, () -> new Process(ID, nb));
	}

	private void startLeadership() {
		
		log.info("Process {} is elected as leader. Sending HOLD message...", id);
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new HoldMsg(), self());
			}
		}
	}

	private void handleHold() {
		log.info("Process {} received HOLD message. Stopping propose operations.", id);
		hold = true;
	}

	/**
	 * 發起提案 Propose()
	 */
	private void propose(String v) {
		if (crashed || decided)
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

	/**
	 * 接收 READballot 請求 Receive READballot request
	 */
	private void handleReadRequest(int b, ActorRef sender) {

		log.info("Process {} received ReadMsg: b={}, readballot={}, imposeballot={}", id, b, readballot, imposeballot);

		if (readballot > b || imposeballot > b) {
			sender.tell(new AbortMsg(b), self());
			log.info("Process {} aborts ReadMsg for b={}", id, b);
		} else {
			readballot = b;
			sender.tell(new GatherMsg(b, imposeballot, estimate), self());
			log.info("Process {} accepts ReadMsg and sends GatherMsg: new readballot={}, imposeballot={}, estimate={}",
					id, readballot, imposeballot, estimate);
		}
	}

	/**
	 * 處理 READballot 回應 Process READballot response
	 */
	private void handleReadResponse(int b, int estBallot, String est, ActorRef sender) {
		if (imposeballot == ballot) {
			log.info("Process {} is already in impose phase, ignoring new GatherMsg from {}", id, sender);
			return;
		}

		states.put(sender, new int[] { estBallot, est != null ? 1 : 0 });

		log.info("Process {} received GatherMsg: b={}, estBallot={}, statesSize={}", id, b, estBallot, states.size());
		log.info("and with est={}", est);

		if (states.size() > N / 2) {
			int maxBallot = -1;
			String selectedValue = null;

			for (int[] state : states.values()) {
				if (state[0] > maxBallot) {
					maxBallot = state[0];
					selectedValue = state[1] == 1 ? est : null;
				}
			}

			if (maxBallot > 0) {
				proposal = selectedValue;
			}

			imposeballot = ballot;
			ackResponses.clear();

			log.info("Process {} moves to write phase with proposal: {}", id, proposal);

			for (ActorRef actor : processes.references) {
				if (!actor.equals(self())) {
					actor.tell(new ImposMsg(ballot, proposal), self());
				}
			}
		}
	}

	/**
	 * 接收 IMPOSballot 請求 Receive IMPOSballot request
	 */
	private void handleImposeRequest(int b, String v, ActorRef sender) {
		log.info("Process {} received ImposMsg: b={}, v={}", id, b, v);
		log.info("Process {}, readballot={}, imposeballot={}, estimate={}", id, readballot, imposeballot, estimate);

		if (readballot > b || imposeballot > b) {
			sender.tell(new AbortMsg(b), self());
			log.info("Process {} rejects ImposMsg: b={}, current readballot={}, imposeballot={}", id, b, readballot,
					imposeballot);
			log.info("and estimate={}", estimate);
		} else {
			estimate = v;
			imposeballot = b;
			sender.tell(new AckMsg(b), self());
			log.info("Process {} accepts ImposMsg and sends AckMsg: new readballot={}, imposeballot={}, estimate={}",
					id, readballot, imposeballot, estimate);
		}
	}

	/**
	 * 處理 ACKballot 回應 Process ACKballot response
	 */
	private void handleAckResponse(int b, ActorRef sender) {
		if (decided)
			return;

		ackResponses.put(sender, b);
		log.info("Process {} recieves ack message, total ack received: {}", id, ackResponses.size());
		if (ackResponses.size() > N / 2) {
			decided = true;
			log.info("Process {} decides on message: {}", id, proposal);
			for (ActorRef actor : processes.references) {
				if (!actor.equals(self())) {
					actor.tell(new DecideMsg(proposal), self());
				}
			}
			int count = Main.decideCount.incrementAndGet();
			log.info("Current decideCount = {}", count);
		}
	}

	/**
	 * 接收 DECIDE 訊息 Receive DECIDE message
	 */
	private void handleDecide(String v) {
		if (decided)
			return;

		decided = true;
		proposal = v;
		log.info("Process {} final decision: {}", id, v);
		for (ActorRef actor : processes.references) {
			if (!actor.equals(self())) {
				actor.tell(new DecideMsg(v), self());
			}
		}

		int count = Main.decideCount.incrementAndGet();
		log.info("Current decideCount = {} and N = {}", count, N);

		if (count == N) {
			log.info("Consensus reached! Reporting delay...");
			Main.reportDelay();
		}
	}

	@Override
	public void onReceive(Object message) {
		if (crashed)
			return;

		if (message instanceof Members) {
			processes = (Members) message;
			// log.info("Process {} received process list", id);
		} else if (message instanceof LeaderSelectionMsg) {
			startLeadership(); // 成為 leader，發送 HOLD 訊息
		} else if (message instanceof HoldMsg) {
			handleHold();
		} else if (message instanceof OfconsProposerMsg) {
			propose(((OfconsProposerMsg) message).message);
		} else if (message instanceof ReadMsg) {
			handleReadRequest(((ReadMsg) message).ballot, getSender());
		} else if (message instanceof GatherMsg) {
			GatherMsg msg = (GatherMsg) message;
			handleReadResponse(msg.ballot, msg.imposeballot, msg.estimate, getSender());
		} else if (message instanceof ImposMsg) {
			ImposMsg msg = (ImposMsg) message;
			handleImposeRequest(msg.ballot, msg.proposal, getSender());
		} else if (message instanceof AckMsg) {
			handleAckResponse(((AckMsg) message).ballot, getSender());
		} else if (message instanceof DecideMsg) {
			handleDecide(((DecideMsg) message).proposal);
		}
	}
}
