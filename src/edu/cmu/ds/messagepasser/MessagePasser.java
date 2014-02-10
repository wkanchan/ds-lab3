package edu.cmu.ds.messagepasser;

import java.util.List;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import edu.cmu.ds.messagepasser.clock.ClockService;
import edu.cmu.ds.messagepasser.clock.LogicalClock;
import edu.cmu.ds.messagepasser.clock.VectorClock;
import edu.cmu.ds.messagepasser.model.Message;
import edu.cmu.ds.messagepasser.model.Node;
import edu.cmu.ds.messagepasser.model.Rule;
import edu.cmu.ds.messagepasser.model.TimeStampedMessage;

public class MessagePasser {
	private static final String DEFAULT_CONFIG_FILENAME = "sample.yaml";
	private static String commandPrompt = ">: ";
	private String configurationFileName;
	private String localName;
	private AtomicInteger sequenceNumber = new AtomicInteger(-1);
	private ConcurrentLinkedQueue<Message> receiveBuffer = new ConcurrentLinkedQueue<Message>();
	private ConcurrentLinkedQueue<Message> receiveDelayedBuffer = new ConcurrentLinkedQueue<Message>();
	private ConcurrentLinkedQueue<Message> sendDelayedBuffer = new ConcurrentLinkedQueue<Message>();
	private ArrayList<String> ReceivedMulticast = new ArrayList<String>();
	private ArrayList<Rule> receiveRuleList;
	private ArrayList<Rule> sendRuleList;
	private ArrayList<Node> peerNodeList;
	private ServerSocket listenerSocket;
	private Map<String, Socket> clientSocketPool = new HashMap<String, Socket>();
	private Map<String, ObjectOutputStream> clientOutputPool = new HashMap<String, ObjectOutputStream>();
	private boolean willTerminate = false;
	private boolean useLogicalClock;
	private int localNodeIndex;
	private int localPort;
	private String localIp = null;
	private String loggerIp = null;
	private int loggerPort;
	private ClockService clockService = null;
	private Map<String, List<String>> groupInfo = null;
	// First multicast message's sequence number will be 0
	private int multicastSequenceNumber = -1;

	public MessagePasser(String inConfigurationFilename, String inLocalName,
			boolean inUseLogicalClock) throws FileNotFoundException {
		this.configurationFileName = inConfigurationFilename;
		this.localName = inLocalName;
		this.useLogicalClock = inUseLogicalClock;

		ConfigFileParser parser;
		parser = new ConfigFileParser(this.configurationFileName, this.localName);
		this.peerNodeList = parser.getPeerNodes();
		this.receiveRuleList = parser.getReceiveRules();
		this.sendRuleList = parser.getSendRules();
		this.loggerIp = parser.getLoggerIp();
		this.loggerPort = parser.getLoggerPort();
		this.localNodeIndex = parser.getLocalNodeIndex();
		this.localPort = parser.getLocalNode().getPort();
		this.localIp = parser.getLocalNode().getIp();
		this.groupInfo = parser.getGroupInfo();

		if (inUseLogicalClock) {
			clockService = new LogicalClock();
		} else {
			clockService = new VectorClock(peerNodeList.size() + 1, localNodeIndex);
		}
		try {
			this.listenerSocket = new ServerSocket(this.localPort);
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
		System.out.println("Local name is " + localName);
		System.out.println("Total number of node is " + (this.peerNodeList.size() + 1));
		System.out.println("Local node index is " + localNodeIndex);
		if (useLogicalClock)
			System.out.println("Using logical clock");
		else
			System.out.println("Using vector clock");
		for (int i = 1; i <= groupInfo.size(); i++)
			System.out.println("Group" + i + " is " + groupInfo.get("Group" + String.valueOf(i)));
	}

	public Map<String, List<String>> getGroupInfo() {
		return groupInfo;
	}

	private Integer incrementAndGetMulticastSequenceNumber() {
		return ++multicastSequenceNumber;
	}

	public boolean isUsingLogicalClock() {
		return useLogicalClock;
	}

	/**
	 * Send a message to the logger but not increment time stamp (for used by
	 * send() and receive() only)
	 * 
	 * @param message
	 * @throws IOException
	 */
	private void sendLog(Message message) throws IOException {
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
	public void mark(Message message) throws IOException {
		if (useLogicalClock == true) {
			((TimeStampedMessage) message).setTimeStamp(clockService.getIncTimeStamp());
			System.out.println(clockService.getTimeStamp());
		} else {

			((TimeStampedMessage) message).setTimeStamp(clockService.getIncTimeStamp());
			@SuppressWarnings("unchecked")
			ArrayList<Integer> tmp = (ArrayList<Integer>) clockService.getTimeStamp();

			System.out.print(localName + ": current time stampe is ( ");
			for (int i = 0; i < tmp.size(); i++) {
				System.out.print(tmp.get(i) + " ");
			}
			System.out.println(")");

		}

		Socket socket = null;
		try {
			socket = new Socket(loggerIp, 3333);
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
	 * Multicast a message to everyone in the list
	 * 
	 * @param destinationNodeNames
	 * @param message
	 * @throws IOException
	 */
	public void multicast(List<String> destinationNodeNames, Message message) throws IOException {
		if (message instanceof TimeStampedMessage) {
			if (useLogicalClock) {
				/*
				 * Multicast logical
				 */
				((TimeStampedMessage) message).setTimeStamp(clockService.getIncTimeStamp());
				System.out.println(clockService.getTimeStamp());
			} else {
				/*
				 * Multicast vector
				 */
				((TimeStampedMessage) message).setTimeStamp(clockService.getIncTimeStamp());
				@SuppressWarnings("unchecked")
				ArrayList<Integer> timeStamp = (ArrayList<Integer>) clockService.getTimeStamp();
				System.out.println(localName + ": current time stampe is " + timeStamp.toString());
			}
		}

		message.setSource(localName);
		message.setSequenceNumber(sequenceNumber.addAndGet(1));

		// Sequentially send messages to everyone in the list
		for (String nodeName : destinationNodeNames) {
			Integer nodeIndex = getNodeIndex(nodeName);
			if (nodeIndex == null) {
				continue;
			}
			message.setDestination(nodeName);
			// The third parameter is "true" to tell send() not to increment
			// sequence number
			send(message, nodeIndex, true);
		}
		// for (int i = 0; i < destinationNodeNames.size(); i++) {
		// Integer targetNodeIndex = getNodeIndex(destinationNodeNames.get(i));
		// message.setDestination(destinationNodeNames.get(i));
		// send(message, targetNodeIndex, true);
		// }

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
	 * @throws IOException
	 */
	public void send(Message message, int targetNodeIndex, boolean isMulticastMessage)
			throws IOException {

		/*
		 * Increment timestamp. Except if the message is multicast, because
		 * multicast() has done it.
		 */
		if (!isMulticastMessage) {
			if (message instanceof TimeStampedMessage) {
				if (useLogicalClock) {
					/*
					 * Send logical
					 */
					((TimeStampedMessage) message).setTimeStamp(clockService.getIncTimeStamp());
					System.out.println(clockService.getTimeStamp());
				} else {
					/*
					 * Send vector
					 */
					((TimeStampedMessage) message).setTimeStamp(clockService.getIncTimeStamp());
					@SuppressWarnings("unchecked")
					ArrayList<Integer> timeStamp = (ArrayList<Integer>) clockService.getTimeStamp();
					System.out
							.print(localName + ": current time stampe is " + timeStamp.toString());
				}
			}
		}

		ObjectOutputStream ot;
		Socket socket;
		try {
			if (!clientOutputPool.containsKey(message.getDestination())) {
				socket = new Socket(peerNodeList.get(targetNodeIndex).getIp(), peerNodeList
						.get(targetNodeIndex).getPort().intValue());
				ObjectOutputStream ot_temp = new ObjectOutputStream(socket.getOutputStream());
				clientSocketPool.put(message.getDestination(), socket);
				clientOutputPool.put(message.getDestination(), ot_temp);
				System.out.println("Connection to " + message.getDestination()
						+ " has been established.");
			}
		} catch (ConnectException e) {
			System.out.println(message.getDestination() + " is offline!");
			return;
		}
		ot = clientOutputPool.get(message.getDestination());

		// If this is not a multicast message, increment global sequence number
		// and set it
		if (!isMulticastMessage) {
			message.setSource(localName);
			message.setSequenceNumber(sequenceNumber.addAndGet(1));
		}

		// Apply rule
		Rule r = checkSendRule(message);
		boolean mustDuplicate = false;
		if (r != null) {
			String action = new String(r.getAction());
			if (action.equals("drop")) {
				/*
				 * Drop: ignore this message and leave
				 */
				System.out.println("Message has been dropped at the sender");
				return;
			}
			if (action.equals("duplicate")) {
				/*
				 * Duplicate: will duplicate this message and then send all
				 * delayed messages
				 */
				System.out.println("Message has been duplicated at the sender");
				mustDuplicate = true;
			}
			if (action.equals("delay")) {
				/*
				 * Delay: defer this message and leave
				 */
				System.out.println("Message has been delayed at the sender");
				sendDelayedBuffer.add(message);
				return;
			}
		}

		/*
		 * Send the message and its duplicate if needed
		 */
		try {
			ot.writeObject(message);
			ot.flush();
			if (mustDuplicate) {
				Message duplicateMessage = new Message(message);
				duplicateMessage.setIsDuplicate(true);
				ot.writeObject(duplicateMessage);
				ot.flush();
			}
		} catch (SocketException e) {
			// If this connection is broken, remove socket and outputStream from
			// pools
			clientSocketPool.remove(message.getDestination());
			clientOutputPool.remove(message.getDestination());
			System.out.println(message.getDestination() + " is offline!");
		}

		/*
		 * Send the rest of delayed messages if there are any
		 */
		while (!sendDelayedBuffer.isEmpty()) {
			ObjectOutputStream ot2;
			Message delayedMessageToSend = new Message(sendDelayedBuffer.poll());

			Integer nodeIndex = null;
			for (int i = 0; i < peerNodeList.size(); i++) {
				if (peerNodeList.get(i).getName().equals(delayedMessageToSend.getDestination())) {
					nodeIndex = i;
				}
			}
			if (nodeIndex == null) {
				System.out.println("Invalid destination");
				return;
			}
			try {
				if (!clientOutputPool.containsKey(delayedMessageToSend.getDestination())) {
					socket = new Socket(peerNodeList.get(nodeIndex).getIp(), peerNodeList
							.get(nodeIndex).getPort().intValue());
					ObjectOutputStream ot3 = new ObjectOutputStream(socket.getOutputStream());
					clientSocketPool.put(delayedMessageToSend.getDestination(), socket);
					clientOutputPool.put(delayedMessageToSend.getDestination(), ot3);
					System.out.println("Connect to " + delayedMessageToSend.getDestination()
							+ " is established");
				}
			} catch (ConnectException e) {
				System.out.println(delayedMessageToSend.getDestination() + " is not online!");
				return;
			}
			ot2 = clientOutputPool.get(delayedMessageToSend.getDestination());
			ot2.writeObject(delayedMessageToSend);
			ot2.flush();
		}
	}

	/**
	 * Get a message from receiveBuffer and print it
	 */
	public void receive() {

		if (receiveBuffer.peek() != null) {
			System.out.println("");
			Message message = receiveBuffer.poll();
			if (message instanceof TimeStampedMessage) {
				if (useLogicalClock == true) {
					clockService.updateTime(((TimeStampedMessage) message).getTimeStamp());
					System.out.println(clockService.getTimeStamp());
				} else {
					clockService.updateTime(((TimeStampedMessage) message).getTimeStamp());
					@SuppressWarnings("unchecked")
					ArrayList<Integer> tmp = (ArrayList<Integer>) clockService.getTimeStamp();
					System.out.print(localName + ": current time stampe is ( ");
					for (int i = 0; i < tmp.size(); i++) {
						System.out.print(tmp.get(i) + " ");
					}
					System.out.println(")");
				}
			}

			System.out.println("Received message from " + message.getSource());
			System.out.println("Message seqNum is " + message.getSequenceNumber());
			System.out.println("Message dup is  " + message.getIsDuplicate());
			System.out.println("Message kind is " + message.getKind());
			System.out.println("Message body is " + (String) (message.getData()));
			System.out.print(commandPrompt);
		}
	}

	/**
	 * Get an index number of a node with a specified name.
	 * 
	 * @param nodeName
	 *            A name of the node
	 * @return Index of the node. Null if the node with that name doesn't exist.
	 */
	public Integer getNodeIndex(String nodeName) {
		for (int i = 0; i < peerNodeList.size(); i++) {
			Node peerNode = peerNodeList.get(i);
			if (peerNode.getName().equals(nodeName)) {
				return i;
			}
		}
		return null;
	}

	/**
	 * Start a thread that monitors for incoming data from a MessagePasser
	 * 
	 * @param socket
	 * @throws IOException
	 */
	private void startClientThread(final Socket socket) throws IOException {
		new Thread(new Runnable() {
			public void run() {
				try {
					ObjectInputStream is = new ObjectInputStream(socket.getInputStream());
					Message message;
					boolean isDuplicate = false;
					while (!willTerminate) {
						message = (Message) is.readObject();
						if (message == null)
							continue;

						Rule rule = checkReceiveRule(message);
						if (rule != null) {
							String action = new String(rule.getAction());
							if (action.equals("drop")) {
								System.out.println("Message has been dropped in receive side");
								System.out.print(commandPrompt);
								continue;
							}
							if (action.equals("duplicate")) {
								isDuplicate = true;
								System.out.println("Message has been duped in receive side");
								System.out.print(commandPrompt);
							}
							if (action.equals("delay")) {
								System.out.println("Message has been delayed in receive side");
								System.out.print(commandPrompt);
								receiveDelayedBuffer.add(message);
								continue;
							}
						}

						// Receive the multicast message
						if (message.getKind().equals("multicast")) {
							if (!ReceivedMulticast.contains((String) message.getData())) {
								ReceivedMulticast.add((String) message.getData());
								if (!(message.getSource().equals(localName))) {
									// To retrieve the GroupName in the
									// Multicast body
									String[] temp = ((String) message.getData()).split(" ");
									String groupName = temp[8];
									System.out.println("I receive Multicast from "
											+ message.getSource());
									System.out.println("I am going to Multicast to the group again");
									multicast(groupInfo.get(groupName), message);
								}
								receiveBuffer.add(message);

							} else {
								// if received just continue
								continue;
							}
						} else {
							// normal message, just to upper application
							receiveBuffer.add(message);
						}

						if (isDuplicate == true) {
							Message dup_message = new Message(message);
							dup_message.setIsDuplicate(true);
							// Receive the multicast message
							// If the dupe message is a multicast one, it must
							// be received before
							// We just drop it
							if (!message.getKind().equals("multicast")) {
								receiveBuffer.add(dup_message);
								isDuplicate = false;
							} else {
								isDuplicate = false;
							}
						}

						while (!receiveDelayedBuffer.isEmpty() && !willTerminate) {
							// It is the delay buffer
							// I think sth should be added here
							// to realize the causal ordering

							receiveBuffer.add(receiveDelayedBuffer.poll());
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
	 * Start a thread that keeps listening to incoming connections from
	 * MessagePassers
	 */
	private void startListenerThread() {
		new Thread(new Runnable() {
			public void run() {
				System.out.println("Local server is listening on port "
						+ listenerSocket.getLocalPort());

				Socket socket_local;
				ObjectOutputStream ot_temp;
				try {
					socket_local = new Socket(localIp, localPort);
					ot_temp = new ObjectOutputStream(socket_local.getOutputStream());
					clientSocketPool.put(localName, socket_local);
					clientOutputPool.put(localName, ot_temp);
				} catch (UnknownHostException e1) {
					e1.printStackTrace();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				try {
					while (!willTerminate) {
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

	public static void main(String[] args) throws Exception {
		InputStreamReader reader = new InputStreamReader(System.in);
		BufferedReader input = new BufferedReader(reader);

		System.out.println("Please enter the local host name");
		System.out.print(commandPrompt);
		String localName = input.readLine();

		String clockType;
		do {
			System.out
					.println("Please choose the clock type between 'l' (logical) or 'v' (vector)");
			System.out.print(commandPrompt);
			clockType = input.readLine();
		} while (!"l".equals(clockType) && !"v".equals(clockType));

		MessagePasser messagePasser;
		while (true) {
			try {
				System.out.println("Please enter the configuration file name (blank for default: "
						+ DEFAULT_CONFIG_FILENAME + ")");
				System.out.print(commandPrompt);
				String configurationFileName = input.readLine();
				if (configurationFileName.length() == 0)
					configurationFileName = DEFAULT_CONFIG_FILENAME;
				// Create a MessagePasser instance and start it!
				messagePasser = new MessagePasser(configurationFileName, localName,
						"l".equals(clockType));
				// Modify command prompt display
				commandPrompt = (messagePasser.isUsingLogicalClock() ? "logical " : "vector ")
						+ localName + commandPrompt;
				break;
			} catch (FileNotFoundException e) {
				System.out.println("Configuration file not found.");
			}
		}

		System.out.println("Please enter 'send' or 'exit' or 'mark' or 'multicast'");
		System.out.print(commandPrompt);
		String command;
		while ((command = input.readLine()) != null) {
			if (command.equals("exit")) {
				/*
				 * Exit
				 */
				break;
			} else if (command.equals("send")) {
				/*
				 * Send
				 */
				// Retrieve message destination and kind
				String[] sendInfo;
				do {
					System.out.println("Please specify the message: <destination> <kind>");
					System.out.print(commandPrompt);
					sendInfo = input.readLine().split(" ");
				} while (sendInfo.length != 2);
				String destination = sendInfo[0];
				String kind = sendInfo[1];

				// Retrieve message body
				System.out.println("Please enter the message body");
				System.out.print(commandPrompt);
				String messageBody = input.readLine();

				// Check if the user wants to log
				String logInfo;
				do {
					System.out.println("Do you want log this message? (y/n)");
					System.out.print(commandPrompt);
					logInfo = input.readLine();
				} while (!logInfo.equals("y") && !logInfo.equals("n"));
				boolean mustLog = (logInfo.toLowerCase().equals("y"));

				// Check destination
				Integer nodeIndex = messagePasser.getNodeIndex(destination);
				if (nodeIndex == null && !destination.equals(localName)) {
					System.out.println("Invalid destination");
				} else {
					// Create and send a time stamped message
					Message message = new TimeStampedMessage(destination, kind, messageBody);

					// if nodeIndex == null, it means send to itself
					// socket has been established at the init of messagePasser
					if (nodeIndex == null)
						nodeIndex = -1;
					messagePasser.send(message, nodeIndex, false);
					if (mustLog) {
						messagePasser.sendLog(message);
					}
				}
			} else if (command.equals("mark")) {
				/*
				 * Mark
				 */
				Message markMessage = new TimeStampedMessage("logger", "log", "This is a mark.");
				markMessage.setSource(localName);
				// We don't care the sequence number.
				markMessage.setSequenceNumber(Integer.MAX_VALUE);
				messagePasser.mark(markMessage);
			} else if (command.equals("multicast")) {
				/*
				 * Multicast
				 */
				String targetMulticastGroupName = null;
				do {
					System.out.println("Please specify the group name");
					System.out.print(commandPrompt);
					targetMulticastGroupName = input.readLine();
				} while (!messagePasser.getGroupInfo().containsKey(targetMulticastGroupName));

				if (!messagePasser.getGroupInfo().get(targetMulticastGroupName).contains(localName)) {
					System.out.println("Couldn't multicast. You are not a member of this group.");
				} else {
					/*
					 * Important: We use this message as a metadata for
					 * multicasting. After split(" "), targetMulticastGroupName
					 * will be at [8]
					 */
					String multicastMessageBody = "This is a multicast sent from " + localName
							+ " to " + targetMulticastGroupName + " MulticastSequenceNumber is "
							+ messagePasser.incrementAndGetMulticastSequenceNumber();
					String logInfo;
					do {
						System.out.println("Do you want log this message? (y/n)");
						System.out.print(commandPrompt);
						logInfo = input.readLine();
					} while (!logInfo.equals("y") && !logInfo.equals("n"));
					boolean mustLog = (logInfo.toLowerCase().equals("y"));
					Message multicastMessage = new TimeStampedMessage(targetMulticastGroupName,
							"multicast", multicastMessageBody);
					messagePasser.multicast(
							messagePasser.getGroupInfo().get(targetMulticastGroupName),
							multicastMessage);
					if (mustLog) {
						multicastMessage.setDestination(targetMulticastGroupName);
						messagePasser.sendLog(multicastMessage);
					}
				}

			}
			System.out.println("Please enter 'send' or 'exit' or 'mark' or 'multicast'");
			System.out.print(commandPrompt);
		}
		input.close();
		System.out.println("Program exited normally");
		System.exit(0);
	}

}
