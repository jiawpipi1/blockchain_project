package com.example;

public class DecideMsg {
	public final String proposal;
	public int id;
	public int b;

	public DecideMsg(String proposal, int id, int b) {
		this.id = id;
		this.proposal = proposal;
		this.b = b;
	}
}