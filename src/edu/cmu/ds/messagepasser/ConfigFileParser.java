package edu.cmu.ds.messagepasser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.yaml.snakeyaml.Yaml;

import edu.cmu.ds.messagepasser.model.Node;
import edu.cmu.ds.messagepasser.model.Rule;

public class ConfigFileParser {

	private Node loggerNode;
	private ArrayList<Node> allNodes = new ArrayList<Node>();
	private ArrayList<Node> peerNodes = new ArrayList<Node>();
	private ArrayList<Rule> sendRules, receiveRules;
	private Node localNode;
	private Integer localNodeIndex = 0;
	private TreeMap<String, ArrayList<String>> groupMembers = new TreeMap<String, ArrayList<String>>();

	@SuppressWarnings("unchecked")
	public ConfigFileParser(String configurationFileName, String localName) throws FileNotFoundException {

		InputStream input = new FileInputStream(new File(configurationFileName));
		Yaml yaml = new Yaml();
		Map<String, Object> configMap = (Map<String, Object>) yaml.load(input);

		parseNodes(configMap, localName);
		parseGroups(configMap);
		parseLogger(configMap);
		sendRules = parseRules(configMap, "sendRules");
		receiveRules = parseRules(configMap, "receiveRules");

	}

	/**
	 * Read configuration map loaded from file, and set up local and peer nodes
	 * 
	 * @param configMap
	 * @param localName
	 */
	@SuppressWarnings("unchecked")
	private void parseNodes(Map<String, Object> configMap, String localName) {
		List<Object> configurationList = (List<Object>) configMap.get("configuration");
		int nodeIndex = 0;
		for (Object c : configurationList) {
			Map<String, Object> configEntry = (Map<String, Object>) c;
			Node node = new Node(configEntry.get("name").toString(), configEntry.get("ip").toString(), new Integer(
					configEntry.get("port").toString()), (ArrayList<String>) configEntry.get("memberOf"));
			if (!configEntry.get("name").equals(localName)) {
				peerNodes.add(node);
			} else {
				localNode = node;
				localNodeIndex = nodeIndex;
			}
			allNodes.add(node);
			nodeIndex++;
		}
	}

	/**
	 * Read configuration map loaded from file, and set up node groups
	 * 
	 * @param configMap
	 */
	@SuppressWarnings("unchecked")
	private void parseGroups(Map<String, Object> configMap) {
		List<Object> groupList = (List<Object>) configMap.get("groups");
		for (Object g : groupList) {
			Map<String, Object> groupEntry = (Map<String, Object>) g;
			String name = groupEntry.get("name").toString();
			ArrayList<String> members = (ArrayList<String>) groupEntry.get("members");
			groupMembers.put(name, members);
		}
	}

	/**
	 * Read configuration map loaded from file, and set up logger
	 * 
	 * @param configMap
	 */
	@SuppressWarnings("unchecked")
	private void parseLogger(Map<String, Object> configMap) {
		List<Object> loggerList = (List<Object>) configMap.get("logger");
		if (loggerList != null && !loggerList.isEmpty()) {
			Map<String, Object> loggerEntry = (Map<String, Object>) loggerList.get(0);
			loggerNode = new Node("", loggerEntry.get("ip").toString(),
					new Integer(loggerEntry.get("port").toString()), null);
		} else {
			throw new RuntimeException("Cannot find config for logger");
		}
	}

	/**
	 * Universal rule parser. Read configuration map loaded from file.
	 * 
	 * @param configMap
	 * @param ruleType
	 *            sendRules or receiveRules
	 * @return List of rules depending on type
	 * @throws FileNotFoundException
	 */
	@SuppressWarnings("unchecked")
	private ArrayList<Rule> parseRules(Map<String, Object> configMap, String ruleType) throws FileNotFoundException {
		ArrayList<Rule> result = new ArrayList<Rule>();

		List<Object> ruleList = (List<Object>) configMap.get(ruleType);
		for (Object r : ruleList) {
			Map<String, Object> ruleEntry = (Map<String, Object>) r;
			String src = null;
			String dest = null;
			String kind = null;
			Integer seqNum = null;
			Boolean dup = null;
			if (ruleEntry.get("src") != null)
				src = ruleEntry.get("src").toString();
			if (ruleEntry.get("dest") != null)
				dest = ruleEntry.get("dest").toString();
			if (ruleEntry.get("kind") != null)
				kind = ruleEntry.get("kind").toString();
			if (ruleEntry.get("seqNum") != null)
				seqNum = Integer.parseInt(ruleEntry.get("seqNum").toString());
			if (ruleEntry.get("duplicate") != null)
				dup = Boolean.parseBoolean(ruleEntry.get("duplicate").toString());
			Rule rule = new Rule(ruleEntry.get("action").toString(), src, dest, kind, seqNum, dup);
			result.add(rule);
		}
		return result;
	}

	public String getLoggerIp() {
		return loggerNode.getIp();
	}

	public int getLoggerPort() {
		return loggerNode.getPort();
	}

	public List<Node> getPeerNodes() {
		return peerNodes;
	}

	public List<Node> getAllNodes() {
		return allNodes;
	}

	public int getLocalNodeIndex() {
		return localNodeIndex;
	}

	public Node getLocalNode() {
		return localNode;
	}

	public List<Rule> getSendRules() {
		return sendRules;
	}

	public List<Rule> getReceiveRules() {
		return receiveRules;
	}

	public TreeMap<String, ArrayList<String>> getGroupMembers() {
		return groupMembers;
	}
}
