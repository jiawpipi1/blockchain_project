package com.example;

public class GatherMsg {
	public final int ballot;
	public final int imposeballot;
	public final String estimate;

	public GatherMsg(int ballot, int imposeballot, String estimate) {
		this.ballot = ballot;
		this.imposeballot = imposeballot;
		this.estimate = estimate;
	}
}