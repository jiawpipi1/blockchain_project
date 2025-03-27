package com.example;

public class WriteMsg {
    public final int ballot;
    public final String proposal;
    
    public WriteMsg(int ballot, String proposal) {
        this.ballot = ballot;
        this.proposal = proposal;
    }
}