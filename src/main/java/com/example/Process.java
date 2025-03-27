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
    private final int N; // 總進程數
    private final int id; // 進程 ID
    private Members processes; // 其他進程的參考
    private String proposal = null;
    private int ballot = 0;
    private int readballot = 0;
    private int imposeballot = 0;
    private String estimate = null;
    private Map<ActorRef, int[]> states = new HashMap<>(); // 存儲 [imposeballot, estimate]
    private Map<ActorRef, Integer> ackResponses = new HashMap<>(); // 存儲 ACK 回應

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

    /**
     * 發起提案
     */
    private void propose(String v) {
        if (crashed || decided) return;
        proposal = v;
        ballot += N; // 遵循 pseudocode，將 ballot 增加 N
        states.clear();

        log.info("Process {} proposes message: {}", id, v);

        for (ActorRef actor : processes.references) {
            actor.tell(new ReadMsg(ballot), self());
        }
    }

    /**
     * 接收 READballot 請求
     */
    private void handleReadRequest(int b, ActorRef sender) {
        if (readballot > b || imposeballot > b) {
            sender.tell(new AbortMsg(b), self());
        } else {
            readballot = b;
            sender.tell(new GatherMsg(b, imposeballot, estimate), self());
        }
    }

    /**
     * 處理 READballot 回應
     */
    private void handleReadResponse(int b, int estBallot, String est, ActorRef sender) {
        states.put(sender, new int[]{estBallot, est != null ? 1 : 0}); // estBallot: past imposeballot, est: 是否有值

        if (states.size() > N / 2) { // 達到多數
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

            log.info("Process {} moves to write phase with message: {}", id, proposal);

            for (ActorRef actor : processes.references) {
                actor.tell(new ImposMsg(ballot, proposal), self());
            }
        }
    }

    /**
     * 接收 IMPOSballot 請求
     */
    private void handleImposeRequest(int b, String v, ActorRef sender) {
        if (readballot > b || imposeballot > b) {
            sender.tell(new AbortMsg(b), self());
        } else {
            estimate = v;
            imposeballot = b;
            sender.tell(new AckMsg(b), self());
        }
    }

    /**
     * 處理 ACKballot 回應
     */
    private void handleAckResponse(int b, ActorRef sender) {
        ackResponses.put(sender, b);

        if (ackResponses.size() > N / 2) { // 達到多數
            decided = true;
            log.info("Process {} decides on message: {}", id, proposal);
            for (ActorRef actor : processes.references) {
                actor.tell(new DecideMsg(proposal), self());
            }
        }
    }

    /**
     * 接收 DECIDE 訊息
     */
    private void handleDecide(String v) {
        if (!decided) {
            decided = true;
            proposal = v;
            log.info("Process {} final decision: {}", id, v);
            for (ActorRef actor : processes.references) {
                actor.tell(new DecideMsg(v), self());
            }
        }
    }

    @Override
    public void onReceive(Object message) {
        if (crashed) return;

        if (message instanceof Members) {
            processes = (Members) message;
            log.info("Process {} received process list", id);
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
