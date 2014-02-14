package edu.cmu.ds.messagepasser;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import edu.cmu.ds.messagepasser.clock.ClockService;
import edu.cmu.ds.messagepasser.clock.LogicalClock;
import edu.cmu.ds.messagepasser.clock.VectorClock;
import edu.cmu.ds.messagepasser.model.Message;
import edu.cmu.ds.messagepasser.model.MutualExclusionCommand;
import edu.cmu.ds.messagepasser.model.MutualExclusionState;
import edu.cmu.ds.messagepasser.model.Node;
import edu.cmu.ds.messagepasser.model.Rule;
import edu.cmu.ds.messagepasser.model.TimeStampedMessage;

public class MessagePasser {
	public static String commandPrompt = ">: ";
	private String configurationFileName;
	private String localName;
	private AtomicInteger sequenceNumber = new AtomicInteger(0);
	private ConcurrentLinkedQueue<TimeStampedMessage> receiveBuffer = new ConcurrentLinkedQueue<TimeStampedMessage>();
	private ConcurrentLinkedQueue<TimeStampedMessage> receiveDelayedBuffer = new ConcurrentLinkedQueue<TimeStampedMessage>();
	private ConcurrentLinkedQueue<TimeStampedMessage> sendDelayedBuffer = new ConcurrentLinkedQueue<TimeStampedMessage>();
	private LinkedList<TimeStampedMessage> holdBackQueue = new LinkedList<TimeStampedMessage>();
	private HashSet<String> receivedMulticast = new HashSet<String>();
	private List<Rule> receiveRuleList;
	private List<Rule> sendRuleList;
	private List<Node> peerNodeList;
	private List<Node> allNodeList;
	private ServerSocket listenerSocket;
	private Map<String, Socket> clientSocketPool = new HashMap<String, Socket>();
	private Map<String, ObjectOutputStream> clientOutputPool = new HashMap<String, ObjectOutputStream>();
	private boolean willTerminate = false;
	private boolean useLogicalClock;
	private Node localNode;
	private int localNodeIndex;
	private String loggerIp = null;
	private int loggerPort;
	private ClockService clockService = null;
	private TreeMap<String, VectorClock> clockServiceGroups = new TreeMap<String, VectorClock>();
	private TreeMap<String, ArrayList<String>> groupMembers = null;
	private int multicastSequenceNumber = 0; // First sequence number is 1

	/*
	 * Maekawa's algorithm for mutual exclusion
	 */
	private int messagesReceivedCount;
	private int messagesSentCount;
	private MutualExclusionState meState = MutualExclusionState.RELEASED;
	private boolean meVoted = false;
	private Queue<TimeStampedMessage> requestQueue = new PriorityQueue<TimeStampedMessage>();
	private Set<String> nodeRepliedSet = new HashSet<String>();

	public MessagePasser(String configurationFilename, String localName, boolean useLogicalClock)
			throws FileNotFoundException {
		this.configurationFileName = configurationFilename;
		this.localName = localName;
		this.useLogicalClock = useLogicalClock;

		ConfigFileParser parser;
		parser = new ConfigFileParser(this.configurationFileName, this.localName);
		this.peerNodeList = parser.getPeerNodes();
		this.allNodeList = parser.getAllNodes();
		this.receiveRuleList = parser.getReceiveRules();
		this.sendRuleList = parser.getSendRules();
		this.loggerIp = parser.getLoggerIp();
		this.loggerPort = parser.getLoggerPort();
		this.localNode = parser.getLocalNode();
		this.groupMembers = parser.getGroupMembers();
		this.localNodeIndex = parser.getLocalNodeIndex();

		if (this.useLogicalClock) {
			clockService = new LogicalClock();
		} else {
			clockService = new VectorClock(allNodeList.size(), localNodeIndex);
			Iterator<String> iter = groupMembers.keySet().iterator();
			while (iter.hasNext()) {
				clockServiceGroups.put(iter.next(), new VectorClock(allNodeList.size(), localNodeIndex));
			}
		}
		try {
			this.listenerSocket = new ServerSocket(this.localNode.getPort());
			startListenerThread(); // setUp the initial connection
			startMessageReceiverThread(); // create receive
		} catch (IOException e) {
			e.printStackTrace();
		}
		printInfo();
	}

	/**
	 * Print all MessagePasser's information
	 */
	public void printInfo() {
		if (useLogicalClock)
			System.out.println("<Logical clock>");
		else
			System.out.println("<Vector clock>");
		System.out.println("Name = " + localName);
		System.out.println("Node index = " + localNodeIndex);
		System.out.println("Total nodes = " + allNodeList.size());

		// List all groups and their members
		Iterator<Entry<String, ArrayList<String>>> iter = groupMembers.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<String, ArrayList<String>> group = iter.next();
			System.out.print(group.getKey() + ": " + group.getValue().toString());
			if (group.getValue().contains(localName)) {
				// Indicate a group that contains this node
				System.out.println(" <==");
			} else {
				System.out.println();
			}
		}
	}

	public void printMutualExclusionStatus() {
		System.out.println("# messages sent = " + messagesSentCount);
		System.out.println("# messages received = " + messagesReceivedCount);
		System.out.println("state = " + meState);
		System.out.println("voted = " + meVoted);
	}

	/**
	 * Print current time stamp
	 */
	public void printTimeStamp() {
		System.out.println(localName + " main time stamp: " + clockService.getTimeStamp());
		Iterator<Entry<String, VectorClock>> iter = clockServiceGroups.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<String, VectorClock> entry = iter.next();
			System.out.println("\t" + entry.getKey() + ": " + entry.getValue());
		}
	}

	public Map<String, ArrayList<String>> getGroupMembers() {
		return groupMembers;
	}

	public ArrayList<String> getGroupMembers(String groupName) {
		return groupMembers.get(groupName);
	}

	/**
	 * Increment then return multicast sequence number
	 * 
	 * @return
	 */
	public Integer getIncMulticastSequenceNumber() {
		return ++multicastSequenceNumber;
	}

	public boolean isUsingLogicalClock() {
		return useLogicalClock;
	}

	/**
	 * Log a message by sending it to the logger, without incrementing time
	 * stamp
	 * 
	 * @param message
	 * @throws IOException
	 */
	public void log(Message message) throws IOException {
		Socket socket = null;
		try {
			socket = new Socket(loggerIp, loggerPort);
		} catch (ConnectException e) {
			System.out.println("Couldn't connect to Logger server. Log info lost");
			return;
		}
		ObjectOutputStream ot = new ObjectOutputStream(socket.getOutputStream());
		ot.writeObject(message);
		ot.flush();
		ot.close();
		socket.close();
	}

	/**
	 * Increment local Timestamp and send a message only to the logger
	 * 
	 * @param message
	 * @throws IOException
	 */
	public void mark(TimeStampedMessage message) throws IOException {
		message.setTimeStamp(clockService.incrementAndGetTimeStamp());
		System.out.println("Current " + localName + "'s time stamp: " + clockService.getTimeStamp());
		Socket socket = null;
		try {
			socket = new Socket(loggerIp, loggerPort);
		} catch (ConnectException e) {
			System.out.println("Couldn't connect to logger. Log info was not sent.");
			return;
		}
		ObjectOutputStream ot = new ObjectOutputStream(socket.getOutputStream());
		ot.writeObject(message);
		ot.flush();
		ot.close();
		socket.close();
	}

	/**
	 * Multicast a message to multiple recipients
	 * 
	 * @param destinationNodeNames
	 * @param message
	 * @param includeSelf
	 *            False if don't need to multicast to itself
	 * @throws IOException
	 */
	public void multicast(String groupName, TimeStampedMessage message, boolean includeSelf) {
		if (!(clockService instanceof VectorClock)) {
			System.out.println("Please use vector clock to use multicast feature.");
			return;
		}
		List<String> destinationNodeNames = groupMembers.get(groupName);
		VectorClock groupClock = clockServiceGroups.get(groupName);

		// Increment sequence number
		sequenceNumber.incrementAndGet();
		if (includeSelf) {
			// Increment timestamp
			groupClock.incrementAndGetTimeStamp();
//			printTimeStamp();
		}

		/*
		 * Multicast to the target group by sequentially send messages to
		 * everyone in the list (with same message sequence number)
		 */
		// CO-deliver itself
		if (includeSelf) {
			TimeStampedMessage newMessage = new TimeStampedMessage(message);
			newMessage.setKind("multicast");
			newMessage.setSource(localName);
			newMessage.setSequenceNumber(sequenceNumber.get());
			newMessage.setDestination(localName);
			newMessage.setTimeStamp(groupClock.getTimeStamp());
			handleReceiveMulticastMessage(newMessage);
		}
		// Multicast to other nodes than itself
		for (String nodeName : destinationNodeNames) {
			if (!nodeName.equals(localName)) {
				TimeStampedMessage newMessage = new TimeStampedMessage(message);
				newMessage.setKind("multicast");
				newMessage.setSource(localName);
				newMessage.setSequenceNumber(sequenceNumber.get());
				newMessage.setDestination(nodeName);
				newMessage.setTimeStamp(groupClock.getTimeStamp());
				send(newMessage, getNodeIndex(nodeName), true);
			}
		}
		// Print a command line if it is called from CO-multicast
		if (!includeSelf) {
			System.out.print(commandPrompt);
		}
	}

	/**
	 * Send a message to destination
	 * 
	 * @param message
	 *            Message to send
	 * @param targetNodeIndex
	 *            Index of the target node in peerNodes list
	 * @param isMulticastMessage
	 *            True if this is called from multicast() so that it won't
	 *            increment the global sequence number
	 */

	public void send(TimeStampedMessage message, int targetNodeIndex, boolean isMulticastMessage) {
		/*
		 * Increment timestamp only if the command is "send"
		 */
		if (!isMulticastMessage) {
			message.setTimeStamp(clockService.incrementAndGetTimeStamp());
//			printTimeStamp();
		}

		// Get a connection
		ObjectOutputStream ot;
		Socket socket;
		try {
			if (clientOutputPool.containsKey(message.getDestination())) {
				ot = clientOutputPool.get(message.getDestination());
			} else {
				socket = new Socket(peerNodeList.get(targetNodeIndex).getIp(), peerNodeList.get(targetNodeIndex)
						.getPort().intValue());
				ot = new ObjectOutputStream(socket.getOutputStream());
				clientSocketPool.put(message.getDestination(), socket);
				clientOutputPool.put(message.getDestination(), ot);
				System.out.println("Connected to " + message.getDestination());
			}
		} catch (Exception e) {
			System.out.println("Couldn't connect to " + message.getDestination() + " | " + e);
			return;
		}

		// If this is an ordinary message, increase and get sequence number
		// Then assign it to the message
		if (!isMulticastMessage) {
			message.setSource(localName);
			message.setSequenceNumber(sequenceNumber.addAndGet(1));
		}

		// Apply rule
		Rule matchedRule = checkSendRule(message);
		boolean willDuplicate = false;
		if (matchedRule != null) {
			String action = new String(matchedRule.getAction());
			if (action.equals("drop")) {
				/*
				 * Drop: ignore this message and leave
				 */
				System.out.println("Message dropped at the sender");
				return;
			} else if (action.equals("duplicate")) {
				/*
				 * Duplicate: will duplicate this message and then send all
				 * delayed messages
				 */
				System.out.println("Message duplicated at the sender");
				willDuplicate = true;
			} else if (action.equals("delay")) {
				/*
				 * Delay: defer this message and leave
				 */
				System.out.println("Message delayed at the sender");
				sendDelayedBuffer.add(new TimeStampedMessage(message));
				return;
			}
		}

		/*
		 * Send the message and its duplicate if needed
		 */
		try {
			ot.writeObject(message);
			ot.flush();
			messagesSentCount++;
			if (willDuplicate) {
				TimeStampedMessage newMessage = new TimeStampedMessage(message);
				newMessage.setIsDuplicate(true);
				ot.writeObject(newMessage);
				ot.flush();
				messagesSentCount++;
			}
		} catch (Exception e) {
			// Remove socket and outputStream from pools if they are broken
			clientSocketPool.remove(message.getDestination());
			clientOutputPool.remove(message.getDestination());
			System.out.println("Couldn't send a message to " + message.getDestination() + " | " + e);
		}

		/*
		 * Send the rest of delayed messages if there are any
		 */
		while (!sendDelayedBuffer.isEmpty()) {
			TimeStampedMessage delayedMessage = new TimeStampedMessage(sendDelayedBuffer.poll());
			String destinationIp = null;
			int destinationPort = 0;
			for (Node node : peerNodeList) {
				if (node.getName().equals(delayedMessage.getDestination())) {
					destinationIp = node.getIp();
					destinationPort = node.getPort();
					break;
				}
			}
			if (destinationIp == null) {
				System.out.println("Invalid destination");
				return;
			}
			try {
				if (clientOutputPool.containsKey(delayedMessage.getDestination())) {
					ot = clientOutputPool.get(delayedMessage.getDestination());
				} else {
					socket = new Socket(destinationIp, destinationPort);
					ot = new ObjectOutputStream(socket.getOutputStream());
					clientSocketPool.put(delayedMessage.getDestination(), socket);
					clientOutputPool.put(delayedMessage.getDestination(), ot);
					System.out.println("Connect to " + delayedMessage.getDestination() + " is established");
				}
				ot.writeObject(delayedMessage);
				ot.flush();
				messagesSentCount++;
			} catch (Exception e) {
				System.out.println("Couldn't send a delayed message to " + delayedMessage.getDestination() + " | " + e);
				return;
			}
		}
	}

	/**
	 * Mutual exclusion: Request to enter critical section
	 */
	public void request() {
		switch (meState) {
		case WANTED:
			System.out.println("Error: You have another pending request.");
			return;
		case HELD:
			System.out.println("Error: You are holding the critical section!");
			return;
		case RELEASED:
			meState = MutualExclusionState.WANTED;
			nodeRepliedSet.clear();
			printMutualExclusionStatus();
			TimeStampedMessage requestMessage = new TimeStampedMessage();
			requestMessage.setSource(localName);
			requestMessage.setMeCommand(MutualExclusionCommand.REQUEST);
			requestMessage.setMulticastMessageBody(localNode.getMemberOf().get(0), getIncMulticastSequenceNumber());
			multicast(localNode.getMemberOf().get(0), requestMessage, true);
		}
	}

	/**
	 * Mutual exclusion: Release
	 */
	public void release() {
		switch (meState) {
		case RELEASED:
		case WANTED:
			System.out.println("Error: You are not holding the critical section.");
			return;
		case HELD:
			meState = MutualExclusionState.RELEASED;
			TimeStampedMessage releaseMessage = new TimeStampedMessage();
			releaseMessage.setSource(localName);
			releaseMessage.setMeCommand(MutualExclusionCommand.RELEASE);
			releaseMessage.setMulticastMessageBody(localNode.getMemberOf().get(0), getIncMulticastSequenceNumber());
			multicast(localNode.getMemberOf().get(0), releaseMessage, true);
		}
	}

	/**
	 * Deliver a message from the receiveBuffer to the screen
	 */
	private void receive() {
		if (!receiveBuffer.isEmpty()) {
			messagesReceivedCount++;
			TimeStampedMessage message = receiveBuffer.poll();
			System.out.println("\n\nDelivered message from " + message.getSource());
			System.out.println(message);

			if (message.getMeCommand() != null) {
				switch (message.getMeCommand()) {
				case REQUEST:
					/*
					 * Received REQUEST
					 */
					if (meState == MutualExclusionState.HELD || meVoted) {
						requestQueue.add(message);
					} else {
						// Send reply to Pi
						String requester = message.getMulticasterName();
						if (requester.equals(localName)) {
							// Pi is self
							TimeStampedMessage selfReplyMessage = new TimeStampedMessage(message);
							selfReplyMessage.setMeCommand(MutualExclusionCommand.REPLY);
							receiveBuffer.add(selfReplyMessage);
						} else {
							// Pi is other
							TimeStampedMessage replyMessage = new TimeStampedMessage(message.getMulticasterName(), "REPLY", "REPLY");
							replyMessage.setMeCommand(MutualExclusionCommand.REPLY);
							send(replyMessage, getNodeIndex(message.getMulticasterName()), false);
						}
						meVoted = true;
					}
					break;
				case RELEASE:
					/*
					 * Received RELEASE
					 */
					if (!requestQueue.isEmpty()) {
						TimeStampedMessage requestMessage = requestQueue.poll();
						String requester = requestMessage.getMulticasterName();
						// Send reply to Pk
						if (requester.equals(localName)) {
							// Pk is self
							TimeStampedMessage selfReplyMessage = new TimeStampedMessage(message);
							selfReplyMessage.setMeCommand(MutualExclusionCommand.REPLY);
							receiveBuffer.add(selfReplyMessage);
						} else {
							// Pk is other
							TimeStampedMessage replyMessage = new TimeStampedMessage(requester, "REPLY", "REPLY");
							replyMessage.setMeCommand(MutualExclusionCommand.REPLY);
							send(replyMessage, getNodeIndex(requester), false);
						}
						meVoted = true;
					} else {
						meVoted = false;
					}
					break;
				case REPLY:
					/*
					 * Received REPLY
					 */
					nodeRepliedSet.add(message.getSource());
					if (nodeRepliedSet.size() == groupMembers.get(localNode.getMemberOf().get(0)).size()) {
						meState = MutualExclusionState.HELD;
						printMutualExclusionStatus();
					}
				}
			}

			System.out.print(commandPrompt);
		}
	}

	/**
	 * Get an index number of a node with a specific name
	 * 
	 * @param nodeName
	 *            A name of the node
	 * @return Index of the node. Null if the node with that name doesn't exist.
	 */
	public Integer getNodeIndex(String nodeName) {
		for (int i = 0; i < peerNodeList.size(); i++) {
			Node node = peerNodeList.get(i);
			if (node.getName().equals(nodeName)) {
				return i;
			}
		}
		return null;
	}

	/**
	 * Get a process index of a node with a specific name
	 * 
	 * @param nodeName
	 * @return
	 */
	public Integer getProcessIndex(String nodeName) {
		for (int i = 0; i < allNodeList.size(); i++) {
			Node node = allNodeList.get(i);
			if (node.getName().equals(nodeName)) {
				return i;
			}
		}
		return null;
	}

	/**
	 * Start a thread that monitors for incoming data from a MessagePasser
	 * Sophisticated algorithms are around here in order to decide which
	 * messages to deliver
	 * 
	 * @param socket
	 * @throws IOException
	 */
	private void startClientThread(final Socket socket) throws IOException {
		new Thread(new Runnable() {
			public void run() {
				try {
					ObjectInputStream is = new ObjectInputStream(socket.getInputStream());
					boolean mustDuplicate = false;
					while (true) {
						TimeStampedMessage message = (TimeStampedMessage) is.readObject();
						if (message == null)
							continue;

						Rule rule = checkReceiveRule(message);
						if (rule != null) {
							String action = new String(rule.getAction());
							if (action.equals("drop")) {
								/*
								 * Drop: drop the message and leave
								 */
								System.out.println("Message dropped at the receiver");
								System.out.print(commandPrompt);
								continue;
							}
							if (action.equals("duplicate")) {
								/*
								 * Duplicate: will duplicate the received
								 * message and deliver all delayed received
								 * messages
								 */
								mustDuplicate = true;
								System.out.println("Message duplicated at the receiver");
								System.out.print(commandPrompt);
							}
							if (action.equals("delay")) {
								/*
								 * Delay: put this in receive buffer then leave
								 */
								System.out.println("Message delayed at the receiver");
								System.out.print(commandPrompt);
								receiveDelayedBuffer.add(new TimeStampedMessage(message));
								continue;
							}
						}

						if (message.getKind().equals("multicast")) {
							handleReceiveMulticastMessage(message);
						} else {
							handleReceiveNormalMessage(message, mustDuplicate);
						}
					}
				} catch (Exception e) {
					return;
				} finally {
					try {
						socket.close();
					} catch (IOException e) {
					}
				}
			}
		}, "clientThread " + this.hashCode()).start();
	}

	/**
	 * Handle multicast message
	 * 
	 * Check whether or not the node has ever gotten the message. If not,
	 * remember and deliver it. If it is not the sender of this message,
	 * multicast it to the other group members.
	 * 
	 * @param receivedMessage
	 */
	private synchronized void handleReceiveMulticastMessage(TimeStampedMessage receivedMessage) {
		String messageBody = (String) receivedMessage.getData();
		// {R-deliver} Check whether it has received this message
		if (receivedMulticast.contains(messageBody))
			return;
		receivedMulticast.add(messageBody);
		String multicaster = receivedMessage.getMulticasterName();
		String groupName = receivedMessage.getMulticastGroupName();
		VectorClock groupClock = clockServiceGroups.get(groupName);
		System.out
				.println("\nReceived a multicast by {" + multicaster + "} from {" + receivedMessage.getSource() + "}");
		// {R-deliver} Multicast it if current node is not its sender
		if (receivedMessage.getSource().equals(localName)) {
			receiveBuffer.add(receivedMessage);
			return;
		}
		System.out.println("Will multicast it to " + groupName + " soon");
		// {CO-deliver} Put it in the hold-back queue
		holdBackQueue.add(receivedMessage);
		// Help make sure that there are no messages that satisfy delivery
		// condition being leftover in the queue
		LinkedList<TimeStampedMessage> deliveredList = new LinkedList<TimeStampedMessage>();
		int lastDeliveredListSize;
		do {
			lastDeliveredListSize = deliveredList.size();
			// Look for messages that satisfy CO-delivery conditions
			@SuppressWarnings("unchecked")
			ArrayList<Integer> localTimeStamp = (ArrayList<Integer>) groupClock.getTimeStamp();
			for (int i = 0; i < holdBackQueue.size(); i++) {
				// Get only message that hasn't been processed yet
				TimeStampedMessage messageInQueue = holdBackQueue.get(i);
				if (deliveredList.contains(messageInQueue))
					continue;
				// Check whether or not message satisfies delivery condition
				@SuppressWarnings("unchecked")
				ArrayList<Integer> messageTimeStamp = (ArrayList<Integer>) messageInQueue.getTimeStamp();
				int j = getProcessIndex(messageInQueue.getMulticasterName());
				boolean mustDeliver = true;
				// Check Vj[j] == Vi[j] + 1
				if (messageTimeStamp.get(j).equals(localTimeStamp.get(j) + 1)) {
					// Check Vj[k] <= Vi[k] and k != j
					for (int k = 0; k < messageTimeStamp.size(); k++) {
						if (k == j)
							continue;
						if (messageTimeStamp.get(k).compareTo(localTimeStamp.get(k)) > 0) {
							mustDeliver = false;
							break;
						}
					}
				} else
					mustDeliver = false;
				if (mustDeliver) {
					// Remember it to be removed from the queue later
					deliveredList.add(messageInQueue);
					// Deliver this message
					receiveBuffer.add(messageInQueue);
					// Vi[j] := Vi[j] + 1
					groupClock.incTimeStamp(j);
					// {R-deliver} Then, multicast it
					System.out.println("Multicast the delivered message to " + groupName);
					multicast(messageInQueue.getMulticastGroupName(), messageInQueue, false);
				}
			}
			// If there are some messages that delivered this round, there might
			// be more messages in holdBackQueue that should deliver as well.
			// So, we need to check it again
		} while (deliveredList.size() > lastDeliveredListSize);

		// Remove delivered message from the hold-back queue
		for (TimeStampedMessage toRemoveMessage : deliveredList) {
			holdBackQueue.remove(toRemoveMessage);
		}

		// Process messages in receiveDelayedBuffer
		while (!receiveDelayedBuffer.isEmpty()) {
			handleReceiveMulticastMessage(receiveDelayedBuffer.poll());
		}

		System.out.print(commandPrompt);
	}

	/**
	 * Handle normal message
	 * 
	 * (a) Deliver the message (b) Make a duplicate if needed (c) Deliver all
	 * delayed messages
	 * 
	 * @param message
	 */
	private synchronized void handleReceiveNormalMessage(TimeStampedMessage message, boolean mustDuplicate) {
		// Deliver current message
		receiveBuffer.add(message);
		// Increment timestamp
		clockService.updateTime(message.getTimeStamp());
//		printTimeStamp();
		// Duplicate the message and deliver it
		if (mustDuplicate) {
			TimeStampedMessage duplicateMessage = new TimeStampedMessage(message);
			duplicateMessage.setIsDuplicate(true);
			receiveBuffer.add(duplicateMessage);
			// Increment timestamp
			clockService.updateTime(message.getTimeStamp());
//			printTimeStamp();
		}
		// Deliver delayed messages
		while (!receiveDelayedBuffer.isEmpty()) {
			receiveBuffer.add(receiveDelayedBuffer.poll());
			// Increment timestamp
			clockService.updateTime(message.getTimeStamp());
//			printTimeStamp();
		}
		System.out.print(commandPrompt);
	}

	/**
	 * Start a thread that keeps listening to incoming connections from
	 * MessagePassers
	 */
	private void startListenerThread() {
		new Thread(new Runnable() {
			public void run() {
				System.out.println("Local server is listening on port " + listenerSocket.getLocalPort());

				try {
					Socket socket = new Socket(localNode.getIp(), localNode.getPort());
					ObjectOutputStream ot = new ObjectOutputStream(socket.getOutputStream());
					clientSocketPool.put(localName, socket);
					clientOutputPool.put(localName, ot);
				} catch (UnknownHostException e1) {
					e1.printStackTrace();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				try {
					while (true) {
						Socket socket = listenerSocket.accept();
						startClientThread(socket);
					}
				} catch (IOException e) {
				} finally {
					try {
						listenerSocket.close();
					} catch (Exception e) {
					}
				}
			}
		}, "listener").start();
	}

	/**
	 * Start a thread that monitors receiveBuffer and print messages in it
	 * 
	 * @throws IOException
	 */
	private void startMessageReceiverThread() throws IOException {
		new Thread(new Runnable() {
			public void run() {
				try {
					while (!willTerminate) {
						receive();
						Thread.sleep(100);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, "messageReceiver").start();
	}

	/**
	 * Check whether the sending message conforms with a send rule
	 * 
	 * @param message
	 *            Sending message
	 * @return A rule to apply
	 */
	private Rule checkSendRule(Message message) {
		try {
			ConfigFileParser p = new ConfigFileParser(configurationFileName, localName);
			sendRuleList = p.getSendRules();
			for (int i = 0; i < sendRuleList.size(); i++) {
				if (sendRuleList.get(i).matches(message))
					return sendRuleList.get(i);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Check whether the receiving message conforms with a receive rule
	 * 
	 * @param message
	 *            Receiving message
	 * @return A rule to apply
	 */
	private Rule checkReceiveRule(Message message) {
		try {
			ConfigFileParser p = new ConfigFileParser(configurationFileName, localName);
			receiveRuleList = p.getReceiveRules();
			for (int i = 0; i < receiveRuleList.size(); i++) {
				if (receiveRuleList.get(i).matches(message))
					return receiveRuleList.get(i);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		return null;
	}

}
