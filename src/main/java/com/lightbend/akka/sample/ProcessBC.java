package com.lightbend.akka.sample;

import akka.actor.UntypedAbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import java.util.ArrayList;


public class ProcessBC extends UntypedAbstractActor {

	//private LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);  
	private LoggingAdapter log = null;



	//static public Props props() {
	//  return Props.create(Process.class, () -> {
	//			return new Process();
	//  });
	//}

	static public Props props(int id) {
		return Props.create(ProcessBC.class, () -> new ProcessBC(id));
	}




	//#system-members
	static public class Members {
		public final int Nmembers;
		public final ArrayList<ActorRef> members; 

		public Members(ArrayList<ActorRef> members) {
			this.Nmembers = members.size();
			this.members = members;
		}
	}




	//#state, 2 - faulty, 1 - active, 3 - waiting
	static public class State {
		public final int state;

		public State(int state) {
			this.state = state;
		}
	}

//	// local value with (seqnum, process id) timestamp
//	static public class StampedValue {
//		public int value;
//		public int seqnum;
//		public int pid;
//
//		public StampedValue(int value, int seqnum, int pid) {
//			this.value = value;
//			this.seqnum = seqnum;
//			this.pid = pid;
//		}
//	}

	// the "Quorum-request" message type with a timestamped value and the local seq number
	static public class QuorumRequest{
		// public StampedValue val; 
		public int localseqnum;

		public QuorumRequest(int localseqnum) {
			//  this.val = val;
			this.localseqnum = localseqnum;
		}
	}

	// the "Quorum-response" message type with a local seq number
	static public class QuorumResponse{
		public int localseqnum;

		public QuorumResponse(int localseqnum) {
			this.localseqnum = localseqnum;
		}
	}


//	// the "read-request" message type with the local seq number
//	static public class ReadRequest{
//		public int localseqnum;
//
//		public ReadRequest(int localseqnum) {
//			this.localseqnum = localseqnum;
//		}
//	}
//
//	// the "read-response" message type with a timestamped value and the local seq number
//	static public class ReadResponse{
//		public StampedValue val; 
//		public int localseqnum;
//
//		public ReadResponse(StampedValue val,int localseqnum) {
//			this.val = val;
//			this.localseqnum = localseqnum;
//		}
//	}


	// process identifier
	private final int id;

	// system members known to the process
	private  Members mem;

	// process state
	private int state;

	//  // currently executed operation: 0 - no operation is active, 1 - get, 2- put 
	//  private int activeop; 

	//  // local copy of the register value
	//  private StampedValue val;

	// local sequence number: the number of operations performed so far
	private int localseqnum;

	// local message buffer (to store messages received in a Quorum or read phase)
	private ArrayList msgs = new ArrayList();

	// number of operations to perform 
	private final int M=3;

	//number of operations performed so far
	private int ops=1;


	public ProcessBC(int id) { 
		this.state = 0;
		//	this.val = new StampedValue(0,0,0);
		this.id = id;
		//    this.activeop = 2; // initial operation is put
		this.localseqnum = 0;
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"[%1$tF %1$tT] [%4$-7s] %5$s %n");
		log = Logging.getLogger(getContext().getSystem(), this);

	}


	@Override
	public void onReceive(Object msg) throws Exception {
		if (this.state != 2) { // If not faulty
			ActorRef actorRef = getSender();
			if (msg instanceof Members) {
				this.mem = (Members) msg; // determine the system members
				this.localseqnum= this.id ;
				this.state = 3;
				for(int x = 0; x <= mem.Nmembers - 1; x = x + 1) {
				     mem.members.get(x).tell(new QuorumRequest(localseqnum),getSelf());
				     log.info("sent request number " + localseqnum);
				}    
				//for(int x = 0; x < this.mem.members.size(); x = x + 1) {
				//log.info(this.name+": know member "+Integer.toString(x)); 
				//this.mem.members.get(x).tell("Message to "+this.mem.members.get(x),getSelf()); 
				//}
			} //else
			//if (msg instanceof String) {
			// 	 log.info("P"+this.id+": received a string message '"+msg+"' from "+actorRef); 	     
			// }
			//	     else
			//	     if (msg instanceof State) { // failure indication 
			//	    	 	 this.state = ((State) msg).state; 
			//		    	 if (this.state==1) { // the process is active
			//		    		 // start a put operation 
			//		    		 this.localseqnum++;
			//		    		 if (this.activeop== 2) {
			//		    			 log.info("P"+this.id+": invokes operation "+(this.localseqnum+1)/2+": put("+this.id+")");
			//		    		 } else 
			//		    			 log.info("P"+this.id+": invokes operation "+(this.localseqnum+1)/2+":get()");
			//		    		 this.msgs.clear();
			//		    		 for(int x = 0; x < this.mem.members.size(); x = x + 1) { // get the current value/timestamp
			//		    			 this.mem.members.get(x).tell(new ReadRequest(localseqnum),getSelf()); 
			//		    		 }
			//		    		 this.state=3; // Enter the waiting state
			//		    	 }
			//		    	 else { // the process is faulty
			// 		    		 log.info("P"+this.id+" is faulty"); 
			//		    	 }
			//	           }		
			//		  else 
			//		  if (msg instanceof ReadRequest) { 
			//		 	    	//log.info("P"+this.id+": received a read request "+this.localseqnum+" from "+actorRef); 
			//		 	    	actorRef.tell(new ReadResponse(this.val,((ReadRequest)msg).localseqnum),getSelf()); 
			//		  } 
			else 
				if (msg instanceof QuorumRequest) { 
					//				 	    	if (((QuorumRequest)msg).val.seqnum>this.val.seqnum || 
					//				 	    			((QuorumRequest)msg).val.seqnum==this.val.seqnum &&
					//		 	    					((QuorumRequest)msg).val.pid>this.val.pid) {   
					//				 	    				this.val = ((QuorumRequest)msg).val;
					//				 	    				//log.info("P"+this.id+": updated the local value with value ("+this.val.value+","+this.val.seqnum+","+this.val.pid+")");
					//		 	    					}
					actorRef.tell(new QuorumResponse(((QuorumRequest)msg).localseqnum),getSelf());
				}
			//	     else
			//		 if (msg instanceof ReadResponse && ((ReadResponse)msg).localseqnum==this.localseqnum  
			//			 	     && this.state==3) {
			//			 	    	// the expected response is received
			//				 	    	//log.info("P"+this.id+": received a read response "+this.localseqnum+" from "+actorRef);
			//				 	    	msgs.add(msg);
			//				 	    	// If "enough" responses received:  change the state and invoke a new request
			//				 	    	if (msgs.size()>=this.mem.members.size()/2+1) {
			//				 	    		//log.info("P"+this.id+": received a quorum of read responses "+this.localseqnum);
			//				 	    		this.state=1; // not waiting any longer
			//				 	    		for (int x = 0; x<msgs.size(); x = x+1) { 
			//				 	    			if (((ReadResponse)msgs.get(x)).val.seqnum>this.val.seqnum ||
			//				 	    					((ReadResponse)msgs.get(x)).val.seqnum==this.val.seqnum &&
			//				 	    					((ReadResponse)msgs.get(x)).val.pid>this.val.pid) {
			//				 	    				this.val = ((ReadResponse)msgs.get(x)).val;
			//				 	    			}
			//				 	    		}
			//				 	    		//log.info("P"+this.id+": new (value,timestamp) = ["+this.val.value+",("+this.val.seqnum+","+this.val.pid+")]");
			//				 	    		
			//				 	    		this.localseqnum++;
			//				 	    		
			//				 	    		this.state=3; //the waiting state
			//				 	    		this.msgs.clear();
			//				 	    		QuorumRequest req;
			////				 	    		if (activeop==2) {
			////						    		    // Quorum a new value (process identifier) with the incremented seq number 
			////						    		    req = new QuorumRequest(new StampedValue(this.localseqnum*this.id/2,this.val.seqnum+1,this.id), this.localseqnum); 
			////				 	    		} else {
			////				 	    			    // Quorum the current(value,timestamp)
			////				 	    				req = new QuorumRequest(this.val,this.localseqnum);
			////				 	    		}
			//				 	    		req = new QuorumRequest(this.localseqnum);
			//				 	    		for(int x = 0; x < this.mem.members.size(); x = x + 1) {
			//				 	    			this.mem.members.get(x).tell(req,getSelf()); // send the Quorum request
			//				 	    		}
			//				 	    	}
			//				 	    	
			//		 }
				else
					if (msg instanceof QuorumResponse && ((QuorumResponse)msg).localseqnum==this.localseqnum  
					&& this.state==3) { 
						// the expected Quorum response is received
						//log.info("P"+this.id+": received a Quorum response "+this.localseqnum+" from "+actorRef);
						msgs.add(msg);
						// If "enough" responses received:  change the state and complete the put operation
						if (msgs.size()>=this.mem.members.size()/2+1) {

							//log.info("P"+this.id+": received a quorum of Quorum responses "+this.localseqnum/2);
							this.state=1;	// not waiting any longer
							log.info("P"+this.id+": completes operation "+this.localseqnum/2+": put");

							log.info("DONE " + this.id + " complete request number " + localseqnum);
							//msgs.clear();
							//getSelf().tell(new State(1),ActorRef.noSender()); // invoke a new operation
							

						}

					}
					else
						unhandled(msg);
		}
	}



}

