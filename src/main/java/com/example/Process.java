package com.example;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedAbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;

public class Process extends UntypedAbstractActor {
    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);// Logger attached to actor
    private final int N;//number of processes
    private final int id;//id of current process
    private Members processes;//other processes' references
    private String proposal;
    private int ballot;
    
    
    private Map<ActorRef, String> readResponses = new HashMap<>(); // 存儲讀取回應
    private Map<ActorRef, Integer> writeResponses = new HashMap<>(); // 存儲寫入回應
    
    private boolean decided = false; // 是否已經做出決定
    private boolean crashed = false; // 進程是否已經崩潰

    private static final double CRASH_PROBABILITY = 0.1; // 進程崩潰的機率 (α)
    
    public Process(int ID, int nb) {
        N = nb;
        id = ID;
    }

    public static Props createActor(int ID, int nb) {
        return Props.create(Process.class, () -> new Process(ID, nb));
    }

    private void propose(String v) {
        if (crashed || decided) return; // 如果崩潰或已決定，則不執行
        
        proposal = v;
        ballot += 1; // 提升提案輪數
        readResponses.clear();
        
        log.info("Process {} proposes message: {}", id, v);

        // 發送讀取請求給所有進程
        for (ActorRef actor : processes.references) {
            actor.tell(new ReadMsg(ballot), self());
        }
    }

    private void handleReadResponse(int newBallot, String proposedValue, ActorRef sender) {
        if (crashed) return;

        readResponses.put(sender, proposedValue);
        
        // 若收到超過半數的回應，則選擇最多數的值
        if (readResponses.size() > N / 2) {
            Map<String, Integer> frequencyMap = new HashMap<>();
            for (String value : readResponses.values()) {
                frequencyMap.put(value, frequencyMap.getOrDefault(value, 0) + 1);
            }

            // 選擇出現最多次的值
            proposal = Collections.max(frequencyMap.entrySet(), Map.Entry.comparingByValue()).getKey();
            writeResponses.clear();

            log.info("Process {} moves to write phase with message: {}", id, proposal);
            
            // 發送寫入請求
            for (ActorRef actor : processes.references) {
                actor.tell(new WriteMsg(ballot, proposal), self());
            }
        }
    }

    private void handleWriteResponse(int newBallot, ActorRef sender) {
        if (crashed) return;

        writeResponses.put(sender, newBallot);
        
        // 若收到超過半數的寫入回應，則決定值
        if (writeResponses.size() > N / 2) {
            decided = true;
            log.info("Process {} decides on message: {}", id, proposal);
        }
    }

    private void crash() {
        crashed = true;
        log.info("Process {} has crashed!", id);
    }

    @Override
    public void onReceive(Object message) {
        if (crashed) return; // 如果已崩潰，不再處理消息

        if (message instanceof Members) {
            processes = (Members) message;
            log.info("Process {} received process list: {}", id, processes.data);
        } 
        else if (message instanceof OfconsProposerMsg) {
            // 收到提議請求，開始提議值
            propose(((OfconsProposerMsg) message).message);
        } 
        else if (message instanceof ReadMsg) {
            ReadMsg m = (ReadMsg) message;
            getSender().tell(new ReadResponseMsg(m.ballot, proposal), self());
            log.info("Process {} responds to read request with message: {}", id, proposal);
        } 
        else if (message instanceof ReadResponseMsg) {
            ReadResponseMsg m = (ReadResponseMsg) message;
            handleReadResponse(m.ballot, m.proposal, getSender());
        } 
        else if (message instanceof WriteMsg) {
            WriteMsg m = (WriteMsg) message;
            getSender().tell(new WriteResponseMsg(m.ballot), self());
            log.info("Process {} acknowledges write request", id);
        } 
        else if (message instanceof WriteResponseMsg) {
            WriteResponseMsg m = (WriteResponseMsg) message;
            handleWriteResponse(m.ballot, getSender());
        }
    }
}
