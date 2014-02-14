package edu.cmu.ds.messagepasser.model;

import java.io.Serializable;

public class Message implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	protected String destination;
	protected String kind;
	protected Object data;
	protected String source;
	protected Integer sequenceNumber;
	protected boolean isDuplicate;

	/**
	 * Create an empty message
	 */
	public Message() {
		destination = null;
		kind = null;
		data = null;
		source = null;
		sequenceNumber = null;
		isDuplicate = false;
	}

	/**
	 * Create a duplicate of message
	 * 
	 * @param message
	 */
	public Message(Message message) {
		destination = message.destination;
		kind = message.kind;
		data = message.data;
		source = message.source;
		sequenceNumber = message.sequenceNumber;
		isDuplicate = message.isDuplicate;
	}

	/**
	 * Create a message with destination, kind and data
	 * 
	 * @param destination
	 * @param kind
	 * @param data
	 */
	public Message(String destination, String kind, Object data) {
		this();
		this.destination = destination;
		this.kind = kind;
		this.data = data;
	}

	public String getDestination() {
		return destination;
	}

	public String getKind() {
		return kind;
	}

	public Object getData() {
		return data;
	}

	public String getSource() {
		return source;
	}

	public Integer getSequenceNumber() {
		return sequenceNumber;
	}

	public boolean getIsDuplicate() {
		return isDuplicate;
	}

	public void setDestination(String destination) {
		this.destination = destination;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public void setData(Object data) {
		this.data = data;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public void setSequenceNumber(int sequeceNumber) {
		sequenceNumber = sequeceNumber;
	}

	public void setIsDuplicate(boolean isDuplicate) {
		this.isDuplicate = isDuplicate;
	}

	@Override
	public String toString() {
		return "Message[\n\tsource = " + source + "\n\tdestination = " + destination + "\n\tsequenceNumber = "
				+ sequenceNumber + "\n\tisDuplicate = " + isDuplicate + "\n\tkind = " + kind + "\n\tbody = "
				+ (String) data + "\n]";
	}

}
