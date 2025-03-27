package com.example;

public class ReadResponseMsg {
    public final int ballot;
    public final String proposal;
    
    public ReadResponseMsg(int ballot, String proposal) {
        this.ballot = ballot;
        this.proposal = proposal;
    }
}