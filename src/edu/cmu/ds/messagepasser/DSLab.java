package edu.cmu.ds.messagepasser;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;

import edu.cmu.ds.messagepasser.model.TimeStampedMessage;

public class DSLab {

	private static final String DEFAULT_CONFIG_FILENAME = "config.yaml";

	public static void main(String[] args) throws Exception {
		InputStreamReader reader = new InputStreamReader(System.in);
		BufferedReader input = new BufferedReader(reader);

		System.out.println("Please enter a local name");
		System.out.print(MessagePasser.commandPrompt);
		String localName = input.readLine();

		String clockType;
		do {
			System.out.println("Please choose a clock type (l/v)");
			System.out.print(MessagePasser.commandPrompt);
			clockType = input.readLine();
		} while (!"l".equals(clockType) && !"v".equals(clockType));

		MessagePasser messagePasser;
		while (true) {
			try {
				System.out.println("Please enter a configuration file name (" + DEFAULT_CONFIG_FILENAME + ")");
				System.out.print(MessagePasser.commandPrompt);
				String configurationFileName = input.readLine();
				if (configurationFileName.length() == 0)
					configurationFileName = DEFAULT_CONFIG_FILENAME;
				// Create a MessagePasser instance and start it!
				messagePasser = new MessagePasser(configurationFileName, localName, "l".equals(clockType));
				// Modify command prompt display
				MessagePasser.commandPrompt = "\n" + (messagePasser.isUsingLogicalClock() ? "logical " : "vector ")
						+ localName + MessagePasser.commandPrompt;
				break;
			} catch (FileNotFoundException e) {
				System.out.println("Configuration file not found.");
			}
		}

		System.out.println("Please enter a command (send/exit/mark/multicast/time)");
		System.out.print(MessagePasser.commandPrompt);
		String command;
		while ((command = input.readLine()) != null) {
			if (command.equals("exit")) {
				/*
				 * Exit
				 */
				break;
			} else if (command.equals("time")) {
				/*
				 * Time - Print current time stamp
				 */
				messagePasser.printTimeStamp();
			} else if (command.equals("send")) {
				/*
				 * Send
				 */
				// Retrieve message destination and kind
				String[] sendInfo;
				do {
					System.out.println("Please enter message <destination> <kind>");
					System.out.print(MessagePasser.commandPrompt);
					sendInfo = input.readLine().split(" ");
				} while (sendInfo.length != 2);
				String destination = sendInfo[0];
				String kind = sendInfo[1];

				// Retrieve message body
				System.out.println("Please enter message body");
				System.out.print(MessagePasser.commandPrompt);
				String messageBody = input.readLine();

				// Check if the user wants to log
				String logInfo;
				do {
					System.out.println("Do you want log this message? (y/N)");
					System.out.print(MessagePasser.commandPrompt);
					logInfo = input.readLine();
				} while (!logInfo.equals("y") && !logInfo.equals("n") && !logInfo.equals(""));
				boolean mustLog = (logInfo.toLowerCase().equals("y"));

				// Check destination
				Integer nodeIndex = messagePasser.getNodeIndex(destination);
				if (nodeIndex == null && !destination.equals(localName)) {
					System.out.println("Invalid destination");
				} else {
					// Create and send a time stamped message
					TimeStampedMessage message = new TimeStampedMessage(destination, kind, messageBody);

					// if nodeIndex == null, it means send to itself
					// socket has been established at the init of messagePasser
					if (nodeIndex == null)
						nodeIndex = -1;
					messagePasser.send(message, nodeIndex, false);
					if (mustLog) {
						messagePasser.log(message);
					}
				}
			} else if (command.equals("mark")) {
				/*
				 * Mark: send a message only to logger.
				 */
				TimeStampedMessage message = new TimeStampedMessage("logger", "log", "Mark");
				message.setSource(localName);
				message.setSequenceNumber(Integer.MAX_VALUE);
				messagePasser.mark(message);
			} else if (command.equals("multicast")) {
				/*
				 * Multicast
				 */
				String groupName = null;
				do {
					System.out.println("Please specify a group name");
					System.out.print(MessagePasser.commandPrompt);
					groupName = input.readLine();
				} while (!messagePasser.getGroupMembers().containsKey(groupName));

				if (!messagePasser.getGroupMembers().get(groupName).contains(localName)) {
					System.out.println("Couldn't multicast. You are not a member of this group.");
				} else {
					String logInfo;
					do {
						System.out.println("Do you want log this message? (y/N)");
						System.out.print(MessagePasser.commandPrompt);
						logInfo = input.readLine();
					} while (!logInfo.equals("y") && !logInfo.equals("n") && !logInfo.equals(""));

					boolean mustLog = (logInfo.toLowerCase().equals("y"));
					TimeStampedMessage message = new TimeStampedMessage();
					message.setSource(localName);
					message.setMulticastMessageBody(groupName, messagePasser.getIncMulticastSequenceNumber());
					messagePasser.multicast(groupName, message, true);

					if (mustLog) {
						message.setDestination(groupName);
						messagePasser.log(message);
					}
				}
			}
			System.out.println("-------------------");
			System.out.println("Please enter a command (send/exit/mark/multicast/time)");
			System.out.print(MessagePasser.commandPrompt);
		}
		input.close();
		System.out.println("Bye!");
		System.exit(0);
	}

}
