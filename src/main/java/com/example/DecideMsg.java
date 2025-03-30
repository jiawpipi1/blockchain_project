package com.example;

public class DecideMsg {
	public final String proposal;
	public int id;

	public DecideMsg(String proposal, int id) {
		this.id = id;
		this.proposal = proposal;
	}
}