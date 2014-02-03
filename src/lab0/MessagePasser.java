package lab0;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.omg.CORBA.portable.InputStream;

public class MessagePasser implements Serializable {
	private static final long serialVersionUID = 1L;
	/**
	 * 
	 */
	public static String configuration_filename;
	public String local_name;
	private AtomicInteger seqNum = new AtomicInteger(-1);
	public static ConcurrentLinkedQueue<Message> ReceiverBuffer = new ConcurrentLinkedQueue<Message>();
	public static ConcurrentLinkedQueue<Message> ReceiverDelayBuffer = new ConcurrentLinkedQueue<Message>();
	public static ConcurrentLinkedQueue<Message> SenderDelayBuffer = new ConcurrentLinkedQueue<Message>();
	public static ArrayList<Rule> ReceiveRuleSet;
	public static ArrayList<Rule> SendRuleSet;
	public static ArrayList<PeerNode> NodeSet;
	public static ServerSocket listener;
	public static Map<String, Socket> map = new HashMap<String, Socket>();
	public static Map<String, ObjectOutputStream> map2 = new HashMap<String, ObjectOutputStream>();
	public static boolean IsEnd = false;
	private boolean isLogical;
	public String log_ip = "79.29.22.3";
	
	private ClockService clockService = null;
	
	public MessagePasser(String configuration_filename, String local_name) {
		this.configuration_filename = configuration_filename;
		this.local_name = local_name;

	}
	
	
	public boolean isLogical() {
		return isLogical;
	}

	public void setLogical(boolean isLogical) {
		this.isLogical = isLogical;
	}
	
	

	public void setClockService(boolean isLogical, int num, int index) {
		if (isLogical == true) {
			clockService = new LogicalClock();
		} else {
			clockService = new VectorClock(num, index);
		}
	}
	
	
	public void send_log(Message message) throws IOException {
		Socket socket = null;
		try {
			socket = new Socket (log_ip, 3333);
		} catch (ConnectException e) {
			System.out.println("Can not connect to Log server. Log info lost");
			return;
		}
		ObjectOutputStream ot = new ObjectOutputStream(socket.getOutputStream());
		ot.writeObject(message);
		ot.flush();
		ot.close();
		socket.close();
	}
	
	
	public void mark(Message message) throws IOException {
		if (isLogical == true) {
			((TimeStampedMessage)message).setTimeStamp(clockService.getIncTimeStamp());
			System.out.println(clockService.getTimeStamp());
		} else {
			
			((TimeStampedMessage)message).setTimeStamp(clockService.getIncTimeStamp());
			@SuppressWarnings("unchecked")
			ArrayList<Integer> tmp = (ArrayList<Integer>)clockService.getTimeStamp();
			
			System.out.print(local_name +": current time stampe is ( ");
			for (int i = 0; i < tmp.size(); i++) {
				System.out.print(tmp.get(i) + " ");
			}
			System.out.println(")");
			
		}
		
		Socket socket = null;
		try {
			socket = new Socket (log_ip, 3333);
		} catch (ConnectException e) {
			System.out.println("Can not connect to Log server. Log info lost");
			return;
		}
		ObjectOutputStream ot = new ObjectOutputStream(socket.getOutputStream());
		ot.writeObject(message);
		ot.flush();
		ot.close();
		socket.close();
		
	}
	
	
	
	public void send(Message message, int index) throws IOException {
		
		if (message instanceof TimeStampedMessage) {
			if (isLogical == true) {
				((TimeStampedMessage)message).setTimeStamp(clockService.getIncTimeStamp());
				System.out.println(clockService.getTimeStamp());
			} else {
				
				((TimeStampedMessage)message).setTimeStamp(clockService.getIncTimeStamp());
				@SuppressWarnings("unchecked")
				ArrayList<Integer> tmp = (ArrayList<Integer>)clockService.getTimeStamp();
				
				System.out.print(local_name +": current time stampe is ( ");
				for (int i = 0; i < tmp.size(); i++) {
					System.out.print(tmp.get(i) + " ");
				}
				System.out.println(")");
				
			}
		}

		ObjectOutputStream ot;
		Socket socket;
		boolean isDupe = false;
		try {
			if (!map2.containsKey(message.get_Dest())) {
				socket = new Socket(NodeSet.get(index).getIp(), NodeSet.get(index).getPort().intValue());
				ObjectOutputStream ot_temp = new ObjectOutputStream(socket.getOutputStream());
				map.put(message.get_Dest(), socket);
				map2.put(message.get_Dest(), ot_temp);
				System.out.println("Connect to " + message.get_Dest() + " is established");
			} 
		} 
		catch (ConnectException e) {
			System.out.println(message.get_Dest() + " is not online!");
			return;
		}
		ot = map2.get(message.get_Dest());

		message.set_Src(local_name);
		message.set_SeqNum(seqNum.addAndGet(1));
		Rule r = check_SendRule(message);
		if (r != null) {
			String action = new String(r.getAction());
			if (action.equals("drop")) {
				System.out.println("Message has been dropped in send side");
				return;
			}
			if (action.equals("duplicate")) {
				
				System.out.println("Message has been duped in send side");
				isDupe = true;

			}
			if (action.equals("delay")) {
				System.out.println("Message has been delayed in send side");
				SenderDelayBuffer.add(message);
				return;
			}
		}
		
		try {
			ot.writeObject(message);
			ot.flush();
			if (isDupe == true) {
				Message dup_message = new Message(message);
				dup_message.set_Dupe(true);
				ot.writeObject(dup_message);
				ot.flush();
				isDupe = false;
			}
			
		} catch (SocketException e) {
			
			map.remove(message.get_Dest());
			map2.remove(message.get_Dest());
			System.out.println(message.get_Dest() + " is not online!");
			
		}
		

		while (!SenderDelayBuffer.isEmpty()) {
			int index_delay = -1;
			ObjectOutputStream ot2;
            Message new_message = new Message(SenderDelayBuffer.poll());
            
            for (int i = 0; i < NodeSet.size(); i++) {
                if (NodeSet.get(i).getName().equals(new_message.get_Dest())) {
                    index_delay = i;
                }
            }
            if (index_delay == -1) {
                System.out
                        .println("Dest is not in the established node list");
                return;
            }
            try {
                if (!map2.containsKey(new_message.get_Dest())) {
                    socket = new Socket(NodeSet.get(index_delay).getIp(), NodeSet.get(index_delay).getPort().intValue());
                    ObjectOutputStream ot_temp = new ObjectOutputStream(socket.getOutputStream());
                    map.put(new_message.get_Dest(), socket);
                    map2.put(new_message.get_Dest(), ot_temp);
                    System.out.println("Connect to " + new_message.get_Dest() + " is established");
                }
            } catch (ConnectException e) {
                System.out.println(new_message.get_Dest() + " is not online!");
                return;
            }
            ot2 = map2.get(new_message.get_Dest());
            ot2.writeObject(new_message);
            ot2.flush();
            
		}
	
	}

	
	public void receive() {

		if (ReceiverBuffer.peek() != null) {
			System.out.println("");
			Message message = ReceiverBuffer.poll();
			if (message instanceof TimeStampedMessage) {
				if (isLogical == true) {
					clockService.updateTime(((TimeStampedMessage)message).getTimeStamp());
					System.out.println(clockService.getTimeStamp());
				} else {
					clockService.updateTime(((TimeStampedMessage)message).getTimeStamp());	
					@SuppressWarnings("unchecked")
					ArrayList<Integer> tmp = (ArrayList<Integer>)clockService.getTimeStamp();	
					System.out.print(local_name +": current time stampe is ( ");
					for (int i = 0; i < tmp.size(); i++) {
						System.out.print(tmp.get(i) + " ");
					}
					System.out.println(")");				
				}
			}
			
			
			System.out.println("Received message from "
					+ message.get_Src());
			System.out.println("Message seqNum is "
					+ message.get_SeqNum());
			System.out.println("Message dup is  "
					+ message.get_Dupe());
			System.out.println("Message kind is "
					+ message.get_Kind());
			System.out.println("Message body is "
					+ (String) (message.get_Data()));
			System.out.print(">: ");
		}
	}

	public static void SetUp() throws Exception {
		System.out.print("Local server is listening on port ");
		System.out.println(listener.getLocalPort());
		ArrayList<Socket> l = new ArrayList<Socket>();
		try {
			while (true) {
				Socket socket = listener.accept();
				l.add(socket);
				CreateListenThread(socket, ReceiverBuffer, ReceiverDelayBuffer);
				
			}
		} catch (SocketException e) {
			
			
			for (int i = 0; i < l.size(); i++) {
				l.get(i).close();
			}
			listener.close();
		} 
	}

	private static void CreateListenThread(final Socket socket,
			final ConcurrentLinkedQueue<Message> ReceiverBuffer,
			final ConcurrentLinkedQueue<Message> ReceiverDelayBuffer)
			throws IOException {
		new Thread(new Runnable() {
			public void run() {
				try {
				    ObjectInputStream is = new ObjectInputStream(socket.getInputStream());
					Message message;
					boolean isDupe = false;
					while (true) {
						message = (Message)is.readObject();
						if (message == null)
							continue;
						Rule r = check_ReceiveRule(message);
						if (r != null) {
							String action = new String(r.getAction());
							if (action.equals("drop")) {
								System.out
										.println("Message has been dropped in receive side");
								System.out.print(">: ");
								continue;
							}
							if (action.equals("duplicate")) {
								
								isDupe = true;
								
								System.out
										.println("Message has been duped in receive side");
								System.out.print(">: ");
								
							}
							if (action.equals("delay")) {
								System.out
										.println("Message has been delayed in receive side");
								System.out.print(">: ");
								ReceiverDelayBuffer.add(message);
								continue;
							}
						}
						 
			
						ReceiverBuffer.add(message);
						if (isDupe == true) {
							Message dup_message = new Message(message);
							dup_message.set_Dupe(true);
							ReceiverBuffer.add(dup_message);
							isDupe = false;
						}
						
						while (!ReceiverDelayBuffer.isEmpty()) {
							ReceiverBuffer.add(ReceiverDelayBuffer.poll());
						}
					
					}
				} catch (Exception e) {
					return;
				} 
			}
		}).start();
	}

	public void CreateThread() throws IOException {
		new Thread(new Runnable() {
			public void run() {
				try {
					SetUp();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		}).start();
	}
	
	
	public void CreateReadThread() throws IOException {
		new Thread(new Runnable() {
			public void run() {
				try {
					
					while (IsEnd == false) {
						receive();
					}
					
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		}).start();
	}
	
	
	
	

	public Rule check_SendRule(Message message) {

		Parse p = new Parse();
		try {
			SendRuleSet = p.parseRules(configuration_filename, "sendRules");
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		for (int i = 0; i < SendRuleSet.size(); i++) {
			if (SendRuleSet.get(i).IsMatch(message))
				return SendRuleSet.get(i);
		}
		return null;
	}

	public static Rule check_ReceiveRule(Message message) {

		Parse p = new Parse();
		try {
			ReceiveRuleSet = p.parseRules(configuration_filename,
					"receiveRules");
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		for (int i = 0; i < ReceiveRuleSet.size(); i++) {
			if (ReceiveRuleSet.get(i).IsMatch(message))
				return ReceiveRuleSet.get(i);
		}
		return null;
	}

	public static void main(String[] args) throws Exception {
		String configuration_filename = new String("");
		String local_name = new String("");
		String command = new String("");
		String message_body = new String("");
		String log_info = new String("");
		String[] send_info;
		int index = -1;
		String ClockService_type = "";
		
		Scanner in = new Scanner(System.in);

		System.out.println("Please enter the configuration_file name");
		System.out.print(">: ");
		configuration_filename = in.next();
		System.out.println("Please enter the local host name");
		System.out.print(">: ");
		local_name = in.next();
		System.out.println("Please choose the ClockService type (logical or vector)");
		System.out.print(">: ");
		ClockService_type = in.next();
	
			
		while (!ClockService_type.equals("logical") && !ClockService_type.equals("vector")) {
			System.out.println("Please choose correct ClockService type(logical or vector)");
			System.out.print(">: ");
			ClockService_type = in.next();
		}
		
		

		
		
		MessagePasser MP = new MessagePasser(configuration_filename, local_name);

		if (ClockService_type.equals("vector")) {
			MP.setLogical(false);
		} else {
			MP.setLogical(true);
		}
		
		
		Parse p = new Parse();
		try {
			p.parseConf(MP.configuration_filename, MP.local_name);
		} catch (FileNotFoundException e) {
			System.out.println("Can not find configuration_file!!");
			System.out.println("Program exit unnormally");
			return;
		}

		MP.NodeSet = p.getPeerNodes();
		MP.ReceiveRuleSet = p
				.parseRules(configuration_filename, "receiveRules");
		MP.SendRuleSet = p.parseRules(configuration_filename, "sendRules");

		if (p.getLocalNode() == null) {
			System.out
					.println("Can not find local host in configuration_file!!");
			System.out.println("Program exit unnormally");
			return;
		}

		System.out.println("Local name is " + p.getLocalNode().getName());
		if (MP.isLogical() == true)
			System.out.println("is Logical is true");
		else 
			System.out.println("is Logical is false");
		
		System.out.println("Total number is " + (NodeSet.size() + 1));
		System.out.println("Index is " + p.getLocalIndex());
		MP.setClockService(MP.isLogical(), (NodeSet.size() + 1), p.getLocalIndex());
		MP.listener = new ServerSocket(p.getLocalNode().getPort().intValue());
		MP.CreateThread(); // setUp the initial connection
		MP.CreateReadThread(); //create receive
		MP.log_ip = p.getLogip();

		
		
		
		try {
			Thread.sleep(20);
		} catch (InterruptedException e) {
		}
		
		
		System.out.println("Please enter 'send' or 'exit' or 'mark'");
		System.out.print(">: ");
		InputStreamReader reader = new InputStreamReader(System.in);
		BufferedReader input = new BufferedReader(reader);
		command = input.readLine();

		while (!command.equals("exit")) {
			if (!(command.equals("send")) && !(command.equals("mark"))) {
				System.out.println("Enter the right command!");
				System.out.print(">: ");
				command = input.readLine();
				continue;
			}
			
			if (command.equals("mark")) {
				Message mark_message = new TimeStampedMessage("logger", "log", "This is a mark");
				mark_message.set_Src(local_name);
				mark_message.set_SeqNum(9999);
				MP.mark(mark_message);
				System.out.println("Please enter 'send' or 'exit' or 'mark'");
				System.out.print(">: ");
				command = input.readLine();
				continue;
			}
			

			if (command.equals("send")) {
				System.out.println("Please specify the message dest and kind");
				System.out.print(">: ");
				send_info = input.readLine().split(" ");
				
				while (send_info.length != 2) {
					System.out
							.println("Please specify the correct dest and kind");
					System.out.print(">: ");
					send_info = input.readLine().split(" ");
				}
				
				System.out.println("Please enter the message body");
				System.out.print(">: ");
				message_body = input.readLine();
				System.out.println("Do you want log this message (y/n)");
				System.out.print(">: ");
				log_info = input.readLine();
				
				while (!log_info.equals("y") && !log_info.equals("n")) {
					System.out.println("Please choose y or n");
					System.out.print(">: ");
					log_info = input.readLine();
				}
			
				
				Message message = new TimeStampedMessage(send_info[0], send_info[1],
						message_body);
				
				
				for (int i = 0; i < MP.NodeSet.size(); i++) {
					if (MP.NodeSet.get(i).getName().equals(message.get_Dest()))
						index = i;
				}
				if (index == -1) {
					System.out
							.println("Dest is not in the established node list");
					try {
						Thread.sleep(20);
					} catch (InterruptedException e) {
					}

					System.out.println("Please enter 'send' or 'exit' or 'mark'");
					System.out.print(">: ");
					command = input.readLine();
					continue;
				}
								
				
				MP.send(message, index);
				if (log_info.equals("y")) {
					MP.send_log(message);
				}
				
				index = -1;

				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
				}

				System.out.println("Please enter 'send' or 'exit' or 'mark'");
				System.out.print(">: ");
				command = input.readLine();
				continue;
			}
		}

		
		MP.listener.close();
		IsEnd = true;
		System.out.println("Program exit normally");
	}



}
