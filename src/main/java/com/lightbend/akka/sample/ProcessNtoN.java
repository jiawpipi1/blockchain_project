package com.lightbend.akka.sample;

import java.util.ArrayList;

import akka.actor.UntypedAbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;


//#greeter-messages
public class ProcessNtoN extends UntypedAbstractActor {

  private LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);  
    
    //static public Props props() {
    //  return Props.create(Process.class, () -> {
    //			return new Process();
    //  });
    //}

 static public Props props(String name) {
    return Props.create(ProcessNtoN.class, () -> new ProcessNtoN(name));
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
    
 
  private  Members mem;
    
  private final String name;

 
 public ProcessNtoN(String name) {
    this.name = name;
 }
    

	@Override
	public void onReceive(Object msg) throws Exception {
	    if (msg instanceof Members) {
		    this.mem = (Members) msg;
		    for(int x = 0; x <= this.mem.Nmembers-1; x = x + 1) {
			 log.info(this.name+": know member "+Integer.toString(x)); 
			 this.mem.members.get(x).tell("Message to "+this.mem.members.get(x),getSelf()); 
		     }
	    } else
	     if (msg instanceof String) {
	    	 ActorRef actorRef = getSender();
	    	 log.info(this.name+": received new message '"+msg+"' from "+actorRef); 	     
	      }
	     else
			unhandled(msg);
	}


  
  
}

