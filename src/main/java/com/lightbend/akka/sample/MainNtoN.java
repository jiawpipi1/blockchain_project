package com.lightbend.akka.sample;

import java.io.IOException;

import com.lightbend.akka.sample.ProcessNtoN.Members;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;


//To print time 
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
//

public class MainNtoN {

 //#printer-messages
  static public class ProcessName {
    public final String name;

    public  ProcessName(String name) {
      this.name = name;
    }
  }
  //#printer-messages
    
    public static void main(String[] args) {
	
	final int N = 3;    
	final ActorSystem system = ActorSystem.create("system");

	Date now = new Date();
	
	 final ArrayList<ActorRef> members = new ArrayList<ActorRef>();
    
    try {
      //#create-actors
 

	for(int x = 0; x <= N-1; x = x + 1) {
	     members.add(x,
		system.actorOf(ProcessNtoN.props("P"+Integer.toString(x)), "P"+Integer.toString(x)));
	}    


       SimpleDateFormat dateFormatter = new SimpleDateFormat("E m/d/y h:m:s.SSS z");
       System.out.println("System birth: "+ dateFormatter.format(now));

       for(int x = 0; x <= N-1; x = x + 1) {
	   members.get(x).tell(new Members(members), ActorRef.noSender());      
	}

      	
	
      //#main-send-messages

      System.out.println(">>> Press ENTER to exit <<<");
      System.in.read();
    } catch (IOException ioe) {
    } finally {
      system.terminate();
    }
  }
}
