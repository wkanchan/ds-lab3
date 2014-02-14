package edu.cmu.ds.messagepasser.model;

import java.util.ArrayList;

public class TimeStampedMessage extends Message implements Comparable<TimeStampedMessage> {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private Object timeStamp = null;
	private static final int MULTICAST_MSG_MULTICASTER_NAME_INDEX = 2;
	private static final int MULTICAST_MSG_GROUP_NAME_INDEX = 4;
	private static final int MULTICAST_MSG_SEQUENCE_NUMBER_INDEX = 6;
	private MutualExclusionCommand meCommand = null;

	public TimeStampedMessage() {
		super();
	}

	public TimeStampedMessage(TimeStampedMessage target) {
		super((Message) target);
		this.timeStamp = target.timeStamp;
		this.meCommand = target.meCommand;
	}

	public TimeStampedMessage(String destination, String kind, Object body) {
		super(destination, kind, body);
	}

	public Object getTimeStamp() {
		return timeStamp;
	}

	public void setTimeStamp(Object timeStamp) {
		this.timeStamp = timeStamp;
	}

	@Override
	public String toString() {
		return "TimeStampedMessage[" + "\n\ttimeStamp = " + timeStamp + "\n\tsource = " + source + "\n\tdestination = "
				+ destination + "\n\tsequenceNumber = " + sequenceNumber + "\n\tisDuplicate = " + isDuplicate
				+ "\n\tkind = " + kind + "\n\tbody = " + (String) data + "\n\tmeCommand = " + meCommand + "\n]";
	}

	public String getMulticasterName() {
		if (!kind.equals("multicast"))
			return null;
		try {
			return ((String) data).split(" ")[MULTICAST_MSG_MULTICASTER_NAME_INDEX];
		} catch (Exception e) {
			return null;
		}
	}

	public String getMulticastGroupName() {
		if (!kind.equals("multicast"))
			return null;
		try {
			return ((String) data).split(" ")[MULTICAST_MSG_GROUP_NAME_INDEX];
		} catch (Exception e) {
			return null;
		}
	}

	public String getMulticastSequenceNumber() {
		if (!kind.equals("multicast"))
			return null;
		try {
			return ((String) data).split(" ")[MULTICAST_MSG_SEQUENCE_NUMBER_INDEX];
		} catch (Exception e) {
			return null;
		}
	}

	public void setMulticastMessageBody(String group, int sequenceNumber) {
		data = "Multicast from " + source + " to " + group + " MulticastSequenceNumber " + sequenceNumber;
	}

	public MutualExclusionCommand getMeCommand() {
		return meCommand;
	}

	public void setMeCommand(MutualExclusionCommand meCommand) {
		this.meCommand = meCommand;
	}

	@Override
	@SuppressWarnings("unchecked")
	public int compareTo(TimeStampedMessage o) {
		if (this.timeStamp instanceof Integer) {
			Integer thisTimeStamp = (Integer) this.timeStamp;
			Integer anotherTimeStamp = (Integer) o.timeStamp;
			if (thisTimeStamp.compareTo(anotherTimeStamp) == 0)
				return this.source.compareTo(o.source);
			else
				thisTimeStamp.compareTo(anotherTimeStamp);
		} else if (this.timeStamp instanceof ArrayList<?>) {
			ArrayList<Integer> thisTimeStamp = (ArrayList<Integer>) this.timeStamp;
			ArrayList<Integer> anotherTimeStamp = (ArrayList<Integer>) o.timeStamp;
			boolean isLessOrEqual = true;
			boolean isMoreOrEqual = true;
			for (int i = 0; i < thisTimeStamp.size(); i++) {
				if (thisTimeStamp.get(i) > anotherTimeStamp.get(i))
					isLessOrEqual = false;
				if (thisTimeStamp.get(i) < anotherTimeStamp.get(i))
					isMoreOrEqual = false;
			}
			if (isLessOrEqual == isMoreOrEqual)
				return this.source.compareTo(o.source);
			else if (isLessOrEqual)
				return -1;
			else
				return 1;
		} else {
			throw new RuntimeException("This and that time stamp are not the same type!");
		}
		return 0;
	}

}
