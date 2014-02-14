package edu.cmu.ds.messagepasser.model;

import java.util.ArrayList;
import java.util.List;

public class Node {
	private String name;
	private String ip;
	private Integer port;
	private List<String> memberOf = new ArrayList<String>();

	public Node() {
	}

	public Node(String name, String ip, Integer port, List<String> memberOf) {
		this.name = name;
		this.ip = ip;
		this.port = port;
		this.memberOf = memberOf;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public Integer getPort() {
		return port;
	}

	public void setPort(Integer port) {
		this.port = port;
	}
	
	public void setMemberOf(List<String> memberOf) {
		this.memberOf = memberOf;
	}
	
	public List<String> getMemberOf() {
		return memberOf;
	}

}
